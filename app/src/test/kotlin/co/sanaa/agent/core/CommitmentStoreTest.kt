package co.sanaa.agent.core

import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CommitmentStoreTest {
    private fun commitment(id: String, agreedAt: Long) = Commitment(
        id, "708:128:wa:contact", JSONArray(), "meeting", "Review order", agreedAt,
        "Africa/Kampala", "tomorrow at 2 pm", JSONArray(), CommitmentStore.Confirmation.AGREED,
        "", "", "", 5, 0, "owner", 0, 0,
    )

    @Test fun onlyOneClaimWinsAndReschedulingInvalidatesStaleRevision() {
        val store = CommitmentStore(ApplicationProvider.getApplicationContext())
        try {
            val start = System.currentTimeMillis() + 12 * 60_000L
            assertTrue(store.upsert(commitment("claim-test", start)))
            val first = store.byId("claim-test")!!
            val due = start - 5 * 60_000L
            assertTrue(store.claimReminder(first.id, first.revision, 1, due))
            assertFalse(store.claimReminder(first.id, first.revision, 1, due))
            assertTrue(store.upsert(first.copy(agreedAt = start + 60 * 60_000L)))
            assertFalse(store.claimReminder(first.id, first.revision, 2, due))
            assertEquals("INVALIDATED", store.remindersFor(first.id).single()["state"])
            assertTrue(store.cancel(first.id))
            val updated = store.byId(first.id)!!
            assertFalse(store.claimReminder(updated.id, updated.revision, 1,
                updated.agreedAt - updated.reminderLeadMinutes * 60_000L))
        } finally { store.close() }
    }

    @Test fun ambiguousTimezoneIsNotScheduledAndConfirmedZoneResolvesTomorrow() {
        val messageAt = 1_789_925_200_000L
        val store = CommitmentStore(ApplicationProvider.getApplicationContext())
        try {
            assertNull(store.resolveTimePhrase("tomorrow at 2 pm", messageAt, null))
            val zone = ZoneId.of("Africa/Kampala")
            val resolved = store.resolveTimePhrase("tomorrow at 2 pm", messageAt, zone)
            assertNotNull(resolved)
            assertEquals(14, java.time.Instant.ofEpochMilli(resolved!!).atZone(zone).hour)
        } finally { store.close() }
    }

    @Test fun confirmationAndRecipientChangesProduceNewReminderIdentity() {
        val store = CommitmentStore(ApplicationProvider.getApplicationContext())
        try {
            val id = "revision-test-${System.nanoTime()}"
            val at = System.currentTimeMillis() + 20 * 60_000L
            val draft = commitment(id, at).copy(confirmationStatus = CommitmentStore.Confirmation.CLARIFICATION_NEEDED)
            assertTrue(store.upsert(draft))
            val initial = store.byId(id)!!
            assertTrue(store.upsert(initial.copy(confirmationStatus = CommitmentStore.Confirmation.AGREED)))
            val agreed = store.byId(id)!!
            assertTrue(agreed.revision > initial.revision)
            assertTrue(store.upsert(agreed.copy(reminderRecipients = "customer")))
            assertTrue(store.byId(id)!!.revision > agreed.revision)
        } finally { store.close() }
    }
}
