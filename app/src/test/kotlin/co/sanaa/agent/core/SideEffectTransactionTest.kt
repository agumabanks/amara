package co.sanaa.agent.core

import co.sanaa.agent.actions.MessageNodeFacts
import co.sanaa.agent.actions.PublicationSurfaceRule
import co.sanaa.agent.actions.SendObservation
import co.sanaa.agent.actions.SendVerificationLogic
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adversarial transaction tests: catalog enforcement, legal transitions, duplicates,
 * uncertainty, crash semantics, and key/content binding. All run against a fake ledger
 * that mirrors the production transition rules; production SQLite behavior is covered
 * separately by AmaraMemoryTest (Robolectric).
 */
class SideEffectTransactionTest {

    @Test fun missingBubbleAndPendingTickNeverProveNoEffectOrDelivery() {
        val base=SendObservation("com.whatsapp","com.whatsapp",true,false,false,null,1L)
        val absent=SendVerificationLogic.evaluate("reply",base)
        assertFalse(absent.verified)
        assertFalse(absent.blocker.orEmpty().contains(SendVerificationLogic.NO_EFFECT_PROVEN))
        for(status in listOf("pending","failed","sending"))
            assertFalse(SendVerificationLogic.evaluate("reply",base.copy(contentVisibleOutsideDraft=true,deliveryState=status)).verified)
        assertTrue(SendVerificationLogic.evaluate("reply",base.copy(contentVisibleOutsideDraft=true,deliveryState="Delivered")).verified)
    }

    @Test fun cancellationAfterDispatchRemainsUncertainAndPropagates(): Unit = runBlocking {
        val ledger=FakeLedger()
        try {
            SideEffectRunner(ledger).execute("send_whatsapp", "cancel-proof", "A", "msg", initiator=Initiator.OWNER_CHAT,
                act={ throw kotlinx.coroutines.CancellationException("deadline") },
                verify={ error("Must not verify after cancellation") })
            throw AssertionError("Cancellation was swallowed")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertEquals(SideEffectState.UNCERTAIN, ledger.find("cancel-proof")?.state)
        }
    }

    private class FakeLedger : SideEffectLedger {
        val store = java.util.concurrent.ConcurrentHashMap<String, SideEffectTransaction>()
        val rejectedTransitions = mutableListOf<Triple<String, SideEffectState, String>>()
        override fun find(idempotencyKey: String): SideEffectTransaction? = store[idempotencyKey]
        override fun upsert(transaction: SideEffectTransaction): Boolean {
            if (store.containsKey(transaction.idempotencyKey)) return false
            store[transaction.idempotencyKey] = transaction
            return true
        }
        override fun transition(idempotencyKey: String, newState: SideEffectState, evidence: String): Boolean {
            val existing = store[idempotencyKey] ?: return false
            if (!SideEffectState.canTransition(existing.state, newState)) {
                rejectedTransitions += Triple(idempotencyKey, newState, evidence)
                return false
            }
            store[idempotencyKey] = existing.copy(state = newState, updatedAt = System.currentTimeMillis(), evidence = evidence)
            return true
        }
    }

    private class Flaggable(var flag: Boolean = false)

    private fun evidence(verified: Boolean, pkg: String = "com.whatsapp", state: String? = "Delivered", blocker: String? = null) =
        VerificationEvidence(verified, if (verified) 0.95 else 0.0, pkg, state, System.currentTimeMillis(), blocker)

    // ---------- catalog + validation enforcement ----------

    @Test fun unknownCapabilityIsRejectedWithoutActing(): Unit = runBlocking {
        val ledger = FakeLedger()
        val acted = Flaggable()
        val outcome = SideEffectRunner(ledger).execute(
            "teleport_owner", "k-unknown", "A", "msg",
            act = { acted.flag = true; true },
            initiator = Initiator.OWNER_CHAT,
            verify = { evidence(true) },
        )
        assertTrue(outcome is SideEffectOutcome.Rejected)
        assertFalse(acted.flag)
        assertTrue(ledger.store.isEmpty())
    }

    @Test fun readOnlyCapabilityCannotEnterTheTransaction(): Unit = runBlocking {
        val ledger = FakeLedger()
        val outcome = SideEffectRunner(ledger).execute(
            "read_screen", "k-readonly", "", "",
            act = { true }, verify = { evidence(true) },
            initiator = Initiator.OWNER_CHAT,
        )
        assertTrue(outcome is SideEffectOutcome.Rejected)
    }

