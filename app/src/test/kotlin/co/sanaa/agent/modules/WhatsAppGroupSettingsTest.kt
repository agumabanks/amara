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
    @Test fun dispatchEvidenceSurvivesSettingsReloadAndUnknownOutcomeReplacesIt() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val entry=DirectoryEntry("recovery-proof","Recovery group",null,emptySet(),true,
            EntrySource.WHATSAPP,1L,Ambiguity.UNIQUE,Classification.UNKNOWN,CommercialConsent.UNKNOWN,emptyMap(),null,null)
        val settings=WhatsAppGroupSettings(context)
        settings.outcome(entry,"FAILED","Network failure","attempt-one","NOT_STARTED")
        val restored=WhatsAppGroupSettings(context).row(entry)
        assertEquals("attempt-one",restored["lastWorkKey"])
        assertEquals("NOT_STARTED",restored["lastDispatchState"])
        settings.outcome(entry,"FAILED","Uncertain")
        assertEquals("UNKNOWN",settings.row(entry)["lastDispatchState"])
    }

    @Test fun provenPreSendNetworkFailuresRetryButNeverClearAnExistingHold() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val directory=ContactDirectory(ContactDirectoryStore(context))
        val entry=directory.upsert(DirectoryEntry("network-test","Network group",null,emptySet(),true,
            EntrySource.WHATSAPP,1L,Ambiguity.UNIQUE,Classification.UNKNOWN,CommercialConsent.UNKNOWN,emptyMap(),null,null))
        val settings=WhatsAppGroupSettings(context)
        val reason="Pre-send catalogue network failure; no send attempted"
        repeat(3) { settings.outcome(entry,"FAILED",reason) }
        assertEquals(false,settings.row(entry)["paused"])
        assertEquals(0,settings.row(entry)["consecutiveFailures"])
        settings.outcome(entry,"FAILED","Uncertain delivery")
        settings.outcome(entry,"FAILED",reason)
        assertEquals(true,settings.row(entry)["paused"])
    }

    @Test fun profilesFilterWholeTopicsWithoutSharingNamesOrGrantingPermission() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val directory=ContactDirectory(ContactDirectoryStore(context))
        val settings=WhatsAppGroupSettings(context)
        fun entry(id:String)=directory.upsert(DirectoryEntry(id,"Same",null,emptySet(),true,
            EntrySource.WHATSAPP,1L,Ambiguity.UNIQUE,Classification.UNKNOWN,CommercialConsent.UNKNOWN,emptyMap(),null,null))
        val a=entry("profile-a"); val b=entry("profile-b")
        settings.update(directory,a.id,"replyKeywords","printing, design")
        settings.update(directory,a.id,"purpose","Local businesses")
        assertTrue(settings.acceptsReply(a,"Who does printing?"))
        assertFalse(settings.acceptsReply(a,"Redesigning the road today"))
        assertTrue(settings.acceptsReply(b,"General chatter"))
        assertFalse(settings.allows(directory.byId(a.id)!!,"reply"))
        assertTrue(WhatsAppGroupSettings(context).context(a.id).contains("Local businesses"))
        assertFalse(settings.context(b.id).contains("Local businesses"))
    }

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
        assertTrue(settings.row(directory.byId(a.id)!!)["paused"]==true)
        assertTrue(settings.due(directory.byId(a.id)!!) in (System.currentTimeMillis()+29*60_000L)..(System.currentTimeMillis()+31*60_000L))
        assertTrue((settings.row(directory.byId(a.id)!!)["lastReason"] as String).contains("no matching recipient"))
        settings.update(directory,a.id,"promote",true)
        settings.update(directory,a.id,"resume",true)
        assertTrue(settings.due(directory.byId(a.id)!!)<=System.currentTimeMillis())
        settings.outcome(directory.byId(a.id)!!,"FAILED","Temporary preparation failure")
        assertNotEquals(Long.MAX_VALUE,settings.due(directory.byId(a.id)!!))
        settings.outcome(directory.byId(a.id)!!,"FAILED","Temporary preparation failure")
        assertTrue(settings.row(directory.byId(a.id)!!)["paused"]==true)
        assertTrue(settings.due(directory.byId(a.id)!!) in (System.currentTimeMillis()+29*60_000L)..(System.currentTimeMillis()+31*60_000L))
        settings.outcome(directory.byId(a.id)!!,"DONE")
        assertEquals(0,settings.row(directory.byId(a.id)!!)["consecutiveFailures"])
        settings.update(directory,a.id,"listen",false)
        assertFalse(settings.allows(directory.byId(a.id)!!,"reply"))
        settings.update(directory,a.id,"promote",true)
        assertTrue(settings.allows(directory.byId(a.id)!!,"listen"))
        assertFalse(settings.allows(directory.byId(a.id)!!,"reply"))
    }
}
