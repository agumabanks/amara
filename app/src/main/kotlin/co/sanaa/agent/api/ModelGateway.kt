package co.sanaa.agent.api

import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.Redactor
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Precise taxonomy of provider/model failure modes. [retryable] marks kinds the
 * gateway is allowed to retry within one logical call; PERMANENT_CLIENT and
 * POLICY_REJECTED always get exactly ONE attempt.
 */
enum class ModelFailureKind(val retryable: Boolean) {
    TRANSPORT(true),
    TIMEOUT(true),
    RATE_LIMITED(true),
    SERVER_RETRYABLE(true),
    PERMANENT_CLIENT(false),
    MALFORMED_ENVELOPE(true),
    MALFORMED_ASSISTANT_JSON(true),
    SCHEMA_VIOLATION(true),
    POLICY_REJECTED(false),
    CIRCUIT_OPEN(false),
}

/** Kinds eligible for the schema-repair follow-up within the same attempt budget. */
val REPAIR_ELIGIBLE_KINDS: Set<ModelFailureKind> =
    setOf(ModelFailureKind.MALFORMED_ASSISTANT_JSON, ModelFailureKind.SCHEMA_VIOLATION)

/**
 * Typed failure raised by the model gateway. Carries only non-sensitive
 * diagnostics: a sha-256 response hash, structural validation errors, and an
 * already-redacted excerpt (never a raw provider body, never a secret).
 *
 * Extends [IllegalStateException] deliberately so existing legacy catch sites
 * (`is IllegalStateException -> ModelCall.Failure` in MorningBroadcastModule and
 * FollowUpEngine) keep isolating model failures until their owners widen the
 * catch to this type directly.
 */
