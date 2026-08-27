package co.sanaa.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneContractsTest {
    @Test fun readOnlySokoSkillIsAllowedWithoutApproval() {
        val result = ActionPolicy.authorize("scan_soko_bookings", "Do I have Soko bookings?")
        assertTrue(result.allowed)
        assertFalse(result.requiresApproval)
    }

    @Test fun communicationRequiresExplicitOwnerLanguage() {
        assertFalse(ActionPolicy.authorize("send_whatsapp", "Write a nice caption").allowed)
        assertTrue(ActionPolicy.authorize("send_whatsapp", "Send this caption to Sanaa Office").allowed)
    }

    @Test fun financialActionNeedsMatchingFreshApproval() {
        assertFalse(ActionPolicy.authorize("refund", "Refund this order").allowed)
        assertTrue(ActionPolicy.authorize("refund", "Refund this order", hasMatchingApproval = true).allowed)
    }

    @Test fun explicitlyProvidedPinCanBeStoredButGenericLoginCannotRun() {
        assertTrue(ActionPolicy.authorize("remember_soko_pin", "The Soko Terminal PIN is 123456").allowed)
        assertFalse(ActionPolicy.authorize("account_login", "Open Soko Terminal").allowed)
    }

    @Test fun protectedScreensAreClassifiedForOwnerHandoff() {
        assertEquals(ProtectedScreenKind.OTP, ProtectedScreenClassifier.classify(listOf("Enter verification code"))!!.kind)
        assertEquals(ProtectedScreenKind.CAPTCHA, ProtectedScreenClassifier.classify(listOf("I'm not a robot CAPTCHA"))!!.kind)
        assertNotNull(ProtectedScreenClassifier.classify(listOf("Enter your phone number", "Continue")))
    }

    @Test fun controllerCapabilitiesAreRegisteredWithPolicy() {
        listOf(
            "read_soko_dashboard", "read_soko_customers", "read_soko_orders", "read_soko_refunds",
            "read_soko_suppliers", "soko_full_report", "tiktok_analytics", "tiktok_comments",
            "tiktok_feed", "tiktok_search", "tiktok_sounds",
        ).forEach { assertNotNull("Missing capability: $it", PhoneCapabilityRegistry.get(it)) }
    }
}
