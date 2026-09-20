package co.sanaa.agent.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class OwnerPowerTest {
    @Test fun powerSurvivesNewInstancesAndRemoteConfigCannotResumeIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val power = OwnerPower(context)
        power.setOn(false)
        assertFalse(OwnerPower(context).isOn())
        SecureConfig(context, useEncryptedPrefs = false).saveRemoteConfig(org.json.JSONObject().put("amaraOn", true))
        assertFalse(OwnerPower(context).isOn())
        power.setOn(true)
        assertTrue(OwnerPower(context).isOn())
    }

    @Test fun recoveryMemoryPersistsAndFailedRecheckRevokesReuse() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        RecoveryExperience(context).record("test-screen", "bounded_navigation", true)
        assertEquals("bounded_navigation", RecoveryExperience(context).provenStrategy("test-screen"))
        RecoveryExperience(context).record("test-screen", "bounded_navigation", false)
        assertNull(RecoveryExperience(context).provenStrategy("test-screen"))
    }

    @Test fun recallIsPerConversationAndLimitedToTwoDays() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("amara_chats.db")
        ChatStore(context).use { store ->
            store.storeMessage("a", "Customer", "received", "old")
            store.writableDatabase.execSQL("UPDATE chats SET timestamp=?", arrayOf(System.currentTimeMillis()-3*86_400_000L))
            store.storeMessage("a", "Customer", "received", "recent")
            store.storeMessage("b", "Other", "received", "private")
            assertEquals(listOf("recent"), store.getChatHistory("a").map { it.text })
        }
    }
}