open class ModelResponseException(
    message: String,
    val kind: ModelFailureKind,
    val stage: String = "",
    val responseHash: String = "",
    val attemptCount: Int = 0,
    val validationErrors: List<String> = emptyList(),
    val rawRedactedExcerpt: String? = null,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Deprecated alias kept so existing `catch (e: GroqClient.MalformedModelResponse)`
 * call sites still compile. New code must catch [ModelResponseException].
 */
@Deprecated(
    "Use ModelResponseException with ModelFailureKind.MALFORMED_ASSISTANT_JSON",
)
open class MalformedModelResponse(
    message: String,
    val rawResponse: String,
    cause: Throwable,
) : ModelResponseException(
    message,
    ModelFailureKind.MALFORMED_ASSISTANT_JSON,
    rawRedactedExcerpt = runCatching { Redactor.redact(rawResponse).take(EXCERPT_CHARS) }.getOrNull(),
    cause = cause,
) {
    companion object {
        const val EXCERPT_CHARS = 200
    }
}

/** Dispositions persisted into AmaraMemory.brain_failures. */
object FailureDispositions {
    const val FAILED_PERMANENT = "FAILED_PERMANENT"
    const val RETRY_EXHAUSTED = "RETRY_EXHAUSTED"
    const val CIRCUIT_OPEN = "CIRCUIT_OPEN"
    const val RETRY_SCHEDULED = "RETRY_SCHEDULED"
    const val REPAIR_REQUESTED = "REPAIR_REQUESTED"
}

/**
 * Terminal outcome vocabulary (contract §2) written ONCE per logical task failure
 * by the module/controller that owns the task, via [BrainFailureFinalizer].
 */
object TerminalOutcomes {
    const val TASK_FAILED_NO_SIDE_EFFECT = "TASK_FAILED_NO_SIDE_EFFECT"
    const val TASK_FAILED_SAFE_STATE = "TASK_FAILED_SAFE_STATE"
    const val RECOVERED_SCHEMA_VALID = "RECOVERED_SCHEMA_VALID"
    const val AWAITING_OWNER_RETRY = "AWAITING_OWNER_RETRY"
}

/**
 * Finalizes durable brain-failure rows with the terminal disposition of the OWNING
 * logical task (contract §2). Never inserts: it only updates the newest open row
 * matching (correlationId, stage). Owner-facing explanations are fixed typed
 * sentences built here — never exception text, stack traces, or provider bodies —
 * and are re-checked against the secret-shape detector as defense in depth.
 */
object BrainFailureFinalizer {

    /** Marks the task's open failure row with an explicit terminal outcome. */
    fun finalizeTaskOutcome(
        memory: AmaraMemory?,
        correlationId: String,
        stage: String,
        terminalOutcome: String,
        ownerExplanation: String,
    ): Boolean {
        val store = memory ?: return false
        if (correlationId.isBlank()) return false
        val safeExplanation = Redactor.redact(ownerExplanation)
            .replace(Regex("\\s+"), " ")
            .take(MAX_OWNER_EXPLANATION_CHARS)
        if (Redactor.containsSecretShape(safeExplanation)) return false
        return runCatching {
            store.finalizeBrainFailure(correlationId, stage, terminalOutcome, safeExplanation)
        }.getOrDefault(false)
    }

    /**
     * After a model call SUCCEEDED, marks its earlier repair rows as fully recovered.
     * Returns true when at least one open row for this task existed and was finalized;
     * a clean first-attempt success finalizes nothing and returns false.
     */
    fun markRecovered(memory: AmaraMemory?, correlationId: String, stage: String): Boolean {
        val store = memory ?: return false
        if (correlationId.isBlank()) return false
        val hadOpenRows = runCatching {
            store.brainFailuresByCorrelation(correlationId).any {
                it.stage == stage && it.terminalOutcome.isBlank()
            }
        }.getOrDefault(false)
        if (!hadOpenRows) return false
        return finalizeTaskOutcome(
            store, correlationId, stage, TerminalOutcomes.RECOVERED_SCHEMA_VALID,
            "The model output was repaired within the bounded attempt budget and validated " +
                "against the required schema before any action was planned.",
        )
    }

    /**
     * Standard terminal outcome + owner sentence after a logical model task FAILED:
     * retryable kinds exhausted their budget without side effects; permanent kinds
     * await an owner retry. Nothing was dispatched by the model layer itself.
     */
    fun finalizeFailed(
        memory: AmaraMemory?,
        correlationId: String,
        stage: String,
        kind: ModelFailureKind,
        subject: String,
    ): Boolean {
        val terminal = if (kind.retryable || kind == ModelFailureKind.CIRCUIT_OPEN) {
            TerminalOutcomes.TASK_FAILED_NO_SIDE_EFFECT
        } else {
            TerminalOutcomes.AWAITING_OWNER_RETRY
        }
        val explanation = when (terminal) {
            TerminalOutcomes.AWAITING_OWNER_RETRY ->
                "The $subject model reply was permanently rejected before anything was done; you can retry the task."
            else ->
                "The $subject model reply stayed invalid through the full bounded attempt budget; " +
                    "the task stopped with no changes and no messages sent."
        }
        return finalizeTaskOutcome(memory, correlationId, stage, terminal, explanation)
    }

    private const val MAX_OWNER_EXPLANATION_CHARS = 300
}

internal enum class BreakerState { CLOSED, OPEN, HALF_OPEN }

/**
 * Circuit breaker over retryable-terminal model failures. After [threshold]
 * consecutive exhausted-retry calls the breaker opens for [cooldownMs]; while
 * open every call fails fast with [ModelFailureKind.CIRCUIT_OPEN]. After the
 * cooldown exactly one half-open probe is admitted; success closes the breaker.
 */
class CircuitBreaker(
    private val threshold: Int = 5,
    private val cooldownMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var consecutiveRetryableTerminalFailures = 0
    private var openedAtMs = -1L
    private var halfOpenProbeAdmitted = false

    @Synchronized
    internal fun admission(): BreakerState {
        if (openedAtMs < 0) return BreakerState.CLOSED
        return if (clock() - openedAtMs >= cooldownMs) {
            if (!halfOpenProbeAdmitted) {
                halfOpenProbeAdmitted = true
                BreakerState.HALF_OPEN
            } else BreakerState.OPEN
        } else BreakerState.OPEN
    }

    @Synchronized
    fun onSuccess() {
        reset()
    }

    @Synchronized
    fun onRetryableTerminalFailure(admissionWasHalfOpen: Boolean) {
        if (admissionWasHalfOpen) {
            // The half-open probe failed: restart the cooldown window immediately.
            openedAtMs = clock()
            halfOpenProbeAdmitted = false
            consecutiveRetryableTerminalFailures += 1
            return
        }
        consecutiveRetryableTerminalFailures += 1
        if (openedAtMs < 0 && consecutiveRetryableTerminalFailures >= threshold) {
            openedAtMs = clock()
            halfOpenProbeAdmitted = false
        }
    }

    private fun reset() {
        consecutiveRetryableTerminalFailures = 0
        openedAtMs = -1L
        halfOpenProbeAdmitted = false
    }
}

/**
 * Strict single-JSON-object parser for assistant output. No substring
 * brace-hunting fallback exists anywhere in this object by design.
 */
internal object StrictJson {

    sealed class Result {
        data class Ok(val value: JSONObject) : Result()
        data class Bad(val errors: List<String>) : Result()
    }

    /** JSONTokener signals end-of-input with this character. */
    private const val END_OF_INPUT: Char = '\u0000'

    fun parse(content: String): Result {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            return Result.Bad(listOf("assistant content was empty"))
        }
        var candidate = trimmed
        if (candidate.startsWith("```")) {
            candidate = stripSingleCodeFence(candidate)
                ?: return Result.Bad(listOf("code-fenced output was not exactly one fenced JSON document"))
            if (candidate.startsWith("```")) {
                return Result.Bad(listOf("output contained more than one code fence"))
            }
        }
        val tokener = JSONTokener(candidate)
        val value: Any? = try {
            tokener.nextValue()
        } catch (_: JSONException) {
            // Covers truncated / unbalanced JSON such as {"a":1 or {"a":.
            // The provider exception message is DELIBERATELY discarded because
            // org.json embeds slices of the raw input (which may hold secrets)
            // in its messages. Only a structural description survives.
            return Result.Bad(listOf(
                "content is not parseable as JSON; unbalanced braces or truncated JSON",
            ))
        }
        if (value !is JSONObject) {
            return Result.Bad(listOf("top-level JSON value must be an object but was ${rootTypeName(value)}"))
        }
        val trailing: Char = try {
            tokener.nextClean()
        } catch (_: JSONException) {
            return Result.Bad(listOf("trailing content after the JSON object could not be read"))
        }
        if (trailing != END_OF_INPUT) {
            return Result.Bad(listOf("multiple top-level JSON values or trailing content after the single object"))
        }
        return Result.Ok(value)
    }

    /**
     * Strips EXACTLY one leading fence line and one trailing fence marker.
     * Returns null whenever the wrapper does not match that strict shape.
     */
    private fun stripSingleCodeFence(raw: String): String? {
        val afterTicks = raw.substring(3)
        val newlineIndex = afterTicks.indexOf('\n')
        // Fence must open its own line: ```json\n{...}\n```
        if (newlineIndex < 0) return null
        val rest = afterTicks.substring(newlineIndex + 1)
        val closeIndex = rest.lastIndexOf("```")
        if (closeIndex < 0) return null
        val tail = rest.substring(closeIndex + 3).trim()
        if (tail.isNotEmpty()) return null
        return rest.substring(0, closeIndex).trim()
    }

    private fun rootTypeName(value: Any?): String = when (value) {
        is JSONArray -> "array"
        is String -> "string"
        is Number -> "number"
        is Boolean -> "boolean"
        else -> value?.javaClass?.simpleName ?: "null"
    }
}