    @Test fun disallowedInitiatorIsRejected(): Unit = runBlocking {
        val ledger = FakeLedger()
        val acted = Flaggable()
        val outcome = SideEffectRunner(ledger).execute(
            "apply_soko_edit", "k-init", "Listing", "new value",
            initiator = Initiator.PROACTIVE_AUDIT,
            inputs = mapOf("product" to "Listing", "field" to "name", "value" to "new value"),
            approvalId = 1L,
            act = { acted.flag = true; true },
            verify = { evidence(true) },
        )
        assertTrue(outcome is SideEffectOutcome.Rejected)
        assertFalse(acted.flag)
    }

    @Test fun blankTargetRejectedForTargetBoundCapabilities(): Unit = runBlocking {
        val ledger = FakeLedger()
        val outcome = SideEffectRunner(ledger).execute(
            "send_whatsapp", "k-target", "", "hello",
            act = { true }, verify = { evidence(true) },
            initiator = Initiator.OWNER_CHAT,
        )
        assertTrue(outcome is SideEffectOutcome.Rejected)
    }

    @Test fun freshExactCapabilityWithoutApprovalBindingIsRejected(): Unit = runBlocking {
        val ledger = FakeLedger()
        val acted = Flaggable()
        val outcome = SideEffectRunner(ledger).execute(
            "apply_soko_edit", "k-noapproval", "Listing", "value",
            approvalId = null,
            act = { acted.flag = true; true },
            initiator = Initiator.OWNER_CHAT,
            verify = { evidence(true) },
        )
        assertTrue(outcome is SideEffectOutcome.Rejected)
        assertFalse(acted.flag)
    }

    @Test fun keyReuseAcrossCapabilitiesIsRejected(): Unit = runBlocking {
        val ledger = FakeLedger()
        ledger.upsert(SideEffectTransaction("k-shared", "send_whatsapp", "A", ContentHashing.hash("m"), null, SideEffectState.PROPOSED, 0, 0, ""))
        val outcome = SideEffectRunner(ledger).execute(
            "post_whatsapp_status", "k-shared", "status", "m",
            act = { true }, verify = { evidence(true) },
            initiator = Initiator.OWNER_CHAT,
        )
        assertTrue(outcome is SideEffectOutcome.Rejected)
    }

    @Test fun contentChangeUnderReusedKeyIsRejected(): Unit = runBlocking {
        val ledger = FakeLedger()
        ledger.upsert(SideEffectTransaction("k-content", "send_whatsapp", "A", ContentHashing.hash("first message"), null, SideEffectState.PROPOSED, 0, 0, ""))
        val acted = Flaggable()
        val outcome = SideEffectRunner(ledger).execute(
            "send_whatsapp", "k-content", "A", "different message entirely",
            act = { acted.flag = true; true },
            initiator = Initiator.OWNER_CHAT,
            verify = { evidence(true) },
        )
        assertTrue(outcome is SideEffectOutcome.Rejected)
        assertFalse(acted.flag)
    }

    @Test fun duplicateRequestsWithDifferentKeysBothExecute(): Unit = runBlocking {
        val ledger = FakeLedger()
        val runner = SideEffectRunner(ledger)
        val first = runner.execute("send_whatsapp", "k-a1", "A", "msg", initiator = Initiator.OWNER_CHAT, act = { true }, verify = { evidence(true) })
        val second = runner.execute("send_whatsapp", "k-a2", "A", "msg", initiator = Initiator.OWNER_CHAT, act = { true }, verify = { evidence(true) })
        assertTrue(first is SideEffectOutcome.Verified)
        // Different keys are different owner intents; deduplication is by key, not content.
        assertTrue(second is SideEffectOutcome.Verified)
    }

    // ---------- preflight + approval lifecycle ----------

    @Test fun preflightAbortFailsBeforeAnyExternalDispatch(): Unit = runBlocking {
        val ledger = FakeLedger()
        val acted = Flaggable()
        val outcome = SideEffectRunner(ledger).execute(
            "send_whatsapp", "k-preflight", "A", "msg",
            preflight = { "WhatsApp is not reachable" },
            act = { acted.flag = true; true },
            initiator = Initiator.OWNER_CHAT,
            verify = { evidence(true) },
        )
        assertTrue(outcome is SideEffectOutcome.Failed)
        assertFalse(acted.flag)
        assertEquals(SideEffectState.CANCELLED, ledger.store.getValue("k-preflight").state)
    }

