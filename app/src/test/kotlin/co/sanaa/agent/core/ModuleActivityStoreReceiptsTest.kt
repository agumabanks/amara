package co.sanaa.agent.core

import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.work.Domain
import co.sanaa.agent.core.work.FailureClass
import co.sanaa.agent.core.work.FailureInfo
import co.sanaa.agent.core.work.WorkItem
import co.sanaa.agent.core.work.WorkKind
import co.sanaa.agent.core.work.WorkResult
import co.sanaa.agent.core.work.WorkStatus
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Meaningful receipt tests: counts remain correct across retries, preparation
 * failures before dispatch, migration and restart; a verified side effect is never
 * overwritten by a generic attempt record; the canonical ledger stays the single
 * authority for transaction state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ModuleActivityStoreReceiptsTest {
    private fun item(key: String, kind: WorkKind = WorkKind.YOUTUBE_SHORT_PUBLISH,
                     attempt: Int = 0, payload: JSONObject = JSONObject()) = WorkItem(
        dedupeKey = key, domain = Domain.YOUTUBE, kind = kind, payload = payload,
        baseValueKes = 200.0, urgencyHalfLifeHours = 8.0, estimatedScreenSeconds = 180,
        attempt = attempt)

    private fun store() = ModuleActivityStore(ApplicationProvider.getApplicationContext())

    @Test fun everyAttemptGetsReceiptIncludingPreparationFailures() {
        val key = "youtube:src:attempt-coverage"
        store().use { s ->
            // Attempt 1: preparation failure before dispatch (held) — still a receipt.
            s.record(WorkResult(item(key), WorkStatus.ESCALATED,
                failure = FailureInfo(FailureClass.PRECONDITION_GONE, "Channel or soundtrack permission is not ready", false)))
            assertEquals(1, s.receiptCount(key))
            // Attempt 2: failed before dispatch ("external trigger was never dispatched").
            s.record(WorkResult(item(key, attempt = 1), WorkStatus.ESCALATED,
                failure = FailureInfo(FailureClass.UNKNOWN, "Shorts dispatch: Failed(external trigger was never dispatched.)", false)))
            assertEquals(1, s.receiptCount(key))
            val receipt = s.receipts(query = key).single()
            assertEquals(ReceiptStateKeys.FAILED_BEFORE_DISPATCH, receipt["outcome"])
            assertEquals(1, receipt["attempt"])
        }
    }

    @Test fun verifiedSideEffectIsNeverDowngradedByGenericAttemptRecord() {
        val key = "youtube:src:verified-no-downgrade"
        store().use { s ->
            // The side-effect ledger projection (during execute) reports VERIFIED.
            s.recordEffect("POST_YOUTUBE_SHORT", key, "VERIFIED", "@sanaasanaa1774", "hash123")
            // The generic attempt record (after execute) maps DONE → completed.
            s.record(WorkResult(item(key), WorkStatus.DONE,
                outcomeFacts = listOf("Verified Short: My Short on @sanaasanaa1774")))
            val receipt = s.receipts(query = key).single()
            assertEquals(ReceiptStateKeys.VERIFIED, receipt["outcome"])
            assertEquals("verified", receipt["dispatch_certainty"])
            assertEquals("@sanaasanaa1774", receipt["target"])
            assertEquals("hash123", receipt["content_hash"])
            assertTrue((receipt["settled_at"] as Long) > 0)
        }
    }

    @Test fun duplicateBlockedKeepsTheOriginalReceiptNotAFreshPostCount() {
        val key = "youtube:src:duplicate-blocked"
        store().use { s ->
            s.recordEffect("POST_YOUTUBE_SHORT", key, "VERIFIED", "@sanaasanaa1774", "hash123")
            s.record(WorkResult(item(key), WorkStatus.DONE,
                outcomeFacts = listOf("Verified Short: original")))
            // A retry with the same key is DuplicateBlocked: the receipt still refers
            // to the original verified attempt, never a fresh post count.
            s.record(WorkResult(item(key, attempt = 1), WorkStatus.DONE,
                outcomeFacts = listOf("Original upload retained; no replay")))
            val receipt = s.receipts(query = key).single()
            assertEquals(ReceiptStateKeys.VERIFIED, receipt["outcome"])
            assertEquals("Verified Short: original", receipt["evidence"])
            assertEquals(1, s.receipts(query = key).size)
        }
    }

    @Test fun stateChangesAppendHistoryWithoutRewritingOriginalEvidence() {
        val key = "youtube:src:history"
        store().use { s ->
            s.recordEffect("POST_YOUTUBE_SHORT", key, "CLAIMED", "@sanaasanaa1774", "hash")
            s.recordEffect("POST_YOUTUBE_SHORT", key, "ACTING", "@sanaasanaa1774", "hash")
            s.recordEffect("POST_YOUTUBE_SHORT", key, "FAILED", "@sanaasanaa1774", "hash")
            val history = s.receiptHistory(key)
            assertEquals(listOf(
                ReceiptStateKeys.PREPARED, ReceiptStateKeys.PREPARED,
                ReceiptStateKeys.FAILED_BEFORE_DISPATCH), history.map { it["outcome"] })
            // The current receipt matches the latest state; original evidence retained.
            assertEquals(ReceiptStateKeys.FAILED_BEFORE_DISPATCH, s.receipts(query = key).single()["outcome"])
        }
    }

    @Test fun migrationFromV1PreservesActivityAndCreatesReceiptTables() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("module_activity.db")
        // Simulate a v1 database with existing activity rows.
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath("module_activity.db").path, null).use { db ->
            db.execSQL("CREATE TABLE activity (id TEXT PRIMARY KEY,module TEXT,kind TEXT,status TEXT,at INTEGER,detail TEXT)")
            db.execSQL("CREATE INDEX activity_time ON activity(at)")
            db.execSQL("INSERT INTO activity VALUES ('legacy:0','TikTok','TIKTOK_POST_PUBLISH','DONE',${System.currentTimeMillis()},'legacy attempt')")
            db.version = 1
        }
        store().use { s ->
            // The v1 activity row survives the upgrade; receipts tables exist.
            val dash = s.dashboard(emptyList())
            assertEquals(1, dash["TikTok"]?.let { (it as Map<*, *>)["attempts"] })
            assertEquals(0, s.receipts().size)
        }
    }

    @Test fun interruptedPersistenceAndRestartCannotClaimVerifiedDispatch() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        store().use { s ->
            // An interrupted attempt left the receipt dispatched/unverified.
            s.recordEffect("POST_TIKTOK", "tiktok:src:orphan", "ACTING", "Some target", "hash")
        }
        // An orphaned receipt alone is not evidence of a canonical transaction.
        // Restart recovery must not invent an uncertain external dispatch.
        val memory = co.sanaa.agent.core.AmaraMemory(ApplicationProvider.getApplicationContext())
        memory.markOrphanedTransactionsUncertain()
        store().use { s ->
            val receipt = s.receipts(query = "tiktok:src:orphan").single()
            assertEquals(ReceiptStateKeys.PREPARED, receipt["outcome"])
            assertEquals("dispatch_attempting", receipt["dispatch_certainty"])
        }
    }

    @Test fun filteredPaginatedReadsAndCountsStayCorrectAcrossRetries() {
        val key = "wa:contact:retries"
        store().use { s ->
            // Attempt 0 mirrors production: the transaction hook reports VERIFIED and
            // the generic attempt record follows (never downgrades it).
            s.recordEffect("FOLLOW_UP_WHATSAPP", key, "CLAIMED", "+256700000003", "hash")
            s.recordEffect("FOLLOW_UP_WHATSAPP", key, "VERIFIED", "+256700000003", "hash")
            s.record(WorkResult(item(key, WorkKind.WA_FOLLOWUP), WorkStatus.DONE,
                outcomeFacts = listOf("Verified one consented follow-up")))
            // Attempt 1 is DuplicateBlocked: the original receipt is retained, never a
            // fresh post count. Attempt 2 is held by a precondition; neither changes
            // the truthful verified state of the logical action.
            s.record(WorkResult(item(key, WorkKind.WA_FOLLOWUP, 1), WorkStatus.DONE,
                outcomeFacts = listOf("Already followed up this message")))
            s.record(WorkResult(item(key, WorkKind.WA_FOLLOWUP, 2), WorkStatus.ESCALATED,
                failure = FailureInfo(FailureClass.PRECONDITION_GONE, "held", false)))
            val all = s.receipts(module = "WhatsApp")
            assertEquals(1, all.size) // one logical action, one current receipt
            assertEquals(ReceiptStateKeys.VERIFIED, all.single()["outcome"])
            val counts = s.receiptCounts()
            assertEquals(1, counts["verified"])
            assertTrue((counts["held"] ?: 0) == 0)
            // The row's state changes are the append-only history: prepared → verified.
            // The later held retry did not change the truthful verified state; it stays
            // visible through the attempt counter and module activity records.
            assertEquals(listOf(ReceiptStateKeys.PREPARED, ReceiptStateKeys.VERIFIED),
                s.receiptHistory(key).map { it["outcome"] })
            assertTrue((s.receipts(module = "WhatsApp").single()["attempt"] as Int) >= 2)
        }
    }

    @Test fun uncertainSendsAreNotRememberedAsVerifiedReplies() {
        val key = "wa:contact:uncertain-send"
        store().use { s ->
            s.recordEffect("REPLY_WHATSAPP", key, "UNCERTAIN", "+256700000001", "hash")
            s.record(WorkResult(item(key, kind = WorkKind.WA_REPLY_INBOUND), WorkStatus.ESCALATED,
                failure = FailureInfo(FailureClass.UNKNOWN, "Whether the action took effect could not be proven.", false)))
            val receipt = s.receipts(query = key).single()
            assertEquals(ReceiptStateKeys.UNCERTAIN, receipt["outcome"])
            assertEquals("WhatsApp", receipt["module"])
            assertFalse(receipt["outcome"] == ReceiptStateKeys.VERIFIED)
        }
    }

    @Test fun exportIsRedactedAndExcludesCustomerConversationContent() {
        val key = "wa:contact:export"
        store().use { s ->
            s.recordEffect("REPLY_WHATSAPP", key, "VERIFIED", "+256700000002", "hash")
            val exported = s.exportReceipts()
            assertFalse(exported.contains("+256700000002"))
            assertTrue(exported.contains("REPLY_WHATSAPP"))
            assertTrue(exported.contains("verified"))
        }
    }
}

private object ReceiptStateKeys {
    const val PREPARED = ModuleActivityStore.Receipt.OUTCOME_PREPARED
    const val HELD = ModuleActivityStore.Receipt.OUTCOME_HELD
    const val COMPLETED = ModuleActivityStore.Receipt.OUTCOME_COMPLETED
    const val FAILED_BEFORE_DISPATCH = ModuleActivityStore.Receipt.OUTCOME_FAILED_BEFORE_DISPATCH
    const val DISPATCHED_UNVERIFIED = ModuleActivityStore.Receipt.OUTCOME_DISPATCHED_UNVERIFIED
    const val UNCERTAIN = ModuleActivityStore.Receipt.OUTCOME_UNCERTAIN
    const val VERIFIED = ModuleActivityStore.Receipt.OUTCOME_VERIFIED
}
