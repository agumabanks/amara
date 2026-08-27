package co.sanaa.agent.api

import android.util.Base64
import android.util.Log
import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.BusinessContext
import co.sanaa.agent.core.SecureConfig
import co.sanaa.agent.core.SystemPromptBuilder
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Typed model gateway front-end. Every provider interaction goes through
 * [ModelGateway]: strict single-object JSON parsing, precise failure
 * classification, bounded retries with capped Retry-After, a circuit breaker,
 * bounded schema repair, and durable redacted BrainFailure records. This class
 * NEVER returns an empty JSONObject when no valid JSON exists, and never stores
 * or logs raw provider bodies.
 */
class GroqClient(
    private val config: SecureConfig,
    private val memory: AmaraMemory? = null,
    /**
     * Test seam ONLY: permits the local MockWebServer wire for integration tests.
     * Production construction keeps the strict HTTPS endpoint requirement.
     */
    private val allowInsecureTestEndpoint: Boolean = false,
    /** Test seam: shrinks OkHttp read timeout so timeout paths are testable. */
    readTimeoutMs: Long = DEFAULT_READ_TIMEOUT_MS,
    /** Test seam: injected sleep so retry/backoff tests never actually wait. */
    sleeper: suspend (Long) -> Unit = { delay(it) },
    /** Test seam: injected clock for the circuit breaker cooldown. */
    clock: () -> Long = System::currentTimeMillis,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    internal val gateway = ModelGateway(memory = memory, sleeper = sleeper, clock = clock)

    /** Legacy entry point: strict JSON, no schema. Rejects empty/no JSON with MALFORMED_ASSISTANT_JSON. */
    suspend fun completeJson(prompt: String): JSONObject = completeJson(prompt, null)

    suspend fun completeJson(prompt: String, schema: ModelSchema?): JSONObject = completeJson(prompt, schema, "")

    /**
     * Typed completion: parses the assistant content as exactly ONE JSON object
     * and validates it against [schema]. Schema violations trigger at most one
     * bounded schema-repair follow-up per malformed attempt, all inside the same
     * three-attempt budget. [correlationId] (contract §3) is the owning logical
     * task id threaded into every durable brain-failure row for this call.
     */
    suspend fun completeJson(prompt: String, schema: ModelSchema?, correlationId: String): JSONObject {
        val key = config.groqApiKey.ifBlank { throw IllegalStateException("Groq API key is not configured") }
        val stage = schema?.name ?: STAGE_CHAT_JSON
        // Defense in depth: no credential material can reach the provider request body
        // even if an upstream caller forgot to redact.
        val safePrompt = co.sanaa.agent.core.Redactor.redact(prompt)
        return withContext(Dispatchers.IO) {
            gateway.execute(stage, config.groqModel, allowRepair = true, correlationId = correlationId) { ctx ->
                val messages = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", systemPrompt(jsonMode = true)))
                    .put(JSONObject().put("role", "user").put("content", safePrompt))
                ctx.repairDirective?.let { repair ->
                    messages.put(
                        JSONObject().put("role", "user").put("content", gateway.buildRepairMessage(repair, schema?.name)),
                    )
                }
                val body = JSONObject()
                    .put("model", config.groqModel)
                    .put("temperature", 0.2)
                    .put("max_completion_tokens", 400)
                    .put("response_format", JSONObject().put("type", "json_object"))
                    .put("messages", messages)
                when (val wire = wireCall(key, body, stage)) {
                    is WireOutcome.Failed -> ModelGateway.AttemptResult.Failure(wire.error, wire.retryAfterMs)
                    is WireOutcome.Reply -> jsonOutcome(wire, schema, stage)
                }
            }
        }
    }

    /**
     * Plain completion. jsonOnly=true returns the STRICTLY parsed single JSON
     * object serialized back to text (never brace-hunted); any deviation throws
     * MALFORMED_ASSISTANT_JSON instead of returning "{}".
     */
    suspend fun complete(prompt: String, jsonOnly: Boolean = false, correlationId: String = ""): String = withContext(Dispatchers.IO) {
        if (jsonOnly) return@withContext completeJson(prompt, null, correlationId).toString()
        val key = config.groqApiKey.ifBlank { throw IllegalStateException("Groq API key is not configured") }
        gateway.execute(STAGE_CHAT_TEXT, config.groqModel, allowRepair = false, correlationId = correlationId) { _ ->
            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemPrompt(jsonMode = false)))
                .put(JSONObject().put("role", "user").put("content", co.sanaa.agent.core.Redactor.redact(prompt)))
            val body = JSONObject()
                .put("model", config.groqModel)
                .put("temperature", 0.2)
                .put("max_completion_tokens", 400)
                .put("messages", messages)
            when (val wire = wireCall(key, body, STAGE_CHAT_TEXT)) {
                is WireOutcome.Failed -> ModelGateway.AttemptResult.Failure(wire.error, wire.retryAfterMs)
                is WireOutcome.Reply -> ModelGateway.AttemptResult.Success(wire.content.trim())
            }
        }
    }

    /** Legacy vision entry point: strict JSON, no schema validation. */
    suspend fun completeVisionJson(prompt: String, imageFile: File): JSONObject =
        completeVisionJson(prompt, imageFile, null)

    suspend fun completeVisionJson(prompt: String, imageFile: File, schema: ModelSchema?): JSONObject =
        completeVisionJson(prompt, imageFile, schema, "")

    /** Vision completion with typed schema validation, bounded repair, and task correlation. */
    suspend fun completeVisionJson(prompt: String, imageFile: File, schema: ModelSchema?, correlationId: String): JSONObject {
        return withContext(Dispatchers.IO) {
            require(config.visionConsent) { "The owner has not consented to sending screenshots to the vision model." }
            val key = config.groqApiKey.ifBlank { throw IllegalStateException("Groq API key is not configured") }
            val model = config.groqVisionModel.ifBlank { throw IllegalStateException("A Groq vision model is not configured") }
            require(imageFile.isFile && imageFile.length() in 1..MAX_IMAGE_BYTES) {
                "Visual evidence must be a private image under ${MAX_IMAGE_BYTES / 1_000_000} MB"
            }
            val mime = if (imageFile.extension.equals("jpg", true) || imageFile.extension.equals("jpeg", true)) "image/jpeg" else "image/png"
            val encoded = Base64.encodeToString(imageFile.readBytes(), Base64.NO_WRAP)
            // Text parts are redacted; the base64 image payload is untouched.
            val safePrompt = co.sanaa.agent.core.Redactor.redact(prompt)
            val stage = schema?.name ?: STAGE_VISION
            gateway.execute(stage, model, allowRepair = true, correlationId = correlationId) { ctx ->
                val parts = JSONArray()
                    .put(JSONObject().put("type", "text").put("text", safePrompt))
                    .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:$mime;base64,$encoded")))
                val messages = JSONArray().put(JSONObject().put("role", "user").put("content", parts))
                ctx.repairDirective?.let { repair ->
                    messages.put(
                        JSONObject().put("role", "user").put("content", gateway.buildRepairMessage(repair, schema?.name)),
                    )
                }
                val body = JSONObject()
                    .put("model", model)
                    .put("temperature", 0.1)
                    .put("max_completion_tokens", 700)
                    .put("response_format", JSONObject().put("type", "json_object"))
                    .put("messages", messages)
                when (val wire = wireCall(key, body, stage)) {
                    is WireOutcome.Failed -> ModelGateway.AttemptResult.Failure(wire.error, wire.retryAfterMs)
                    is WireOutcome.Reply -> jsonOutcome(wire, schema, stage)
                }
            }
        }
    }

    suspend fun ping(): Boolean = try {
        complete("Return the single word READY.").isNotBlank()
    } catch (error: Exception) {
        Log.w(TAG, "ping failed: ${error.javaClass.simpleName}")
        false
    }

    fun testConnection(result: MethodChannel.Result) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { complete("Reply with one warm sentence confirming you are ready for work.") }
                .onSuccess { withContext(Dispatchers.Main) { result.success(it) } }
                .onFailure { withContext(Dispatchers.Main) { result.error("GROQ_ERROR", it.message, null) } }
        }
    }

    private fun systemPrompt(jsonMode: Boolean): String {
        val context = BusinessContext(
            config.agentName, config.businessName, "UGX ${config.orderThresholdUgx}",
            memoryContext = runCatching { memory?.promptContext() }.getOrNull() ?: "No device memory available",
        )
        val base = SystemPromptBuilder.build(context)
        return if (jsonMode) "$base\nReturn ONLY valid JSON. No commentary." else base
    }

    private sealed class WireOutcome {
        data class Reply(
            val content: String,
            val requestId: String,
            @Suppress("unused") val rawBody: String,
            val hash: String,
        ) : WireOutcome()

        data class Failed(val error: ModelResponseException, val retryAfterMs: Long? = null) : WireOutcome()
    }

    /**
     * One blocking wire exchange: HTTP POST + precise classification into
     * [WireOutcome.Reply] or [WireOutcome.Failed]. Raw bodies are hashed, never
     * logged, never persisted, and never echoed back to the provider.
     */
    private fun wireCall(key: String, body: JSONObject, stage: String): WireOutcome {
        val endpoint = trustedHttpsEndpoint(config.groqEndpoint)
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(JSON)).build()
        Log.i(TAG, "Model call: stage=$stage model=${body.optString("model")} requestBytes=${body.length()}")
        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            return WireOutcome.Failed(classifiedTransportFailure(error, stage))
        }
        val outcome = response.use { resp ->
            val rawBody = try {
                resp.body?.string().orEmpty()
            } catch (error: IOException) {
                // Read timeouts surface while streaming the body, not at execute().
                return@use WireOutcome.Failed(classifiedTransportFailure(error, stage))
            }
            val hash = ModelGateway.sha256Hex(rawBody)
            val requestId = resp.header(REQUEST_ID_HEADER).orEmpty()
            Log.i(TAG, "Model response: code=${resp.code} bodyLength=${rawBody.length}")

            fun failed(kind: ModelFailureKind, message: String, errors: List<String>, retryAfterMs: Long? = null) =
                WireOutcome.Failed(attachRequestId(ModelResponseException(message, kind, stage, hash, validationErrors = errors), requestId), retryAfterMs)

            when {
                resp.code == 429 -> failed(
                    ModelFailureKind.RATE_LIMITED, "Rate limited by the model provider (429)",
                    listOf("HTTP_429"), ModelGateway.parseRetryAfterSeconds(resp.header("Retry-After")),
                )
                resp.code == 408 -> failed(
                    ModelFailureKind.SERVER_RETRYABLE, "Model provider request timeout (408)", listOf("HTTP_408"),
                )
                resp.code in 500..599 -> failed(
                    ModelFailureKind.SERVER_RETRYABLE, "Model provider server error (${resp.code})", listOf("HTTP_${resp.code}"),
                )
                resp.code in 400..499 -> failed(
                    ModelFailureKind.PERMANENT_CLIENT, "Model provider rejected the request permanently (${resp.code})",
                    listOf("HTTP_${resp.code}"),
                )
                !resp.isSuccessful -> failed(
                    ModelFailureKind.SERVER_RETRYABLE, "Unexpected model provider status (${resp.code})", listOf("HTTP_${resp.code}"),
                )
                else -> when (val extracted = extractEnvelopeContent(rawBody)) {
                    is EnvelopeResult.EnvelopeMalformed -> failed(
                        ModelFailureKind.MALFORMED_ENVELOPE, "HTTP 200 body was not a chat-completion envelope: ${extracted.detail}",
                        listOf(extracted.detail),
                    )
                    is EnvelopeResult.EmptyContent -> failed(
                        ModelFailureKind.MALFORMED_ASSISTANT_JSON, "Assistant content was empty",
                        listOf("assistant content was empty"),
                    )
                    is EnvelopeResult.Content -> WireOutcome.Reply(extracted.text, requestId, rawBody, hash)
                }
            }
        }
        return outcome
    }

    private fun classifiedTransportFailure(error: IOException, stage: String): ModelResponseException {
        val kind = if (error is SocketTimeoutException) ModelFailureKind.TIMEOUT else ModelFailureKind.TRANSPORT
        val message = if (kind == ModelFailureKind.TIMEOUT) {
            "Model provider read timed out after ${client.readTimeoutMillis}ms"
        } else {
            "Network transport failure contacting the model provider (${error.javaClass.simpleName})"
        }
        Log.w(TAG, "Model call transport failure: $message")
        return ModelResponseException(message, kind, stage, ModelGateway.sha256Hex(""), validationErrors = listOf(kind.name))
    }

    private sealed class EnvelopeResult {
        data class Content(val text: String) : EnvelopeResult()
        data class EnvelopeMalformed(val detail: String) : EnvelopeResult()
        object EmptyContent : EnvelopeResult()
    }

    private fun extractEnvelopeContent(rawBody: String): EnvelopeResult {
        val envelope = try {
            JSONObject(rawBody)
        } catch (_: Exception) {
            return EnvelopeResult.EnvelopeMalformed("body is not JSON")
        }
        val choices = envelope.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            return EnvelopeResult.EnvelopeMalformed("choices[] missing or empty")
        }
        val message = choices.optJSONObject(0)?.optJSONObject("message")
            ?: return EnvelopeResult.EnvelopeMalformed("choices[0].message missing")
        val content = message.opt("content")
        if (content !is String) return EnvelopeResult.EnvelopeMalformed("choices[0].message.content missing or not a string")
        if (content.isBlank()) return EnvelopeResult.EmptyContent
        return EnvelopeResult.Content(content)
    }

    /** Strict parse + optional schema validation of a successful reply. */
    private fun jsonOutcome(
        reply: WireOutcome.Reply,
        schema: ModelSchema?,
        stage: String,
    ): ModelGateway.AttemptResult<JSONObject> = when (val parsed = StrictJson.parse(reply.content)) {
        is StrictJson.Result.Bad -> ModelGateway.AttemptResult.Failure(
            ModelResponseException(
                "Assistant output was not a single valid JSON object",
                ModelFailureKind.MALFORMED_ASSISTANT_JSON, stage, reply.hash,
                validationErrors = parsed.errors,
                rawRedactedExcerpt = gateway.redactedExcerpt(reply.content),
            ),
        )
        is StrictJson.Result.Ok -> {
            if (schema == null) {
                ModelGateway.AttemptResult.Success(parsed.value)
            } else {
                val errors = schema.validate(parsed.value)
                if (errors.isNotEmpty()) {
                    ModelGateway.AttemptResult.Failure(
                        ModelResponseException(
                            "Assistant output violated schema '${schema.name}'",
                            ModelFailureKind.SCHEMA_VIOLATION, stage, reply.hash,
                            validationErrors = errors,
                            rawRedactedExcerpt = gateway.redactedExcerpt(reply.content),
                        ),
                    )
                } else ModelGateway.AttemptResult.Success(parsed.value)
            }
        }
    }

    /** X-Request-Id rides the cause chain into durable records without widening the public exception API. */
    private fun attachRequestId(error: ModelResponseException, requestId: String): ModelResponseException =
        if (requestId.isBlank()) error
        else ModelResponseException(
            error.message ?: "model call failed", error.kind, error.stage, error.responseHash, error.attemptCount,
            error.validationErrors, error.rawRedactedExcerpt, ModelGateway.ProviderRequestId(requestId),
        )

    private fun trustedHttpsEndpoint(value: String) = value.toHttpUrlOrNull()
        ?.takeIf { it.isHttps || allowInsecureTestEndpoint }
        ?: throw IllegalStateException("The Groq endpoint must be a valid HTTPS URL")

    @Deprecated(
        "Use co.sanaa.agent.api.ModelResponseException",
        ReplaceWith("ModelResponseException(message, ModelFailureKind.MALFORMED_ASSISTANT_JSON, cause = cause)"),
    )
    class MalformedModelResponse(message: String, rawResponse: String, cause: Throwable) :
        co.sanaa.agent.api.MalformedModelResponse(message, rawResponse, cause)

    companion object {
        val JSON = "application/json".toMediaType()
        const val TAG = "SanaaGroq"
        const val REQUEST_ID_HEADER = "X-Request-Id"
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val DEFAULT_READ_TIMEOUT_MS = 45_000L
        const val MAX_IMAGE_BYTES = 8_000_000
        const val STAGE_CHAT_JSON = "chat_json"
        const val STAGE_CHAT_TEXT = "chat_text"
        const val STAGE_VISION = "vision"
    }
}
