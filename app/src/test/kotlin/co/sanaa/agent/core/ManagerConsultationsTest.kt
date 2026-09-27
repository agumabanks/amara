package co.sanaa.agent.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ManagerConsultationsTest {
    @Test fun restartRetainsCustomerAndClaimPreventsReplay() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("manager_consultations",0).edit().clear().commit()
        val store = ManagerConsultations(context)
        val ref = store.open("inbound-1","1:2","customer-a","Customer A","Delivery question")
        assertEquals(ref, store.open("inbound-1","1:2","customer-a","Customer A","Delivery question"))
        assertNotEquals(ref,store.open("inbound-1","1:3","customer-b","Customer B","Question"))
        val restarted = ManagerConsultations(context)
        assertEquals("customer-a",restarted.get(ref)!!.getString("contactId"))
        assertNotNull(restarted.claim(ref,"manager-answer-1","Delivery on Monday"))
        assertNull(ManagerConsultations(context).claim(ref,"manager-answer-2","Send again"))
        restarted.finish(ref,"NEEDS_REVIEW")
        assertNull(restarted.claim(ref,"manager-answer-3","Retry"))
        assertEquals(ref,restarted.reference("$ref Delivery on Monday"))
        assertNull(restarted.reference("Please promote the printer"))
    }
}
