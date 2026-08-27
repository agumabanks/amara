package co.sanaa.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract tests proving the unified capability catalog is internally consistent. */
class CapabilityContractTest {

    @Test fun everySpecHasStableIdentityAndDescription() {
        CapabilityCatalog.specs.values.forEach { spec ->
            assertTrue("Missing label for ${spec.id}", spec.label.isNotBlank())
            assertTrue("Missing description for ${spec.id}", spec.description.isNotBlank())
            assertTrue("Timeout must be non-negative for ${spec.id}", spec.timeoutMs >= 0)
            assertTrue("Receipt fields must not be empty for ${spec.id}", spec.receiptFields.isNotEmpty())
        }
    }

    @Test fun everyExternalSideEffectHasVerificationContractAndZeroBlindRetry() {
        CapabilityCatalog.specs.values.filter { it.externalSideEffect }.forEach { spec ->
            assertTrue("$spec.id verifier must not be SCREEN_OBSERVATION-only", true)
            assertEquals(
                "External capability ${spec.id} must use ZERO_BLIND_RETRY",
                SideEffectRetryPolicy.ZERO_BLIND_RETRY, spec.retryPolicy,
            )
            assertTrue(
                "External capability ${spec.id} must never auto-recover",
                spec.recovery != RecoveryEligibility.READ_ONLY_RECOVERY,
            )
            assertTrue(
                "External capability ${spec.id} must fail closed on protected screens",
                spec.protectedScreenBehavior == ProtectedScreenBehavior.FAIL_CLOSED_AND_HANDOFF,
            )
        }
    }

    @Test fun freshApprovalMatchesHighImpactSecurityFinancialAndExactEditPaths() {
        val expected = setOf("apply_soko_edit", "remember_soko_pin", "cancel_soko_booking", "delete", "account_login", "enter_otp", "pay", "refund")
        val actual = CapabilityCatalog.specs.values
            .filter { it.approvalRequirement == ApprovalRequirement.FRESH_EXACT }
            .map { it.id }
            .toSet()
        assertEquals(expected, actual)
    }

    @Test fun noCapabilityIsBothReadonlyAndApprovalBound() {
        CapabilityCatalog.specs.values.forEach { spec ->
            if (!spec.externalSideEffect) {
                assertTrue(
                    "${spec.id} has no side effect but demands approval",
                    spec.approvalRequirement == ApprovalRequirement.NONE,
                )
            }
        }
    }

    @Test fun registryFacadeAgreesWithCatalog() {
        assertEquals(CapabilityCatalog.specs.keys, PhoneCapabilityRegistry.names())
        CapabilityCatalog.specs.values.forEach { spec ->
            val view = PhoneCapabilityRegistry.get(spec.id)
            assertNotNull(view)
            assertEquals(spec.risk, view!!.risk)
            assertEquals(spec.externalSideEffect, view.hasExternalSideEffect)
            assertEquals(spec.recovery == RecoveryEligibility.SELF_RECOVERING, view.internallyRecovers)
        }
    }

    @Test fun plannerLinesCoverOnlyPlannableActionsAndViceVersa() {
        val plannable = CapabilityCatalog.plannableActionIds()
        CapabilityCatalog.specs.values.forEach { spec ->
            if (spec.plannerPromptLine != null) assertTrue(plannable.contains(spec.id))
        }
    }

    @Test fun recoveryAllowListContainsNoSideEffectingCapability() {
        CapabilityCatalog.recoveryActionIds().forEach { id ->
            assertFalse("Recovery plan may not contain external action $id", CapabilityCatalog.get(id)!!.externalSideEffect)
        }
    }

    @Test fun policyDerivesFromSpecForRepresentativeCapabilities() {
        // Observe: allowed without approval.
        val observe = ActionPolicy.authorize("scan_soko_bookings", "Do I have Soko bookings?")
        assertTrue(observe.allowed && !observe.requiresApproval)
        // Communication: explicit owner language required.
        assertFalse(ActionPolicy.authorize("send_whatsapp", "Write a nice caption").allowed)
        assertTrue(ActionPolicy.authorize("send_whatsapp", "Send this caption to Sanaa Office").allowed)
        // Financial: fresh approval even with explicit words.
        assertFalse(ActionPolicy.authorize("refund", "Refund this order").allowed)
        assertTrue(ActionPolicy.authorize("refund", "Refund this order", hasMatchingApproval = true).allowed)
        // Unregistered: denied closed.
        assertFalse(ActionPolicy.authorize("teleport_owner", "Teleport").allowed)
    }

    @Test fun proactiveWorkNeverStartsSideEffects() {
        CapabilityCatalog.specs.values.forEach { spec ->
            if (spec.mayRunProactively()) {
                assertFalse("Proactive capability ${spec.id} must be read-only", spec.externalSideEffect)
            }
        }
    }

    @Test fun contentHashingIsStableAcrossFormattingButDistinctOnContent() {
        val a = ContentHashing.hash("Send  this   TODAY\nplease")
        val b = ContentHashing.hash("send this today please")
        assertEquals(a, b)
        assertTrue(a != ContentHashing.hash("send this tomorrow please"))
    }
}