    @Test fun approvalExpiryBetweenClaimAndActCancels(): Unit = runBlocking {
        val ledger = FakeLedger()
        // Transaction already APPROVED, then the approval expires before this attempt acts.
        ledger.upsert(SideEffectTransaction("k-expiry", "apply_soko_edit", "Listing", ContentHashing.hash("value"), 42L, SideEffectState.APPROVED, 0, 0, ""))
        val acted = Flaggable()
        var validated = false
        val outcome = SideEffectRunner(ledger).execute(
            "apply_soko_edit", "k-expiry", "Listing", "value",
            initiator = Initiator.OWNER_CHAT,
            inputs = mapOf("product" to "Listing", "field" to "name", "value" to "value"),
            approvalId = 42L,
            approvalValidator = { validated = true; false },
            act = { acted.flag = true; true },
            verify = { evidence(true) },
        )
        assertTrue(validated)
        assertTrue(outcome is SideEffectOutcome.Failed)
        assertFalse(acted.flag)
        assertEquals(SideEffectState.CANCELLED, ledger.store.getValue("k-expiry").state)
    }

    @Test fun approvalInvalidBeforeActCancelsWithoutActing(): Unit = runBlocking {
        val ledger = FakeLedger()
        val acted = Flaggable()
        val outcome = SideEffectRunner(ledger).execute(
            "apply_soko_edit", "k-invalid", "Listing", "value",
            inputs = mapOf("product" to "Listing", "field" to "name", "value" to "value"),
            approvalId = 7L,
            approvalValidator = { false },
            act = { acted.flag = true; true },
            initiator = Initiator.OWNER_CHAT,
            verify = { evidence(true) },
        )
        assertTrue(outcome is SideEffectOutcome.Failed)
        assertFalse(acted.flag)
        assertEquals(SideEffectState.CANCELLED, ledger.store.getValue("k-invalid").state)
    }

    // ---------- core state machine ----------

    @Test fun verifiedPathPersistsClaimBeforeActingAndFinalizesVerified(): Unit = runBlocking {
        val ledger = FakeLedger()
        val runner = SideEffectRunner(ledger)
        var verifyRan = false
        val outcome = runner.execute(
            "send_whatsapp", "k1", "Sanaa Office", "Hello team",
            act = { ledger.find("k1")!!.state == SideEffectState.ACTING },
            initiator = Initiator.OWNER_CHAT,
            verify = { verifyRan = true; evidence(true) },
        )
        assertTrue(outcome is SideEffectOutcome.Verified)
        assertTrue(verifyRan)
        assertEquals(SideEffectState.VERIFIED, ledger.store.getValue("k1").state)
    }

    @Test fun duplicateVerifiedKeyIsBlockedWithoutActing(): Unit = runBlocking {
        val ledger = FakeLedger()
        val runner = SideEffectRunner(ledger)
        runner.execute("send_whatsapp", "k2", "A", "msg", initiator = Initiator.OWNER_CHAT, act = { true }, verify = { evidence(true) })
        val acted = Flaggable()
        val second = runner.execute("send_whatsapp", "k2", "A", "msg",
            act = { acted.flag = true; true },
            initiator = Initiator.OWNER_CHAT,
            verify = { evidence(true) })
        assertTrue("second=$second", second is SideEffectOutcome.DuplicateBlocked)
        assertFalse("Act must never run for a duplicate", acted.flag)
    }

    @Test fun actingStateIsNeverRepeatedAutomatically(): Unit = runBlocking {
        val ledger = FakeLedger()
        ledger.upsert(SideEffectTransaction("k3", "send_whatsapp", "A", ContentHashing.hash("msg"), null, SideEffectState.ACTING, 0, 0, ""))
        val acted = Flaggable()
        val outcome = SideEffectRunner(ledger).execute("send_whatsapp", "k3", "A", "msg",
            act = { acted.flag = true; true },
            initiator = Initiator.OWNER_CHAT,
            verify = { evidence(true) })
        assertTrue(outcome is SideEffectOutcome.Uncertain)
        assertFalse(acted.flag)
        assertEquals(SideEffectState.ACTING, ledger.store.getValue("k3").state)
    }

    @Test fun verificationPendingIsNeverRepeated(): Unit = runBlocking {
        val ledger = FakeLedger()
        ledger.upsert(SideEffectTransaction("k4", "post_tiktok", "t", ContentHashing.hash("cap"), null, SideEffectState.VERIFICATION_PENDING, 0, 0, ""))
        val outcome = SideEffectRunner(ledger).execute("post_tiktok", "k4", "t", "cap",
            initiator = Initiator.OWNER_CHAT,
            act = { true }, verify = { evidence(true) })
        assertTrue(outcome is SideEffectOutcome.Uncertain)
    }

