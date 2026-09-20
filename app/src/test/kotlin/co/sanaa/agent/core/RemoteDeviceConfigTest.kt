package co.sanaa.agent.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteDeviceConfigTest {
    @Test fun adminChannelsDoNotResumeOwnerPowerOrEnableUnspecifiedChannels() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = SecureConfig(context, useEncryptedPrefs = false)
        OwnerPower(context).setOn(false)
        config.whatsAppAutomationEnabled = false
        config.tikTokSocialEnabled = true
        config.saveRemoteConfig(JSONObject().put("tiktok_test_mode", true).put("business_brief", "Stock questions first"))
        assertTrue(config.tikTokTestMode)
        assertTrue(config.tikTokSocialEnabled)
        assertFalse(config.whatsAppAutomationEnabled)
        assertFalse(OwnerPower(context).isOn())
        assertEquals("Stock questions first", config.businessBrief)
    }

    @Test fun adminCanDisableChannelAndClearManagerAndBrief() {
        val config = SecureConfig(ApplicationProvider.getApplicationContext<Context>(), useEncryptedPrefs = false)
        config.tikTokTestMode = true
        config.managerWhatsApp = "+256700000001"
        config.businessBrief = "Old priorities"
        config.saveRemoteConfig(JSONObject().put("tiktok_test_mode", false).put("manager_whatsapp", "").put("business_brief", ""))
        assertFalse(config.tikTokTestMode)
        assertEquals("", config.managerWhatsApp)
        assertEquals("", config.businessBrief)
    }
}
