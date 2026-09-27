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
class WorkEvidenceConsentTest {
    @Test fun ownerSettingPersistsIndependentlyOfHealthReporting() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = SecureConfig(context, useEncryptedPrefs = false)
        config.telemetryOptIn = false
        config.operationalReportingEnabled = false
        val settings = AmaraSettings(context, useEncryptedPrefs = false)
        assertEquals(false, settings.getAll()["telemetryOptIn"])
        assertTrue(settings.set("telemetryOptIn", true))
        assertEquals(true, AmaraSettings(context, useEncryptedPrefs = false).getAll()["telemetryOptIn"])
        assertFalse(SecureConfig(context, useEncryptedPrefs = false).operationalReportingEnabled)
        assertTrue(settings.set("telemetryOptIn", false))
        assertFalse(SecureConfig(context, useEncryptedPrefs = false).telemetryOptIn)
    }
}
