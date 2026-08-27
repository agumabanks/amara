package co.sanaa.agent.api

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.SecureConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Wire-level behavior of the typed model gateway over MockWebServer with real
 * SQLite memory. Every sleep is captured by an injected sleeper and every clock
 * read by an injected clock, so backoff bounds, Retry-After capping, and
 * circuit-breaker cooldowns are asserted exactly without waiting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModelGatewayBehaviorTest {

    private lateinit var context: Context
    private lateinit var config: SecureConfig
    private lateinit var memory: AmaraMemory
    private lateinit var server: MockWebServer
    private val sleeps = mutableListOf<Long>()
    private var fakeNow = 1_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(AmaraMemory.DATABASE_NAME)
        memory = AmaraMemory(context)
        config = SecureConfig(context, useEncryptedPrefs = false)
        server = MockWebServer()
        server.start()
        config.groqApiKey = "test-key"
        config.groqEndpoint = server.url("/v1/chat/completions").toString()
        config.groqVisionModel = "test-vision-model"
        config.visionConsent = true
        sleeps.clear()
        fakeNow = 1_000_000L
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun client(readTimeoutMs: Long = 45_000L): GroqClient = GroqClient(
        config,
        memory,
        allowInsecureTestEndpoint = true,
        readTimeoutMs = readTimeoutMs,
        sleeper = { ms -> sleeps.add(ms) },
        clock = { fakeNow },
    )

    private fun groqContent(content: String): MockResponse {
        val quoted = JSONObject.quote(content)
        return MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":$quoted},"finish_reason":"stop"}]}""")
    }

    private fun enqueueAll(vararg responses: MockResponse) {
        responses.forEach { server.enqueue(it.setHeader("Content-Type", "application/json")) }
    }

    private fun statusResponses(code: Int, count: Int) {
        repeat(count) { server.enqueue(MockResponse().setResponseCode(code).setBody("{}")) }
    }

    private fun fixedDispatcher(status: Int, body: String = "{}"): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    }

    private fun contentDispatcher(content: String): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            groqContent(content).setHeader("Content-Type", "application/json")
    }

    private suspend fun expectFailure(block: suspend () -> Any?): ModelResponseException =
        try {
            block()
            fail("expected ModelResponseException")
            throw IllegalStateException("unreachable")
        } catch (expected: ModelResponseException) {
            expected
        }

    private fun brainRows() = memory.recentBrainFailures(100)

    // ------------------------------------------------------------------ strict JSON

    @Test
    fun emptyContentIsRejectedAsMalformedAssistantJsonNotAnEmptyObject() = runBlocking {
        enqueueAll(groqContent(""), groqContent(""), groqContent(""))
        val error = expectFailure { client().completeJson("hello") }
        assertEquals(ModelFailureKind.MALFORMED_ASSISTANT_JSON, error.kind)
        assertEquals(3, server.requestCount)
        val row = brainRows().first { it.disposition == "RETRY_EXHAUSTED" }
        assertEquals(3, row.attemptCount)
        assertTrue(row.validationErrorsJson.contains("empty"))
    }

    @Test
    fun fencedJsonIsAcceptedExactlyOnce() = runBlocking {
        enqueueAll(groqContent("```json\n{\"ok\":true}\n```"))
        val result = client().completeJson("hello")
        assertEquals(true, result.optBoolean("ok"))
        assertEquals(1, server.requestCount)
        assertTrue(brainRows().isEmpty())
    }

    @Test
    fun truncatedUnbalancedJsonIsRejected() = runBlocking {
        enqueueAll(groqContent("{\"a\":1"), groqContent("{\"a\":1"), groqContent("{\"a\":1"))
        val error = expectFailure { client().completeJson("hello") }
        assertEquals(ModelFailureKind.MALFORMED_ASSISTANT_JSON, error.kind)
        assertTrue(error.validationErrors.first().contains("parseable"))
    }

    @Test
    fun multiObjectStreamsAreRejected() = runBlocking {
        enqueueAll(groqContent("{\"a\":1} {\"b\":2}"), groqContent("{}{}"), groqContent("{} {}"))
        val error = expectFailure { client().completeJson("hello") }
        assertEquals(ModelFailureKind.MALFORMED_ASSISTANT_JSON, error.kind)
        assertTrue(error.validationErrors.single().contains("multiple top-level"))
    }

    @Test
    fun nonObjectRootsAreRejected() = runBlocking {
        enqueueAll(groqContent("[1,2,3]"), groqContent("[1,2,3]"), groqContent("[1,2,3]"))
        val arrayRoot = expectFailure { client().completeJson("hello") }
        assertTrue(arrayRoot.validationErrors.single().contains("must be an object but was array"))

        enqueueAll(groqContent("\"just a string\""), groqContent("\"x\""), groqContent("\"x\""))
        val stringRoot = expectFailure { client().completeJson("hello") }
        assertTrue(stringRoot.validationErrors.single().contains("but was string"))

        enqueueAll(groqContent("42"), groqContent("42"), groqContent("42"))
        val numberRoot = expectFailure { client().completeJson("hello") }
        assertTrue(numberRoot.validationErrors.single().contains("but was number"))
    }

    // ------------------------------------------------------------------ schema validation

    @Test
    fun missingRequiredFieldViolatesSchemaAndListsTheField() = runBlocking {
        enqueueAll(
            groqContent("{\"observation\":\"the home screen\"}"),
            groqContent("{\"observation\":\"the home screen\"}"),
            groqContent("{\"observation\":\"the home screen\"}"),
        )
        val error = expectFailure { client().completeJson("plan this", ModelSchemas.PLANNER_PLAN) }
        assertEquals(ModelFailureKind.SCHEMA_VIOLATION, error.kind)
        assertTrue(error.validationErrors.any { it.contains("missing required field 'steps'") })
        assertEquals(3, server.requestCount)
    }

    @Test
    fun wrongTypedFieldViolatesSchemaPrecisely() = runBlocking {
        val badAudit = "{\"listing_name\":\"Red Dress\",\"image_description\":\"A red dress on hanger\"," +
            "\"mismatch\":false,\"issue\":\"\",\"confidence\":\"high\"}"
        enqueueAll(groqContent(badAudit), groqContent(badAudit), groqContent(badAudit))
        val error = expectFailure { client().completeJson("audit", ModelSchemas.VISUAL_AUDIT) }
        assertEquals(ModelFailureKind.SCHEMA_VIOLATION, error.kind)
        assertTrue(error.validationErrors.single().contains("'confidence' must be NUMBER but was STRING"))
    }

    // ------------------------------------------------------------------ bounded schema repair

    @Test
    fun malformedThenValidRepairsWithinBudgetAndRecordsOneSchemaRepairRow() = runBlocking {
        enqueueAll(
            groqContent("this is not json at all"),
            groqContent("{\"broadcast_message\":\"Good morning\",\"tiktok_caption\":\"New stock\",\"featured_product\":\"Kitenge\"}"),
        )
        val result = client().completeJson("write the broadcast", ModelSchemas.BROADCAST_PLAN)
        assertEquals("Good morning", result.optString("broadcast_message"))
        assertEquals(2, server.requestCount)

        val firstRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
        val repairRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/v1/chat/completions", repairRequest.path)
        // The gateway hashes the provider RESPONSE body it rejected, not our request.
        val rejectedEnvelope = """{"choices":[{"message":{"content":${JSONObject.quote("this is not json at all")}},"finish_reason":"stop"}]}"""
        val rejectedHash = ModelGateway.sha256Hex(rejectedEnvelope)
        val repairBody = repairRequest.body.readUtf8()
        assertTrue(repairBody.contains("Validation errors"))
        assertTrue(repairBody.contains("sha-256 hash reference: $rejectedHash"))

        val rows = brainRows()
        assertEquals(1, rows.size)
        assertEquals("SCHEMA_REPAIR:MALFORMED_ASSISTANT_JSON", rows.single().correctiveAction)
        assertEquals("REPAIR_REQUESTED", rows.single().disposition)
    }

    @Test
    fun visionMalformedThenValidAlsoRepairsWithinBudget() = runBlocking {
        val screenshot = File.createTempFile("shot", ".png", context.cacheDir).apply {
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        }
        screenshot.deleteOnExit()
        enqueueAll(
            groqContent("{truncated vision json"),
            groqContent(
                "{\"listing_name\":\"Red Dress\",\"image_description\":\"A red dress on a hanger\"," +
                    "\"mismatch\":false,\"issue\":\"\",\"confidence\":0.9}",
            ),
        )
        val result = client().completeVisionJson("inspect listing", screenshot, ModelSchemas.VISUAL_AUDIT)
        assertEquals(0.9, result.optDouble("confidence"), 0.0001)
        assertEquals(2, server.requestCount)
        val row = brainRows().single()
        assertTrue(row.correctiveAction.contains("SCHEMA_REPAIR"))
        assertEquals("test-vision-model", row.model)
    }

    @Test
    fun repairNeverDispatchesAnythingExceptModelHttpCalls() = runBlocking {
        enqueueAll(groqContent("garbage one"), groqContent("garbage two"), groqContent("{\"steps\":[]}"))
        client().completeJson("plan", ModelSchemas.RECOVERY_PLAN)
        assertEquals(3, server.requestCount)
        for (index in 0 until 3) {
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("only the model endpoint may be hit during repair", "/v1/chat/completions", request.path)
        }
    }

    // ------------------------------------------------------------------ replayed responses

    @Test
    fun duplicateReplayedMalformedResponsesAreClassifiedBoundedAndRecorded() = runBlocking {
        val poison = "{\"plan\":\"stale echo\""
        enqueueAll(groqContent(poison), groqContent(poison), groqContent(poison))
        val error = expectFailure { client().completeJson("plan this", ModelSchemas.PLANNER_PLAN) }
        assertEquals(ModelFailureKind.MALFORMED_ASSISTANT_JSON, error.kind)
        assertEquals(3, server.requestCount)
        assertEquals(3, error.attemptCount)

        val rows = brainRows().sortedBy { it.attemptCount }
        assertEquals(3, rows.size)
        // First rejection is genuine; byte-identical rejections are marked REPLAYED.
        assertFalse(rows[0].correctiveAction.startsWith("REPLAYED_"))
        assertTrue(rows[1].correctiveAction.startsWith("REPLAYED_"))
        assertTrue(rows[2].correctiveAction.startsWith("REPLAYED_"))
        assertTrue(rows.all { it.correctiveAction.endsWith("MALFORMED_ASSISTANT_JSON") })
        // Task/stage/model correlation is preserved across every attempt.
        assertTrue(rows.all { it.stage == ModelSchemas.PLANNER_PLAN.name })
        assertTrue(rows.all { it.model == config.groqModel })
        assertEquals(
            ModelGateway.sha256Hex(
                """{"choices":[{"message":{"content":${JSONObject.quote(poison)}},"finish_reason":"stop"}]}""",
            ),
            rows[1].responseHash,
        )
    }

    @Test
    fun distinctMalformedResponsesAreNotFalselyMarkedAsReplays() = runBlocking {
        enqueueAll(
            groqContent("first broken"),
            groqContent("second differently broken"),
            groqContent("{\"steps\":[]}"),
        )
        val result = client().completeJson("plan this", ModelSchemas.PLANNER_PLAN)
        assertEquals(0, result.optJSONArray("steps")!!.length())
        val rows = brainRows()
        assertTrue(rows.none { it.correctiveAction.startsWith("REPLAYED_") })
    }

    @Test
    fun exhaustedRetryBudgetProducesTerminalTypedReceiptAndNoFurtherWireCalls() = runBlocking {
        enqueueAll(groqContent("{broken"), groqContent("{broken too"), groqContent("{still broken"))
        val error = expectFailure { client().completeJson("plan this") }
        assertEquals(3, error.attemptCount)
        assertEquals(ModelFailureKind.MALFORMED_ASSISTANT_JSON, error.kind)
        assertEquals(3, server.requestCount)
        val terminal = brainRows().single { it.disposition == FailureDispositions.RETRY_EXHAUSTED }
        assertTrue(terminal.retryable)
        assertTrue(terminal.validationErrorsJson.isNotEmpty())
    }

    // ------------------------------------------------------------------ envelope + status classification

    @Test
    fun malformedEnvelopeMissingChoicesIsRetryable() = runBlocking {
        repeat(3) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"error\":{\"message\":\"shape wrong\"}}"))
        }
        val error = expectFailure { client().completeJson("hello") }
        assertEquals(ModelFailureKind.MALFORMED_ENVELOPE, error.kind)
        assertEquals(3, server.requestCount)
        assertTrue(brainRows().any { it.disposition == "RETRY_EXHAUSTED" })
    }

    @Test
    fun http400GetsExactlyOneAttemptAndPermanentDisposition() = runBlocking {
        statusResponses(400, 3)
        val error = expectFailure { client().completeJson("hello") }
        assertEquals(ModelFailureKind.PERMANENT_CLIENT, error.kind)
        assertEquals(1, server.requestCount)
        val row = brainRows().single()
        assertEquals("FAILED_PERMANENT", row.disposition)
        assertEquals(1, row.attemptCount)
        assertFalse(row.retryable)
    }

    @Test
    fun http401And403EachGetExactlyOneAttempt() = runBlocking {
        statusResponses(401, 1)
        val unauthorized = expectFailure { client().completeJson("hello") }
        assertEquals(ModelFailureKind.PERMANENT_CLIENT, unauthorized.kind)
        assertEquals(1, server.requestCount)

        statusResponses(403, 1)
        val forbidden = expectFailure { client().completeJson("hello") }
        assertEquals(ModelFailureKind.PERMANENT_CLIENT, forbidden.kind)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun policyRejectedNeverRetriesEvenAtGatewayLevel() = runBlocking {
        val gateway = ModelGateway(memory = null, sleeper = {}, clock = { fakeNow })
        var invocations = 0
        val error = expectFailure {
            gateway.execute<Unit>("probe", "model-x", allowRepair = true) { _ ->
                invocations += 1
                ModelGateway.AttemptResult.Failure(ModelResponseException("policy refusal", ModelFailureKind.POLICY_REJECTED))
            }
        }
        assertEquals(ModelFailureKind.POLICY_REJECTED, error.kind)
        assertEquals(1, invocations)
    }

    @Test
    fun transportFailuresAreClassifiedAndRetriedWithinBudget() = runBlocking {
        server.shutdown()
        val error = expectFailure { client().completeJson("hello") }
        assertEquals(ModelFailureKind.TRANSPORT, error.kind)
        assertEquals(2, sleeps.size)
        assertEquals(3, error.attemptCount)
    }

    // ------------------------------------------------------------------ timeouts, 408/429/500, Retry-After cap

    @Test
    fun readTimeoutIsClassifiedRetriedAndBounded() = runBlocking {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[],\"padding\":\"").setBodyDelay(1500, TimeUnit.MILLISECONDS),
        )
        enqueueAll(groqContent("{\"recovered\":true}"))
        val result = GroqClient(
            config, memory, allowInsecureTestEndpoint = true,
            readTimeoutMs = 250L,
            sleeper = { sleeps.add(it) },
            clock = { fakeNow },
        ).completeJson("timeout probe")
        assertEquals(true, result.optBoolean("recovered"))
        assertEquals(2, server.requestCount)
        val timeoutRow = brainRows().single()
        assertTrue(timeoutRow.correctiveAction.contains(ModelFailureKind.TIMEOUT.name))
    }

    @Test
    fun rateLimitedRetryAfterIsCappedAtSixtySeconds() = runBlocking {
        repeat(2) {
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "999999").setBody("{}"))
        }
        enqueueAll(groqContent("{\"send\":true,\"message\":\"on its way\"}"))
        val result = client().completeJson("follow up?", ModelSchemas.FOLLOW_UP_DECISION)
        assertEquals(true, result.optBoolean("send"))
        assertEquals(3, server.requestCount)
        assertEquals(listOf(60_000L, 60_000L), sleeps)
        assertTrue(sleeps.all { it <= 60_000L })
    }

    @Test
    fun http408RetriesAreBoundedToThreeAttempts() = runBlocking {
        // A dispatcher (not a fixed queue) because OkHttp may transparently retry
        // 408s internally per external attempt; the dispatcher can never starve.
        server.dispatcher = fixedDispatcher(408)
        val error = expectFailure { client().completeJson("hello") }
        assertEquals(ModelFailureKind.SERVER_RETRYABLE, error.kind)
        assertTrue("expected at least the three external attempts", server.requestCount >= 3)
        assertEquals(2, sleeps.size)
        assertTrue(brainRows().any { it.disposition == "RETRY_EXHAUSTED" })
    }

    @Test
    fun http500BackoffIsExponentialBase800WithJitterUnder300() = runBlocking {
        statusResponses(500, 3)
        val error = expectFailure { client().completeJson("hello") }
        assertEquals(ModelFailureKind.SERVER_RETRYABLE, error.kind)
        assertEquals(3, error.attemptCount)
        assertEquals(2, sleeps.size)
        assertTrue("first backoff ${sleeps[0]}", sleeps[0] in 800..1100)
        assertTrue("second backoff ${sleeps[1]}", sleeps[1] in 1600..1900)
    }

    // ------------------------------------------------------------------ durable records fidelity

    @Test
    fun failureRecordsCarryRequestIdAndSha256ResponseHash() = runBlocking {
        val rawBody = """{"error":{"message":"boom"}}"""
        server.enqueue(
            MockResponse().setResponseCode(500).setHeader("X-Request-Id", "req-42")
                .setHeader("Content-Type", "application/json").setBody(rawBody),
        )
        statusResponses(500, 2)
        val error = expectFailure { client().completeJson("hello") }
        assertEquals(3, error.attemptCount)
        // The X-Request-Id header arrived on ATTEMPT 1 only; that attempt's row carries it.
        val firstAttemptRow = brainRows().first { it.attemptCount == 1 }
        assertEquals("req-42", firstAttemptRow.requestId)
        assertEquals(ModelGateway.sha256Hex(rawBody), firstAttemptRow.responseHash)
        val terminalRow = brainRows().first { it.disposition == "RETRY_EXHAUSTED" }
        assertEquals(3, terminalRow.attemptCount)
        assertTrue(terminalRow.retryable)
        assertEquals(3, brainRows().size)
        assertTrue(brainRows().any { it.disposition == "RETRY_SCHEDULED" && it.attemptCount == 1 })
    }

    // ------------------------------------------------------------------ secrets never leak

    @Test
    fun secretsNeverReachRepairRequestsOrDurableRecordsOrExcerpts() = runBlocking {
        val pinLikeSecret = "550001118222"
        val otpLikeSecret = "773344556677"
        val poisoned = "{broken json \"note\":\"pin is $pinLikeSecret\" and otp is $otpLikeSecret}"
        enqueueAll(
            groqContent(poisoned),
            groqContent("{\"broadcast_message\":\"Morning Kampala\",\"tiktok_caption\":\"New kitenge in\",\"featured_product\":\"Gomesi\"}"),
        )
        val result = client().completeJson("broadcast", ModelSchemas.BROADCAST_PLAN)
        assertEquals("Morning Kampala", result.optString("broadcast_message"))

        server.takeRequest(5, TimeUnit.SECONDS)!!
        val repairRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
        val repairBody = repairRequest.body.readUtf8()
        assertTrue(repairBody.contains("sha-256 hash reference:"))
        assertFalse(repairBody.contains(pinLikeSecret))
        assertFalse(repairBody.contains(otpLikeSecret))

        val digitRuns = Regex("\\d{9,}")
        brainRows().forEach { row ->
            assertNull(digitRuns.find(row.validationErrorsJson)?.value)
            assertNull(digitRuns.find(row.correctiveAction)?.value)
        }

        val standalone = ModelGateway(memory = null, sleeper = {}, clock = { fakeNow })
        val excerpt = standalone.redactedExcerpt(poisoned)
        assertNotNull("redactable shapes must produce an excerpt", excerpt)
        assertTrue(excerpt!!.contains("[REDACTED:"))
        assertFalse(excerpt.contains(pinLikeSecret))
        assertFalse(excerpt.contains(otpLikeSecret))
        assertNull(digitRuns.find(excerpt)?.value)
        assertTrue(excerpt.length <= 200)
    }

    // ------------------------------------------------------------------ circuit breaker

    @Test
    fun circuitBreakerOpensAfterThresholdFailsFastAndHalfOpenRecovers() = runBlocking {
        server.dispatcher = fixedDispatcher(500)
        val groq = client()
        repeat(5) { index ->
            val error = expectFailure { groq.completeJson("call $index") }
            assertEquals(ModelFailureKind.SERVER_RETRYABLE, error.kind)
        }
        assertEquals(15, server.requestCount)

        val fastFail = expectFailure { groq.completeJson("while open") }
        assertEquals(ModelFailureKind.CIRCUIT_OPEN, fastFail.kind)
        assertEquals(15, server.requestCount)
        assertTrue(brainRows().any { it.disposition == "CIRCUIT_OPEN" })

        fakeNow += 61_000
        server.dispatcher = contentDispatcher("{\"healed\":true}")
        val healed = groq.completeJson("half open probe")
        assertEquals(true, healed.optBoolean("healed"))

        server.dispatcher = contentDispatcher("{\"second\":true}")
        assertEquals(true, groq.completeJson("after recovery").optBoolean("second"))
    }

    @Test
    fun halfOpenProbeFailureRestartsCooldownAndStaysFastFailing() = runBlocking {
        server.dispatcher = fixedDispatcher(500)
        val groq = client()
        repeat(5) { index -> expectFailure { groq.completeJson("call $index") } }
        fakeNow += 61_000
        expectFailure { groq.completeJson("failing probe") }
        val stillOpen = expectFailure { groq.completeJson("immediate retry") }
        assertEquals(ModelFailureKind.CIRCUIT_OPEN, stillOpen.kind)
        assertEquals(18, server.requestCount)

        fakeNow += 61_000
        server.dispatcher = contentDispatcher("{\"back\":true}")
        assertEquals(true, groq.completeJson("recovered probe").optBoolean("back"))
    }

    // ------------------------------------------------------------------ legacy compatibility

    @Test
    fun plainTextCompletionStillWorksAndJsonOnlyModeRejectsNonJson() = runBlocking {
        enqueueAll(groqContent("READY"))
        assertEquals("READY", client().complete("Return READY.", jsonOnly = false))

        enqueueAll(groqContent("no braces here"), groqContent("still none"), groqContent("nope"))
        val error = expectFailure { client().complete("give json", jsonOnly = true) }
        assertEquals(ModelFailureKind.MALFORMED_ASSISTANT_JSON, error.kind)
    }

    @Test
    fun deprecatedAliasRemainsASubclassOfTypedException() {
        val topLevel = MalformedModelResponse("legacy message", "raw output text", IllegalArgumentException("cause"))
        assertTrue(topLevel is ModelResponseException)
        assertEquals(ModelFailureKind.MALFORMED_ASSISTANT_JSON, topLevel.kind)

        val nested = GroqClient.MalformedModelResponse("nested", "raw", IllegalArgumentException("c"))
        assertTrue(nested is ModelResponseException)
        assertEquals("raw", nested.rawResponse)
    }

    // ------------------------------------------------------------------ correlation threading (contract §2/§3)

    @Test
    fun correlationIdThreadsIntoEveryDurableRowWhileProviderRequestIdStaysSeparate() = runBlocking {
        val rawBody = """{"error":{"message":"boom"}}"""
        server.enqueue(
            MockResponse().setResponseCode(500).setHeader("X-Request-Id", "req-42")
                .setHeader("Content-Type", "application/json").setBody(rawBody),
        )
        statusResponses(500, 2)
        val error = expectFailure { client().completeJson("hello", null, "task-77") }
        assertEquals(3, error.attemptCount)
        val rows = brainRows()
        assertEquals(3, rows.size)
        // The logical task id rides EVERY row…
        assertTrue(rows.all { it.correlationId == "task-77" })
        // …while the provider request id stays a separate per-attempt carrier.
        assertEquals("req-42", rows.first { it.attemptCount == 1 }.requestId)
        assertTrue(rows.filter { it.attemptCount > 1 }.all { it.requestId.isBlank() })
        assertNotEquals("the provider id must never replace the logical correlation id", "req-42", "task-77")
        assertTrue(rows.all { it.terminalOutcome.isBlank() && it.ownerExplanation.isBlank() })
    }

    @Test
    fun legacyCallsWithoutCorrelationStillPersistRowsWithEmptyId() = runBlocking {
        statusResponses(400, 1)
        expectFailure { client().completeJson("legacy path") }
        val row = brainRows().single()
        assertEquals("", row.correlationId)
        assertEquals("FAILED_PERMANENT", row.disposition)
    }

    @Test
    fun circuitOpenFastFailRowCarriesTheTaskCorrelationId() = runBlocking {
        server.dispatcher = fixedDispatcher(500)
        val groq = client()
        repeat(5) { index -> expectFailure { groq.completeJson("call $index", null, "task-open") } }
        val fastFail = expectFailure { groq.completeJson("while open", null, "task-next") }
        assertEquals(ModelFailureKind.CIRCUIT_OPEN, fastFail.kind)
        val openRow = brainRows().first { it.disposition == FailureDispositions.CIRCUIT_OPEN }
        assertEquals("task-next", openRow.correlationId)
    }

    @Test
    fun repairedSuccessFinalizesRecoveredOutcomeExactlyOncePerLogicalTask() = runBlocking {
        enqueueAll(
            groqContent("{broken json"),
            groqContent("{\"broadcast_message\":\"Good morning\",\"tiktok_caption\":\"New stock\",\"featured_product\":\"Kitenge\"}"),
        )
        val result = client().completeJson("write broadcast", ModelSchemas.BROADCAST_PLAN, "task-recovered")
        assertEquals("Good morning", result.optString("broadcast_message"))

        // Repair row exists and is open until the owning task finalizes it.
        val repairRow = brainRows().single()
        assertEquals("SCHEMA_REPAIR:MALFORMED_ASSISTANT_JSON", repairRow.correctiveAction)
        assertTrue(repairRow.terminalOutcome.isBlank())

        assertTrue(BrainFailureFinalizer.markRecovered(memory, "task-recovered", ModelSchemas.BROADCAST_PLAN.name))
        val finalized = memory.brainFailuresByCorrelation("task-recovered").single()
        assertEquals(TerminalOutcomes.RECOVERED_SCHEMA_VALID, finalized.terminalOutcome)
        assertTrue(finalized.ownerExplanation.contains("repaired"))
        assertTrue(finalized.ownerExplanation.contains("validated"))
        assertFalse(finalized.ownerExplanation.contains("broken"))

        // A second finalize attempt must not double-write (row no longer open).
        assertFalse(BrainFailureFinalizer.markRecovered(memory, "task-recovered", ModelSchemas.BROADCAST_PLAN.name))
    }

    @Test
    fun cleanFirstAttemptSuccessFinalizesNothing() = runBlocking {
        enqueueAll(groqContent("{\"steps\":[]}"))
        client().completeJson("plan this", ModelSchemas.PLANNER_PLAN, "task-clean")
        assertTrue(brainRows().isEmpty())
        assertFalse(BrainFailureFinalizer.markRecovered(memory, "task-clean", ModelSchemas.PLANNER_PLAN.name))
    }

    @Test
    fun exhaustedRepairFinalizesNoSideEffectWithARedactedOwnerSentence() = runBlocking {
        val poison = "{broken \"pin\":\"550001118222\"}"
        enqueueAll(groqContent(poison), groqContent(poison), groqContent(poison))
        val error = expectFailure {
            client().completeJson(
                "caption for product", ModelSchema(
                    name = "caption_probe",
                    requiredFields = linkedMapOf("caption" to FieldType.STRING),
                ),
                correlationId = "task-caption",
            )
        }
        assertEquals(3, error.attemptCount)
        val finalized = BrainFailureFinalizer.finalizeFailed(
            memory, "task-caption", "caption_probe", error.kind, "studio caption probe",
        )
        assertTrue(finalized)
        // brainFailuresByCorrelation returns rows newest-first; finalize stamps
        // the newest open row, so the finalized attempt is the FIRST row.
        val row = memory.brainFailuresByCorrelation("task-caption").first { it.stage == "caption_probe" }
        assertEquals(TerminalOutcomes.TASK_FAILED_NO_SIDE_EFFECT, row.terminalOutcome)
        assertTrue(row.ownerExplanation.isNotBlank())
        assertFalse(row.ownerExplanation.contains(poison))
        assertFalse(row.ownerExplanation.contains("550001118222"))
        assertFalse(row.ownerExplanation.contains("{"))
        // Every intermediate attempt row stayed open-free of terminal text except the finalized one.
        assertTrue(memory.brainFailuresByCorrelation("task-caption").count { it.terminalOutcome == TerminalOutcomes.TASK_FAILED_NO_SIDE_EFFECT } >= 1)
    }

    @Test
    fun permanentFailuresFinalizeAsAwaitingOwnerRetry() = runBlocking {
        statusResponses(401, 1)
        val error = expectFailure { client().completeJson("vision config", null, "task-permanent") }
        assertEquals(ModelFailureKind.PERMANENT_CLIENT, error.kind)
        assertTrue(BrainFailureFinalizer.finalizeFailed(memory, "task-permanent", GroqClient.STAGE_CHAT_JSON, error.kind, "probe"))
        val row = memory.brainFailuresByCorrelation("task-permanent").single()
        assertEquals(TerminalOutcomes.AWAITING_OWNER_RETRY, row.terminalOutcome)
        assertTrue(row.ownerExplanation.contains("retry"))
    }

    @Test
    fun ownerExplanationRedactsEmbeddedSecretShapesBeforePersistingAndRefusesBlankCorrelation() = runBlocking {
        statusResponses(400, 1)
        expectFailure { client().completeJson("x", null, "task-secret-guard") }
        // A secret-shaped fragment inside the owner sentence is redacted, never persisted.
        val ok = BrainFailureFinalizer.finalizeTaskOutcome(
            memory, "task-secret-guard", GroqClient.STAGE_CHAT_JSON,
            TerminalOutcomes.TASK_FAILED_NO_SIDE_EFFECT,
            "The attempt failed because terminal pin is 1234567 was rejected by validation.",
        )
        assertTrue(ok)
        val explanation = memory.brainFailuresByCorrelation("task-secret-guard").single().ownerExplanation
        assertFalse(explanation.contains("1234567"))
        // A blank correlation id can never finalize anything.
        statusResponses(400, 1)
        expectFailure { client().completeJson("x", null) }
        val blank = BrainFailureFinalizer.finalizeTaskOutcome(
            memory, "", GroqClient.STAGE_CHAT_JSON, TerminalOutcomes.TASK_FAILED_NO_SIDE_EFFECT, "irrelevant",
        )
        assertFalse(blank)
    }
}