    @Test fun processDeathInEveryNonterminalStateIsSweptToUncertain(): Unit = runBlocking {
        listOf(SideEffectState.CLAIMED, SideEffectState.ACTING, SideEffectState.VERIFICATION_PENDING).forEach { state ->
            val ledger = FakeLedger()
            ledger.upsert(SideEffectTransaction("k-death-$state", "send_whatsapp", "A", ContentHashing.hash("m"), null, state, 0, 0, ""))
            // Production startup sweep: nonterminal states become UNCERTAIN (AmaraMemory).
            if (state != SideEffectState.UNCERTAIN) {
                ledger.transition("k-death-$state", SideEffectState.UNCERTAIN, "process death sweep")
            }
            val acted = Flaggable()
            val outcome = SideEffectRunner(ledger).execute("send_whatsapp", "k-death-$state", "A", "m",
                initiator = Initiator.OWNER_CHAT,
                act = { acted.flag = true; true },
                verify = { evidence(true) })
            assertTrue(outcome is SideEffectOutcome.Uncertain)
            assertFalse(acted.flag)
        }
    }

    @Test fun failedActIsTerminalForTheKeyAndVerifyNeverRuns(): Unit = runBlocking {
        val ledger = FakeLedger()
        val runner = SideEffectRunner(ledger)
        var verifyRan = false
        val first = runner.execute("send_whatsapp", "k5", "A", "msg",
            act = { false },
            initiator = Initiator.OWNER_CHAT,
            verify = { verifyRan = true; evidence(true) })
        assertTrue("first=$first", first is SideEffectOutcome.Failed)
        assertFalse(verifyRan)
        val acted = Flaggable()
        val second = runner.execute("send_whatsapp", "k5", "A", "msg", initiator = Initiator.OWNER_CHAT, act = { acted.flag = true; true }, verify = { evidence(true) })
        assertTrue(second is SideEffectOutcome.Failed)
        assertFalse(acted.flag)
    }

    @Test fun exceptionsDuringActAreUncertainUnlessNoEffectIsProvable(): Unit = runBlocking {
        val ledger = FakeLedger()
        val thrown = SideEffectRunner(ledger).execute("send_whatsapp", "k6b", "A", "msg",
            initiator = Initiator.OWNER_CHAT,
            act = { throw IllegalStateException("process died right after clicking Send") },
            verify = { evidence(true) })
        // Mandated: an exception during an external action must normally become UNCERTAIN.
        assertTrue(thrown is SideEffectOutcome.Uncertain)
        assertEquals(SideEffectState.UNCERTAIN, ledger.store.getValue("k6b").state)
        val acted = Flaggable()
        val retried = SideEffectRunner(ledger).execute("send_whatsapp", "k6b", "A", "msg",
            initiator = Initiator.OWNER_CHAT,
            act = { acted.flag = true; true },
            verify = { evidence(true) })
        assertTrue(retried is SideEffectOutcome.Uncertain)
        assertFalse("UNCERTAIN is never auto-retried", acted.flag)
    }

    @Test fun thrownVerifierBecomesUncertainNotVerified(): Unit = runBlocking {
        val ledger = FakeLedger()
        val outcome = SideEffectRunner(ledger).execute("send_whatsapp", "k7", "A", "msg",
            act = { true },
            initiator = Initiator.OWNER_CHAT,
            verify = { throw IllegalStateException("accessibility detached") })
        assertTrue(outcome is SideEffectOutcome.Uncertain)
        assertEquals(SideEffectState.UNCERTAIN, ledger.store.getValue("k7").state)
    }

    @Test fun noEffectProvenBlockerFinalizesFailedInsteadOfUncertain(): Unit = runBlocking {
        val ledger = FakeLedger()
        val outcome = SideEffectRunner(ledger).execute("send_whatsapp", "k8", "A", "msg",
            act = { true },
            initiator = Initiator.OWNER_CHAT,
            verify = { evidence(false, blocker = "${SendVerificationLogic.NO_EFFECT_PROVEN}: content absent") })
        assertTrue(outcome is SideEffectOutcome.Failed)
        assertEquals(SideEffectState.FAILED, ledger.store.getValue("k8").state)
    }

    @Test fun concurrentClaimAttemptsProduceExactlyOneExecution(): Unit = runBlocking {
        val ledger = FakeLedger()
        val executions = java.util.concurrent.atomic.AtomicInteger(0)
        val outcomes = kotlinx.coroutines.coroutineScope {
            (1..8).map {
                async(kotlinx.coroutines.Dispatchers.Unconfined) {
                    SideEffectRunner(ledger).execute(
                        "send_whatsapp", "k-race", "A", "msg",
                        act = { executions.incrementAndGet(); true },
                        initiator = Initiator.OWNER_CHAT,
                        verify = { evidence(true) },
                    )
                }
            }.map { it.await() }
        }
        assertEquals("Only one claim may execute the action", 1, executions.get())
        assertTrue(outcomes.any { it is SideEffectOutcome.Verified })
    }

