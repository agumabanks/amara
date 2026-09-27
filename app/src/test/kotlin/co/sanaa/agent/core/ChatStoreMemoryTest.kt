package co.sanaa.agent.core

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Meaningful long-term memory tests: a conversation of 100+ messages across multiple
 * weeks recalls an early agreement after restart, incorporates a later correction, and
 * excludes another contact/shop's details; concurrent duplicate message observations
 * produce one logical message; larger histories stay within a bounded retrieval budget.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ChatStoreMemoryTest {
    private fun store() = ChatStore(ApplicationProvider.getApplicationContext())
    private fun <T> withStore(block: (ChatStore) -> T): T {
        val s = store()
        return block(s)
    }
    private val day = 86_400_000L

    @Test fun hundredMessagesAcrossWeeksRecallEarlyAgreementAfterRestart() {
        val chat = "708:128:wa-origin:agreement"
        val now = System.currentTimeMillis()
        withStore { s ->
            // 100+ messages across multiple weeks; the early agreement is message 5.
            for (i in 1..120) {
                val at = now - (121 - i) * day
                val text = if (i == 5) "I agree to buy the numbering machine at UGX 95,000 next week"
                    else "Message number $i about the shop"
                s.storeMessage(chat, if (i % 2 == 0) "Amara" else "Customer", "received", text, originalAt = at)
            }
            // A later correction supersedes the early price.
            s.storeMessage(chat, "Customer", "received",
                "Correction: the numbering machine price is now UGX 90,000", originalAt = now)
        }
        // Restart: a fresh store instance reads the same durable memory.
        withStore { s ->
            val recalled = s.recallContext(chat, "Customer discussed the numbering machine purchase.")
            assertTrue(recalled.contains("I agree to buy the numbering machine"))
            assertTrue(recalled.contains("Correction: the numbering machine price is now UGX 90,000"))
            assertTrue(recalled.contains("CONVERSATION SUMMARY"))
        }
    }

    @Test fun recallExcludesAnotherContactAndShopDetails() {
        val chatA = "708:128:wa-origin:contact-a"
        val chatB = "328:41:wa-origin:contact-b"
        val now = System.currentTimeMillis()
        withStore { s ->
            s.storeMessage(chatA, "Customer", "received",
                "I want the blue silk scarf reserved for me", originalAt = now - 30 * day)
            s.storeMessage(chatA, "Amara", "sent",
                "The blue silk scarf is UGX 45,000 and I can hold it", originalAt = now - 30 * day + 60_000)
            // Another contact in a different shop: must never leak into chat A's recall.
            s.storeMessage(chatB, "Buyer", "received",
                "Do you deliver the wooden stool to Ntinda", originalAt = now - 2 * day)
        }
        withStore { s ->
            val recalledA = s.recallContext(chatA, "Customer asked about the blue silk scarf.")
            assertTrue(recalledA.contains("blue silk scarf"))
            assertFalse(recalledA.contains("wooden stool"))
            assertFalse(recalledA.contains("Ntinda"))
            val recalledB = s.recallContext(chatB, "Buyer asked about delivery.")
            assertTrue(recalledB.contains("wooden stool"))
            assertFalse(recalledB.contains("silk scarf"))
        }
    }

    @Test fun duplicateObservationsProduceOneLogicalMessage() {
        val chat = "708:128:wa-origin:dedup"
        val now = System.currentTimeMillis()
        withStore { s ->
            // Notification re-delivery, repeated screen reads and restart recovery of
            // the same logical message deduplicate on the stable event identity.
            repeat(3) {
                s.recordEvent(chat, "Customer", "received", "Is the shop open today",
                    originalAt = now - 3_600_000L, observedAt = now, provenance = ChatStore.PROVENANCE_NOTIFICATION)
            }
            s.recordEvent(chat, "Customer", "received", "Is the shop open today",
                originalAt = now - 3_600_000L, observedAt = now + 5_000, provenance = ChatStore.PROVENANCE_SCREEN_READ)
            s.recordEvent(chat, "Customer", "received", "Is the shop open today",
                originalAt = now - 3_600_000L, observedAt = now + 9_000, provenance = ChatStore.PROVENANCE_RESTART_RECOVERY)
            assertEquals(1, s.eventCount(chat))
        }
    }

    @Test fun duplicateStoreMessageDoesNotInflateTranscriptOrSummary() {
        val chat = "708:128:wa-origin:store-dedup"
        val originalAt = System.currentTimeMillis() - 60_000
        withStore { s ->
            repeat(3) {
                s.storeMessage(chat, "Customer", "received", "Please call me tomorrow",
                    originalAt = originalAt)
            }
            assertEquals(1, s.eventCount(chat))
            assertEquals(1, s.getChatHistory(chat).size)
            assertEquals(1, s.messageCount(chat))
        }
    }

    @Test fun concurrentDuplicateObservationsProduceOneLogicalMessage() {
        val chat = "708:128:wa-origin:concurrent"
        val originalAt = System.currentTimeMillis() - 60_000
        val threads = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(8)
        repeat(8) {
            threads.execute {
                ChatStore(ApplicationProvider.getApplicationContext()).recordEvent(
                    chat, "Customer", "received", "Do you have this in stock",
                    originalAt = originalAt, provenance = ChatStore.PROVENANCE_NOTIFICATION)
                latch.countDown()
            }
        }
        latch.await()
        threads.shutdown()
        withStore { s -> assertEquals(1, s.eventCount(chat)) }
    }

    @Test fun generatedDraftIsNotRememberedAsSentMessage() {
        val chat = "708:128:wa-origin:draft"
        withStore { s ->
            // An outbound event is recorded only after verified dispatch; a draft that
            // was never sent must not become a remembered sent message.
            s.recordEvent(chat, "Amara", "sent", "Draft that was never dispatched",
                deliveryState = "pending")
            val events = s.chatEvents(chat)
            assertTrue(events.isEmpty())
            val recalled = s.recallContext(chat, null)
            assertFalse(recalled.contains("Draft that was never dispatched"))
        }
    }

    @Test fun largerHistoriesStayWithinBoundedRetrievalBudget() {
        val chat = "708:128:wa-origin:large"
        val now = System.currentTimeMillis()
        withStore { s ->
            // 500+ messages across weeks; the early agreement mentions the sofa.
            for (i in 1..520) {
                val at = now - (400 - i) * day
                val text = if (i == 3) "Early agreement: the three-seat sofa is reserved for Friday"
                    else "Routine message $i with varied small talk content"
                s.storeMessage(chat, if (i % 2 == 0) "Amara" else "Customer", "received", text, originalAt = at)
            }
        }
        withStore { s ->
            assertEquals(520, s.eventCount(chat))
            // Recent turns stay bounded; older keyword evidence is retrieved.
            val recent = s.getRecentTurns(chat, 12)
            assertEquals(12, recent.size)
            assertTrue(recent.first().timestamp > now - 30 * day)
            val recalled = s.recallContext(chat, "Customer asked about the sofa reservation.")
            assertTrue(recalled.contains("Early agreement: the three-seat sofa"))
            // The budget bounds the whole retrieval.
            assertTrue(recalled.length <= 4_000)
        }
    }

    @Test fun recentWindowAndLongTermRecallAreDistinctLayers() {
        val chat = "708:128:wa-origin:layers"
        val now = System.currentTimeMillis()
        withStore { s ->
            s.storeMessage(chat, "Customer", "received", "Old message from three weeks ago",
                originalAt = now - 21 * day)
            s.storeMessage(chat, "Customer", "received", "Fresh message from today",
                originalAt = now)
        }
        withStore { s ->
            // The legacy two-day window excludes the old message...
            assertTrue(s.getRecentChatHistory(chat).none { it.text.contains("three weeks ago") })
            // ...while long-term recall includes it.
            assertTrue(s.getChatHistory(chat).any { it.text.contains("three weeks ago") })
            assertTrue(s.recallContext(chat, null).contains("three weeks ago"))
        }
    }

    @Test fun existingV1DatabaseUpgradesWithoutLosingTranscriptsOrSummaries() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("amara_chats.db")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath("amara_chats.db").path, null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS chats (chat_key TEXT NOT NULL, sender TEXT NOT NULL, direction TEXT NOT NULL CHECK(direction IN ('sent','received')), message_text TEXT NOT NULL, timestamp INTEGER NOT NULL, platform TEXT DEFAULT 'whatsapp')")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_chats_key_time ON chats(chat_key, timestamp)")
            db.execSQL("CREATE TABLE IF NOT EXISTS chat_summaries (chat_key TEXT PRIMARY KEY, summary TEXT NOT NULL, stage TEXT DEFAULT 'GREETING', last_activity INTEGER NOT NULL, message_count INTEGER DEFAULT 0)")
            db.execSQL("CREATE TABLE IF NOT EXISTS offerings (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL CHECK(type IN ('PRODUCT','SERVICE')), name TEXT NOT NULL, price_ugx INTEGER, price_floor_ugx INTEGER, stock_count INTEGER, duration_minutes INTEGER, requires_booking INTEGER DEFAULT 0, description TEXT, source TEXT, synced_at INTEGER)")
            db.execSQL("INSERT INTO chats VALUES ('legacy-chat','Customer','received','Legacy transcript row before upgrade',${System.currentTimeMillis() - 5 * day},'whatsapp')")
            db.execSQL("INSERT INTO chat_summaries VALUES ('legacy-chat','Legacy summary','DISCOVERY',${System.currentTimeMillis()},1)")
            db.version = 1
        }
        withStore { s ->
            // The v1→v2 upgrade backfills events conservatively; transcripts and
            // summaries survive.
            assertEquals(1, s.eventCount("legacy-chat"))
            assertEquals("Legacy summary", s.getSummary("legacy-chat"))
            assertTrue(s.getChatHistory("legacy-chat").any { it.text.contains("Legacy transcript row") })
            // Re-opening does not duplicate the backfill.
        }
        withStore { s -> assertEquals(1, s.eventCount("legacy-chat")) }
    }

    @Test fun ownerDeletionControlRemovesLongTermEvents() {
        val chat = "708:128:wa-origin:delete"
        withStore { s ->
            s.recordEvent(chat, "Customer", "received", "Message one")
            s.recordEvent(chat, "Customer", "received", "Message two")
            assertEquals(2, s.deleteChatEvents(chat))
            assertEquals(0, s.eventCount(chat))
        }
    }
}
