package co.sanaa.agent.modules

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class WhatsAppGroupSettingsTest {
    @Test fun duplicateGroupSettingsAreIndependentAndCadencePersists() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val directory=ContactDirectory(ContactDirectoryStore(context))
        val settings=WhatsAppGroupSettings(context)
        fun entry(id:String)=directory.upsert(DirectoryEntry(id,"Same group",null,emptySet(),true,
            EntrySource.WHATSAPP,1L,Ambiguity.UNIQUE,Classification.UNKNOWN,CommercialConsent.UNKNOWN,emptyMap(),null,null))
        val a=entry("wa-origin:group-a");val b=entry("wa-origin:group-b")
        assertFalse(settings.allows(a,"reply"))
        settings.update(directory,a.id,"listen",true)
        settings.update(directory,a.id,"reply",true)
        settings.update(directory,a.id,"intervalMinutes",60)
        assertTrue(settings.allows(directory.byId(a.id)!!,"reply"))
        assertFalse(settings.allows(directory.byId(b.id)!!,"reply"))
        settings.outcome(directory.byId(a.id)!!,"VERIFIED")
        assertTrue(WhatsAppGroupSettings(context).due(directory.byId(a.id)!!)>System.currentTimeMillis())
        settings.outcome(directory.byId(a.id)!!,"FAILED","Group publication: recipient_unavailable")
        assertEquals(Long.MAX_VALUE,settings.due(directory.byId(a.id)!!))
        assertTrue((settings.row(directory.byId(a.id)!!)["lastReason"] as String).contains("no matching recipient"))
        settings.update(directory,a.id,"promote",true)
        settings.update(directory,a.id,"resume",true)
        assertTrue(settings.due(directory.byId(a.id)!!)<=System.currentTimeMillis())
        settings.outcome(directory.byId(a.id)!!,"FAILED","Temporary preparation failure")
        assertNotEquals(Long.MAX_VALUE,settings.due(directory.byId(a.id)!!))
        settings.outcome(directory.byId(a.id)!!,"FAILED","Temporary preparation failure")
        assertEquals(Long.MAX_VALUE,settings.due(directory.byId(a.id)!!))
        settings.outcome(directory.byId(a.id)!!,"DONE")
        assertEquals(0,settings.row(directory.byId(a.id)!!)["consecutiveFailures"])
        settings.update(directory,a.id,"listen",false)
        assertFalse(settings.allows(directory.byId(a.id)!!,"reply"))
        settings.update(directory,a.id,"promote",true)
        assertTrue(settings.allows(directory.byId(a.id)!!,"listen"))
        assertFalse(settings.allows(directory.byId(a.id)!!,"reply"))
    }
}