/**
 * Typed model gateway shared by every GroqClient call path. Owns the retry
 * policy, bounded backoff/jitter, Retry-After capping, circuit breaking,
 * schema-repair directives, strict JSON parsing hooks, and durable redacted
 * BrainFailure persistence. Phone-side side effects NEVER pass through here.
 *
 * All sleeps go through the injected [sleeper] and all time reads through
 * [clock] so tests run instantly and deterministically.
 */
class ModelGateway(
    private val memory: AmaraMemory? = null,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val backoffBaseMs: Long = DEFAULT_BACKOFF_BASE_MS,
    private val jitterMaxMs: Long = DEFAULT_JITTER_MAX_MS,
    private val maxRetryAfterMs: Long = DEFAULT_MAX_RETRY_AFTER_MS,
    breakerThreshold: Int = DEFAULT_BREAKER_THRESHOLD,
    breakerCooldownMs: Long = DEFAULT_BREAKER_COOLDOWN_MS,
    private val jitterSource: Random = Random.Default,
) {
    internal val breaker = CircuitBreaker(breakerThreshold, breakerCooldownMs, clock)

    sealed class AttemptResult<out T> {
        data class Success<T>(val value: T) : AttemptResult<T>()
        data class Failure(val error: ModelResponseException, val retryAfterMs: Long? = null) : AttemptResult<Nothing>()
    }

    /** What one wire attempt receives: its 1-based number and any repair directive. */
    class CallContext(val attemptNumber: Int, val repairDirective: RepairDirective?)

    /**
     * Bounded-repair directive built from the previous rejected output. Contains
     * only validation errors, a sha-256 hash reference, and at most the FIRST 200
     * REDACTED characters of the rejected output (withheld entirely when the
     * redacted text still matches a secret shape).
     */
    data class RepairDirective(
        val validationErrors: List<String>,
        val rejectedHash: String,
        val rejectedExcerpt: String?,
    )

    /**
     * Runs one logical model call: up to [maxAttempts] wire attempts, precise
     * failure classification, bounded backoff, one schema-repair follow-up per
     * malformed attempt, circuit-breaker enforcement, and durable BrainFailure
     * persistence for every classified failure. [correlationId] is the OWNING
     * task's logical id (contract §3, e.g. "task-$taskId" or "<module>-<runId>");
     * it rides every persisted row so recovery can be traced end to end, while
     * provider request ids keep riding the exception cause chain separately.
     */
    suspend fun <T> execute(
        stage: String,
        model: String,
        allowRepair: Boolean,
        correlationId: String = "",
        block: suspend (CallContext) -> AttemptResult<T>,
    ): T {
        var repair: RepairDirective? = null
        var attemptNumber = 0
        val rejectedHashes = HashSet<String>()
        // Admitted ONCE per logical call: an open breaker fails the whole call
        // fast, while an admitted half-open probe keeps its full attempt budget.
        val admission = breaker.admission()
        if (admission == BreakerState.OPEN) {
            val error = ModelResponseException(
                "Model gateway circuit breaker is open for stage '$stage'; failing fast",
                ModelFailureKind.CIRCUIT_OPEN,
                stage,
                sha256Hex(""),
                attemptCount = 0,
            )
            record(stage, model, requestId(error), responseHash = error.responseHash, attemptCount = 0,
                retryable = true, errors = emptyList(), correctiveAction = "FAIL_FAST_CIRCUIT_OPEN",
                disposition = FailureDispositions.CIRCUIT_OPEN, correlationId, error)
            throw error
        }
        while (true) {
            attemptNumber += 1
            when (val outcome = block(CallContext(attemptNumber, repair))) {
                is AttemptResult.Success -> {
                    breaker.onSuccess()
                    return outcome.value
                }
                is AttemptResult.Failure -> {
                    val raw = outcome.error
                    val error = enrich(raw, attemptNumber)
                    // Replayed-response detection: a byte-identical rejected body on a
                    // later attempt means the provider/intermediary is echoing, not
                    // regenerating. Classified and persisted; retry bounds unchanged.
                    val replayed = error.responseHash.isNotBlank() && !rejectedHashes.add(error.responseHash)
                    val lastAttempt = attemptNumber >= maxAttempts
                    if (!error.kind.retryable) {
                        record(stage, model, requestId(error), error.responseHash, attemptNumber, retryable = false,
                            errors = error.validationErrors, correctiveAction = prefixIf(replayed, "NO_RETRY_PERMANENT"),
                            disposition = FailureDispositions.FAILED_PERMANENT, correlationId, error)
                        throw error
                    }
                    if (lastAttempt) {
                        record(stage, model, requestId(error), error.responseHash, attemptNumber, retryable = true,
                            errors = error.validationErrors, correctiveAction = prefixIf(replayed, "RETRY_BUDGET_EXHAUSTED"),
                            disposition = FailureDispositions.RETRY_EXHAUSTED, correlationId, error)
                        breaker.onRetryableTerminalFailure(admission == BreakerState.HALF_OPEN)
                        throw error
                    }
                    if (allowRepair && error.kind in REPAIR_ELIGIBLE_KINDS) {
                        repair = RepairDirective(error.validationErrors, error.responseHash, error.rawRedactedExcerpt)
                        record(stage, model, requestId(error), error.responseHash, attemptNumber, retryable = true,
                            errors = error.validationErrors, correctiveAction = prefixIf(replayed, "SCHEMA_REPAIR"),
                            disposition = FailureDispositions.REPAIR_REQUESTED, correlationId, error)
                    } else {
                        repair = null
                        record(stage, model, requestId(error), error.responseHash, attemptNumber, retryable = true,
                            errors = error.validationErrors, correctiveAction = prefixIf(replayed, "RETRY_BACKOFF"),
                            disposition = FailureDispositions.RETRY_SCHEDULED, correlationId, error)
                    }
                    sleeper(backoffDelayMs(error.kind, outcome.retryAfterMs, attemptNumber))
                }
            }
        }
    }

    private fun backoffDelayMs(kind: ModelFailureKind, retryAfterMs: Long?, completedAttempt: Int): Long {
        val exponential = backoffBaseMs shl (completedAttempt - 1).coerceAtLeast(0)
        val jittered = exponential + jitterSource.nextLong(jitterMaxMs + 1)
        return when (kind) {
            ModelFailureKind.RATE_LIMITED -> minOf(retryAfterMs ?: jittered, maxRetryAfterMs)
            else -> minOf(jittered, maxRetryAfterMs)
        }.coerceIn(0L, maxRetryAfterMs)
    }

    private fun enrich(raw: ModelResponseException, attemptNumber: Int): ModelResponseException =
        if (raw.attemptCount == attemptNumber) raw else ModelResponseException(
            raw.message ?: "model call failed", raw.kind, raw.stage, raw.responseHash,
            attemptNumber, raw.validationErrors, raw.rawRedactedExcerpt, raw.cause,
        )

    /** Marks corrective actions where the provider echoed an already-rejected body. */
    private fun prefixIf(replayed: Boolean, action: String): String =
        if (replayed) "REPLAYED_$action" else action

    private fun requestId(error: ModelResponseException): String = (error.cause as? ProviderRequestId)?.requestId.orEmpty()

    /** Carrier for the X-Request-Id response header through the exception chain. */
    class ProviderRequestId(val requestId: String) : RuntimeException()

    private fun record(
        stage: String,
        model: String,
        requestId: String,
        responseHash: String,
        attemptCount: Int,
        retryable: Boolean,
        errors: List<String>,
        correctiveAction: String,
        disposition: String,
        correlationId: String,
        error: ModelResponseException,
    ) {
        val store = memory ?: return
        runCatching {
            store.recordBrainFailure(
                stage, model, requestId, responseHash, attemptCount, retryable,
                JSONArray(errors.take(MAX_PERSISTED_ERRORS)).toString(),
                "$correctiveAction:${error.kind.name}",
                disposition,
                correlationId = correlationId,
                terminalOutcome = "",
                ownerExplanation = "",
            )
        }
    }

    /**
     * Redacted excerpt for repair directives and exceptions: at most the first
     * 200 characters AFTER full redaction; withheld entirely when the redacted
     * text still contains a recognizable secret shape.
     */
    fun redactedExcerpt(rawContent: String): String? {
        val redacted = Redactor.redact(rawContent)
        if (Redactor.containsSecretShape(redacted)) return null
        return redacted.take(EXCERPT_CHARS)
    }

    fun buildRepairMessage(directive: RepairDirective, schemaName: String?): String = buildString {
        append("Your previous reply was rejected by strict validation. Respond again with EXACTLY ONE corrected JSON object and nothing else.\n")
        append("Validation errors:\n")
        if (directive.validationErrors.isEmpty()) {
            append("- output was not valid JSON\n")
        } else {
            directive.validationErrors.forEach { rawError ->
                val safe = Redactor.redact(rawError)
                append("- ")
                    .append(if (Redactor.containsSecretShape(safe)) "[withheld: secret-shaped validation detail]" else safe)
                    .append('\n')
            }
        }
        append("Rejected output sha-256 hash reference: ").append(directive.rejectedHash).append('\n')
        val excerpt = directive.rejectedExcerpt
        if (excerpt.isNullOrEmpty()) {
            append("Your rejected reply contained secret-shaped content and was withheld entirely; regenerate it from scratch.\n")
        } else {
            append("First ").append(excerpt.length).append(" redacted characters of your rejected reply:\n")
                .append(excerpt).append('\n')
        }
        schemaName?.let { append("Required JSON schema: ").append(it).append('\n') }
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val DEFAULT_BACKOFF_BASE_MS = 800L
        const val DEFAULT_JITTER_MAX_MS = 300L
        const val DEFAULT_MAX_RETRY_AFTER_MS = 60_000L
        const val DEFAULT_BREAKER_THRESHOLD = 5
        const val DEFAULT_BREAKER_COOLDOWN_MS = 60_000L
        const val EXCERPT_CHARS = 200
        const val MAX_PERSISTED_ERRORS = 20

        fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** Parses a Retry-After header in seconds; returns null when absent/invalid. */
        fun parseRetryAfterSeconds(header: String?): Long? =
            header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(1000)
    }
}
