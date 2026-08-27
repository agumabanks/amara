package co.sanaa.agent.modules

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.api.BrainFailureFinalizer
import co.sanaa.agent.api.GroqClient
import co.sanaa.agent.api.ModelResponseException
import co.sanaa.agent.api.ModelSchemas
import co.sanaa.agent.core.AmaraMemory
import co.sanaa.agent.core.CapabilityIds
import co.sanaa.agent.core.ContentHashing
import co.sanaa.agent.core.SecureConfig
import co.sanaa.agent.core.SideEffectLedger
import co.sanaa.agent.core.SideEffectOutcome
import co.sanaa.agent.core.SideEffectRunner
import co.sanaa.agent.core.SideEffectState
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ONE combined production-path proof (campaign task C): malformed model reply →
 * bounded repair → schema-valid reply → EXACTLY ONE transaction claim → EXACTLY ONE
 * downstream action → EXACTLY ONE verified final receipt; plus replay and
 * exhausted-repair variants producing ZERO side effects. Runs the real gateway,
 * real SQLite ledger/memory, and real OkHttp wire (MockWebServer), mirroring the
 * exact SokoStudioSharingModule sequence without device accessibility.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProductionPathCorrelationTest {

    private lateinit var context: Context
    private lateinit var config: SecureConfig
    private lateinit var memory: AmaraMemory
    private lateinit var server: MockWebServer
    private val actionsPerformed = mutableListOf<String>()

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
        actionsPerformed.clear()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun groq(): GroqClient = GroqClient(config, memory, allowInsecureTestEndpoint = true, sleeper = {})

    private fun groqContent(content: String): MockResponse {
        val quoted = JSONObject.quote(content)
        return MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":$quoted},"finish_reason":"stop"}]}""")
    }

    private fun runner() = SideEffectRunner(SideEffectLedger.from(memory))

    /** The exact correlation + idempotency derivation SokoStudioSharingModule performs. */
    private fun correlationIdFor(target: String, product: String) =
        "${SokoStudioSharingModule.MODULE_TAG}${ContentHashing.hash("$target|$product|UGX 45,000").take(24)}"

    private suspend fun shareCaptionTransaction(
        runnerSideEffects: SideEffectRunner,
        key: String,
        caption: String,
    ): SideEffectOutcome = runnerSideEffects.execute(
        capabilityId = CapabilityIds.SHARE_SOKO_STUDIO_CAPTION,
        idempotencyKey = key,
        target = "Buyer Seven",
        content = caption,
        act = {
            actionsPerformed.add("send:$key")
            true
        },
        verify = {
            co.sanaa.agent.core.VerificationEvidence(
                verified = true, confidence = 1.0, observedPackage = "com.whatsapp",
                deliveryState = "Sent", evidenceTimestamp = System.currentTimeMillis(),
            )
        },
    )

    private fun transactionRows(key: String): List<Pair<String, String>> {
        val cursor = memory.readableDatabase.rawQuery(
            "SELECT state, evidence FROM side_effect_transactions WHERE idempotency_key = ? ORDER BY updated_at",
            arrayOf(key),
        )
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) add(c.getString(0) to c.getString(1))
            }
        }
    }

    @Test
    fun malformedThenRepairedReplyProducesExactlyOneClaimOneActionOneVerifiedReceipt() = runBlocking {
        val correlationId = correlationIdFor("Buyer Seven", "Ankara Dress")
        // Stage 1 — model stage with one malformed reply repaired inside the budget.
        server.enqueue(groqContent("this is not json at all"))
        server.enqueue(groqContent("{\"caption\":\"Warm Ankara dress for you, 45,000 UGX. Reply to order.\"}"))
        val polished = groq().completeJson(
            "Polish this Studio ad", ModelSchemas.STUDIO_CAPTION, correlationId,
        ).optString("caption")
        assertTrue(polished.contains("Ankara"))
        assertEquals(2, server.requestCount)

        // Exactly one repair row exists, open, carrying the logical correlation id.
        val repairRow = memory.recentBrainFailures(10).single()
        assertEquals("SCHEMA_REPAIR:MALFORMED_ASSISTANT_JSON", repairRow.correctiveAction)
        assertEquals(correlationId, repairRow.correlationId)
        assertTrue(repairRow.terminalOutcome.isBlank())

        // Recovery info is persisted exactly once via the §2 finalize API.
        assertTrue(BrainFailureFinalizer.markRecovered(memory, correlationId, ModelSchemas.STUDIO_CAPTION.name))
        assertEquals(
            TerminalOutcomeQuery.recoveredCount(memory, correlationId), 1,
        )

        // Stage 2 — EXACTLY ONE transaction claim, ONE downstream action, ONE receipt.
        val key = "studio-caption:Buyer Seven:${ContentHashing.hash(polished)}"
        val outcome = shareCaptionTransaction(runner(), key, polished)
        assertTrue(outcome is SideEffectOutcome.Verified)
        assertEquals(listOf("send:$key"), actionsPerformed)

        val rows = transactionRows(key)
        assertEquals("exactly one durable transaction must exist", 1, rows.size)
        assertEquals(SideEffectState.VERIFIED.name, rows.single().first)
        assertTrue(rows.single().second.contains("verified=true"))

        // Stage 3 — replay of the same share produces ZERO additional side effects.
        val replay = shareCaptionTransaction(runner(), key, polished)
        assertTrue(replay is SideEffectOutcome.DuplicateBlocked)
        assertEquals("no second action after a verified receipt", listOf("send:$key"), actionsPerformed)
        assertEquals(1, transactionRows(key).size)
        assertEquals("the model is not re-consulted on replay either", 2, server.requestCount)

        // The finalized chain stays queryable by correlation id.
        val chain = memory.brainFailuresByCorrelation(correlationId)
        assertEquals(1, chain.size)
        assertEquals(TerminalOutcomesConst.RECOVERED_SCHEMA_VALID, chain.single().terminalOutcome)
        assertFalse(chain.single().ownerExplanation.contains("not json at all"))
    }

    @Test
    fun exhaustedRepairProducesZeroClaimsZeroActionsAndAFinalizedNoSideEffectChain() = runBlocking {
        val correlationId = correlationIdFor("Buyer Eight", "Bark Cloth")
        repeat(3) { server.enqueue(groqContent("{broken caption json")) }
        try {
            groq().completeJson("Polish this Studio ad", ModelSchemas.STUDIO_CAPTION, correlationId)
            fail("expected the exhausted model call to throw")
        } catch (expected: ModelResponseException) {
            assertEquals(3, expected.attemptCount)
        }
        assertEquals(3, server.requestCount)

        // ZERO transactions, ZERO downstream actions.
        val key = "studio-caption:Buyer Eight:${ContentHashing.hash("never produced")}"
        assertNull(SideEffectLedger.from(memory).find(key))
        assertTrue(transactionRowsLike("studio-caption:Buyer Eight").isEmpty())
        assertTrue(actionsPerformed.isEmpty())

        // The owning module finalizes ONCE with TASK_FAILED_NO_SIDE_EFFECT + redacted sentence.
        val error = ModelResponseException(
            "Assistant output was not a single valid JSON object",
            co.sanaa.agent.api.ModelFailureKind.MALFORMED_ASSISTANT_JSON,
        )
        assertTrue(BrainFailureFinalizer.finalizeFailed(memory, correlationId, ModelSchemas.STUDIO_CAPTION.name, error.kind, "Soko Studio caption"))
        val chain = memory.brainFailuresByCorrelation(correlationId)
        assertEquals(3, chain.size)
        assertTrue(chain.all { it.correlationId == correlationId })
        val finalizedRows = chain.filter { it.terminalOutcome.isNotBlank() }
        assertEquals(1, finalizedRows.size)
        assertEquals(TerminalOutcomesConst.TASK_FAILED_NO_SIDE_EFFECT, finalizedRows.single().terminalOutcome)
        assertTrue(finalizedRows.single().ownerExplanation.contains("no changes and no messages sent"))
        // No raw provider body fragments ever reach durable recovery info.
        assertFalse(finalizedRows.single().ownerExplanation.contains("broken caption json"))
    }

    @Test
    fun schemaViolationWithoutAnyValidReplyStillNeverClaimsTheTransaction() = runBlocking {
        val correlationId = correlationIdFor("Buyer Nine", "Kitenge Fabric")
        repeat(3) { server.enqueue(groqContent("{\"caption\":\"\"}")) }
        try {
            groq().completeJson("Polish this Studio ad", ModelSchemas.STUDIO_CAPTION, correlationId)
            fail("blank captions must violate the schema through all attempts")
        } catch (expected: ModelResponseException) {
            assertEquals(co.sanaa.agent.api.ModelFailureKind.SCHEMA_VIOLATION, expected.kind)
        }
        assertTrue(actionsPerformed.isEmpty())
        assertTrue(transactionRowsLike("studio-caption:Buyer Nine").isEmpty())
        assertTrue(
            memory.recentBrainFailures(10).all { it.correlationId == correlationId },
        )
    }

    private fun transactionRowsLike(prefix: String): List<String> {
        val cursor = memory.readableDatabase.rawQuery(
            "SELECT idempotency_key FROM side_effect_transactions WHERE idempotency_key LIKE ?",
            arrayOf("$prefix%"),
        )
        return cursor.use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    }

    /** Small helper keeping terminal-outcome string literals out of assertions drift. */
    private object TerminalOutcomesConst {
        const val RECOVERED_SCHEMA_VALID = co.sanaa.agent.api.TerminalOutcomes.RECOVERED_SCHEMA_VALID
        const val TASK_FAILED_NO_SIDE_EFFECT = co.sanaa.agent.api.TerminalOutcomes.TASK_FAILED_NO_SIDE_EFFECT
    }

    private object TerminalOutcomeQuery {
        fun recoveredCount(memory: AmaraMemory, correlationId: String): Int =
            memory.brainFailuresByCorrelation(correlationId)
                .count { it.terminalOutcome == co.sanaa.agent.api.TerminalOutcomes.RECOVERED_SCHEMA_VALID }
    }
}