    @Test fun deadlineOverrunFinalizesUncertainWithTerminalLedgerState(): Unit = runBlocking {
        var fakeNow = 0L
        val ledger = FakeLedger()
        val runner = SideEffectRunner(ledger, clock = { fakeNow })
        val outcome = runner.execute(
            "send_whatsapp", "k-deadline", "A", "msg",
            initiator = Initiator.OWNER_CHAT,
            act = { fakeNow += 200_000; true }, // blow past send_whatsapp's 90s timeout
            verify = { evidence(true) },
        )
        assertTrue(outcome is SideEffectOutcome.Uncertain)
        assertEquals(SideEffectState.UNCERTAIN, ledger.store.getValue("k-deadline").state)
        assertTrue(ledger.store.getValue("k-deadline").evidence.contains("deadline"))
        val acted = Flaggable()
        val retried = runner.execute(
            "send_whatsapp", "k-deadline", "A", "msg",
            initiator = Initiator.OWNER_CHAT,
            act = { acted.flag = true; true },
            verify = { evidence(true) },
        )
        assertTrue("terminal UNCERTAIN must not auto-retry", retried is SideEffectOutcome.Uncertain)
        assertFalse(acted.flag)
    }

    // ---------- verifier logic ----------

    @Test fun missingDeliveryStateIsUnverifiedNotVerified() {
        val result = SendVerificationLogic.evaluate(
            "Hello there team",
            SendObservation("com.whatsapp", "com.whatsapp", targetVisible = true, contentVisibleOutsideDraft = true, contentStillInDraftField = false, deliveryState = null, observedAtMs = 0),
        )
        assertFalse(result.verified)
        assertTrue(result.blocker!!.contains("delivery", ignoreCase = true))
    }

    @Test fun draftOnlyContentIsNeverVerified() {
        val result = SendVerificationLogic.evaluate(
            "Hello there team",
            SendObservation("com.whatsapp", "com.whatsapp", targetVisible = true, contentVisibleOutsideDraft = false, contentStillInDraftField = true, deliveryState = "Sent", observedAtMs = 0),
        )
        assertFalse(result.verified)
        assertTrue(result.blocker!!.contains("draft", ignoreCase = true))
    }

    @Test fun sentBubblePlusLingeringDraftTextStillVerifies() {
        // Node-role rule: identical text in a read-only bubble counts as sent even while
        // the draft field still contains it (the defect fixed in this corrective pass).
        val facts = MessageNodeFacts(inReadOnlyBubble = true, onlyInsideEditableField = true, deliveryState = "Delivered")
        val (inBubble, draftOnly) = SendVerificationLogic.evaluateNodeRoles("Hello team", facts)
        assertTrue(inBubble)
        assertFalse(draftOnly)
    }

    @Test fun wrongPackageOrTargetIsNeverVerified() {
        val wrongPackage = SendVerificationLogic.evaluate(
            "msg", SendObservation("com.other", "com.whatsapp", true, true, false, "Sent", 0),
        )
        val wrongTarget = SendVerificationLogic.evaluate(
            "msg", SendObservation("com.whatsapp", "com.whatsapp", false, true, false, "Sent", 0),
        )
        assertFalse(wrongPackage.verified)
        assertFalse(wrongTarget.verified)
    }

    @Test fun publicationVerificationBindsExpectedPackage() {
        val spec = PublicationSurfaceRule("com.whatsapp")
        val wrongApp = spec.evaluate("my status text", "com.other", contentVisible = true)
        val rightApp = spec.evaluate("my status text", "com.whatsapp", contentVisible = true)
        val rightAppMissing = spec.evaluate("my status text", "com.whatsapp", contentVisible = false)
        assertFalse(wrongApp.verified)
        assertTrue(rightApp.verified)
        assertFalse(rightAppMissing.verified)
        assertNotNull(wrongApp.blocker)
    }

    @Test fun contentMatchingAgreesWithHashingNormalization() {
        val lines = listOf("12:01", " Hello   Team ", "Delivered")
        assertTrue(SendVerificationLogic.contentMatches(lines, "hello team"))
        assertFalse(SendVerificationLogic.contentMatches(lines, "goodbye team"))
    }
}
