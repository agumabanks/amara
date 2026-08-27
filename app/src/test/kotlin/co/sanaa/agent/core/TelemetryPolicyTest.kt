package co.sanaa.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryPolicyTest {

    @Test
    fun optOutSuppressesPayloadEntirely() {
        val decision = TelemetryPolicy.gate(optIn = false, payload = "owner sent a customer the receipt")
        assertFalse(decision.allowed)
        assertNull(decision.payload)
        assertTrue(decision.reason.contains("not opted in"))
    }

    @Test
    fun optOutSuppressesErrorDetailEntirely() {
        val decision = TelemetryPolicy.gateError(optIn = false, error = "Groq 401: bearer gsk-DEADBEEFCAFEBABE1234")
        assertFalse(decision.allowed)
        assertNull(decision.payload)
    }

    @Test
    fun optInRedactsPinShapesBeforeExport() {
        val raw = "Soko login failed: staff pin is 123456 and otp is 998877"
        val decision = TelemetryPolicy.gate(optIn = true, payload = raw)
        assertTrue(decision.allowed)
        val exported = decision.payload!!
        assertFalse("PIN digits must be redacted", exported.contains("123456"))
        assertFalse("OTP digits must be redacted", exported.contains("998877"))
        assertTrue(exported.contains(Redactor.REDACTION_PREFIX))
    }

    @Test
    fun optInRedactsBearerAndApiKeyShapesBeforeExport() {
        val raw = "upstream error: api_key is abcdef0123456789ABCDEF and header bearer xyzzy-1234567890"
        val decision = TelemetryPolicy.gate(optIn = true, payload = raw)
        assertTrue(decision.allowed)
        val exported = decision.payload!!
        assertTrue(
            "raw api_key value must not appear in the redacted export: $exported",
            !exported.contains("abcdef0123456789ABCDEF"),
        )
        assertTrue(
            "raw bearer value must not appear in the redacted export: $exported",
            !exported.contains("xyzzy-1234567890"),
        )
    }

    @Test
    fun optInErrorExportRedactsLongDigitRunsAndTruncates() {
        val longRun = "x".repeat(20) + " " + "9".repeat(12) + " " + "y".repeat(20)
        val decision = TelemetryPolicy.gateError(optIn = true, error = longRun)
        assertTrue(decision.allowed)
        val exported = decision.payload!!
        assertFalse("long digit run must be redacted", exported.contains("9".repeat(12)))
        assertTrue(exported.length <= 2_000)
    }

    @Test
    fun optInWithNoSecretsIsPassedThroughAfterTruncation() {
        val payload = "summary: catalog audit completed; 14 products reconciled; no anomalies"
        val decision = TelemetryPolicy.gate(optIn = true, payload = payload)
        assertTrue(decision.allowed)
        assertEquals(payload, decision.payload)
    }

    @Test
    fun optInNullErrorReturnsNullPayload() {
        val decision = TelemetryPolicy.gateError(optIn = true, error = null)
        assertTrue(decision.allowed)
        assertNull(decision.payload)
        assertNotNull(decision.reason)
    }
}
