package co.sanaa.agent.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.core.AgentRuntime
import co.sanaa.agent.core.CredentialVault
import co.sanaa.agent.core.SecretCodec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * A notification is rendered on the lock screen and in the notification shade, so
 * any literal credential carried by a model reply, an error string, or an
 * accessibility observation would be readable by anyone holding the device.
 *
 * NotificationReporter previously passed title/message straight through, so
 * CredentialVault.redactKnownSecrets had zero production call sites and a
 * released Soko staff PIN could surface in a lock-screen notification. These
 * tests pin the redaction at that boundary.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationCredentialRedactionTest {

    private companion object {
        const val ID = AgentRuntime.SOKO_PIN_ID
        const val PKG = AgentRuntime.SOKO_TERMINAL_PACKAGE
        const val SECRET = "7391842"
    }

    /** Deterministic reversible codec so the test never depends on a real keystore. */
    private class ReversibleTestCodec : SecretCodec {
        override fun encrypt(alias: String, plaintext: CharArray): Pair<String, String> {
            val masked = plaintext.concatToString().toByteArray(Charsets.UTF_8)
                .map { (it.toInt() xor mask(alias)).toByte() }.toByteArray()
            return Base64.getEncoder().encodeToString(masked) to "iv:${alias.hashCode()}"
        }

        override fun decrypt(alias: String, blob: String, iv: String): CharArray {
            val unmasked = Base64.getDecoder().decode(blob)
                .map { (it.toInt() xor mask(alias)).toByte() }.toByteArray()
            return String(unmasked, Charsets.UTF_8).toCharArray()
        }

        override fun deleteAlias(alias: String) = Unit

        private fun mask(alias: String): Int = (alias.hashCode() ushr 1) or 0x20
    }

    private lateinit var context: Context
    private lateinit var vault: CredentialVault
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("credential_vault", Context.MODE_PRIVATE).edit().clear().commit()
        vault = CredentialVault(context, ReversibleTestCodec())
        vault.store(ID, PKG, "terminal_login", SECRET.toCharArray())
        manager = context.getSystemService(NotificationManager::class.java)
    }

    private fun postedText(id: Int): String {
        val notification = manager.activeNotifications.single { it.id == id }.notification
        val extras = notification.extras
        val parts = listOfNotNull(
            extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString(),
            extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString(),
        )
        return parts.joinToString(" ")
    }

    @Test
    fun storedSecretIsNeverRenderedInANotification() {
        val id = 40211
        try {
            NotificationReporter(context, vault).report(
                "Soko login rejected",
                "Staff Login refused code $SECRET. Amara will retry.",
                NotificationReporter.Priority.ACTION_NEEDED,
                notificationId = id,
            )
            val text = postedText(id)
            assertFalse("literal credential reached the notification: $text", text.contains(SECRET))
            assertTrue("expected a redaction marker in: $text", text.contains("[REDACTED:CREDENTIAL:$ID]"))
        } finally {
            manager.cancel(id)
        }
    }

    @Test
    fun secretInTheTitleIsAlsoRedacted() {
        val id = 40212
        try {
            NotificationReporter(context, vault).report(
                "Blocked: $SECRET",
                "Owner action required",
                NotificationReporter.Priority.URGENT,
                notificationId = id,
            )
            val text = postedText(id)
            assertFalse("literal credential reached the notification title: $text", text.contains(SECRET))
        } finally {
            manager.cancel(id)
        }
    }

    @Test
    fun ordinaryBusinessTextIsLeftIntact() {
        val id = 40213
        try {
            NotificationReporter(context, vault).report(
                "3 listings need photos",
                "Self-Inking Stamp — UGX 35,000 is missing a photo. Jiji shows 4 comparable listings.",
                NotificationReporter.Priority.INFO,
                notificationId = id,
            )
            val text = postedText(id)
            assertTrue("legitimate owner-facing text was mangled: $text", text.contains("UGX 35,000"))
            assertTrue("legitimate owner-facing text was mangled: $text", text.contains("Self-Inking Stamp"))
        } finally {
            manager.cancel(id)
        }
    }

    @Test
    fun reporterStillWorksWithoutAVault() {
        val id = 40214
        try {
            NotificationReporter(context).report("Battery low", "Connect charger",
                NotificationReporter.Priority.ACTION_NEEDED, notificationId = id)
            assertTrue(postedText(id).contains("Connect charger"))
        } finally {
            manager.cancel(id)
        }
    }
}
