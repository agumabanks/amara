package co.sanaa.agent.modules

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.*
import co.sanaa.agent.core.work.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class WhatsAppFollowUpIdentityTest {
    @Test fun originRequiresItsOwnVerifiedDirectPhoneAndRespectsRevocation() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val directory=ContactDirectory(ContactDirectoryStore(context))
        fun entry(id:String,phone:String?,group:Boolean=false)=directory.upsert(DirectoryEntry(
            id,"Sam",phone,emptySet(),group,EntrySource.WHATSAPP,1L,Ambiguity.UNIQUE,
            Classification.CUSTOMER,CommercialConsent.GRANTED,mapOf(Operation.MONITOR to Permission.ALLOW),null,null))
        val a=entry("wa-origin:a",null)
        entry("wa-origin:b","+256700111222")
        assertNull(WhatsAppFollowUpIdentity.target(directory,a.id))
        assertEquals("+256700111222",WhatsAppFollowUpIdentity.target(directory,"wa-origin:b"))
        entry("wa-origin:group","+256700111333",true)
        assertNull(WhatsAppFollowUpIdentity.target(directory,"wa-origin:group"))
        directory.revokeAll("wa-origin:b","Owner revoked")
        assertNull(WhatsAppFollowUpIdentity.target(directory,"wa-origin:b"))
    }
    @Test fun draftPersistsAcrossRetryAndCannotBeReplacedOrBoundOutsideLease() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val queue=WorkQueue(context)
        val item=WorkItem("followup-test",Domain.WHATSAPP,WorkKind.WA_FOLLOWUP,
            payload=JSONObject().put("target","Sam"),baseValueKes=1.0,urgencyHalfLifeHours=1.0,estimatedScreenSeconds=1)
        queue.offer(item)
        assertFalse(queue.bindFollowUpPayload(item.dedupeKey,JSONObject().put("message","First")))
        assertTrue(queue.markInFlight(item.dedupeKey))
        assertTrue(queue.bindFollowUpPayload(item.dedupeKey,JSONObject().put("message","First")))
        assertFalse(queue.bindFollowUpPayload(item.dedupeKey,JSONObject().put("message","Different")))
        queue.requeue(item.dedupeKey,0,1)
        assertTrue(queue.markInFlight(item.dedupeKey))
        assertTrue(queue.bindFollowUpPayload(item.dedupeKey,JSONObject().put("message","First")))
        queue.readableDatabase.rawQuery("SELECT payload FROM work_items WHERE dedupe_key=?",arrayOf(item.dedupeKey)).use {
            assertTrue(it.moveToFirst());val payload=JSONObject(it.getString(0))
            assertEquals("Sam",payload.getString("target"));assertEquals("First",payload.getString("message"))
        }
    }
}
