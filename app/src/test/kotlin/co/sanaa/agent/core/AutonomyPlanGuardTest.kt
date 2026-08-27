package co.sanaa.agent.core

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomyPlanGuardTest {
    @Test fun acceptsExplicitSendToNamedRecipient() {
        val error = AutonomyPlanGuard.validate(
            "Send hello to Sanaa One",
            "",
            listOf(PlannedStep("send_whatsapp", "Sanaa One", "Hello", "", "Greet the contact")),
        )
        assertNull(error)
    }

    @Test fun rejectsSendWhenOwnerDidNotRequestOne() {
        val error = AutonomyPlanGuard.validate(
            "What is happening today?",
            "Sanaa One",
            listOf(PlannedStep("send_whatsapp", "", "Hello", "", "Follow up")),
        )
        assertTrue(error!!.contains("explicitly"))
    }

    @Test fun rejectsMissingRecipient() {
        val error = AutonomyPlanGuard.validate(
            "Send the update",
            "",
            listOf(PlannedStep("send_whatsapp", "", "Update", "", "Send update")),
        )
        assertTrue(error!!.contains("exact WhatsApp contact"))
    }

    @Test fun rejectsInventedRecipient() {
        val error = AutonomyPlanGuard.validate(
            "Send the update to Sarah",
            "",
            listOf(PlannedStep("send_whatsapp", "Sanaa One", "Update", "", "Send update")),
        )
        assertTrue(error!!.contains("not certain"))
    }

    @Test fun rejectsImplicitStatusPublication() {
        val error = AutonomyPlanGuard.validate(
            "Write a good promotion",
            "",
            listOf(PlannedStep("post_whatsapp_status", "", "Promotion", "", "Publish promotion")),
        )
        assertTrue(error!!.contains("explicitly"))
    }

    @Test fun allowsReadOnlySokoInventoryScan() {
        val error = AutonomyPlanGuard.validate(
            "Read every visible Soko product and report coverage",
            "",
            listOf(PlannedStep("scan_soko_inventory", "", "", "Soko Terminal", "Learn the catalogue")),
        )
        assertNull(error)
    }

    @Test fun neverRetriesExternalSideEffects() {
        assertTrue(!AutonomyRecoveryPolicy.canRetry("send_whatsapp"))
        assertTrue(!AutonomyRecoveryPolicy.canRetry("share_soko_studio_ad"))
        assertTrue(!AutonomyRecoveryPolicy.canRetry("post_whatsapp_status"))
        assertTrue(!AutonomyRecoveryPolicy.canRetry("post_tiktok"))
        assertTrue(!AutonomyRecoveryPolicy.canRetry("apply_soko_edit"))
    }

    @Test fun recoveryOnlyAllowsReadOnlyOrOwnerFacingActions() {
        assertTrue(AutonomyRecoveryPolicy.allowedInRecovery("open_app"))
        assertTrue(AutonomyRecoveryPolicy.allowedInRecovery("scan_soko_inventory"))
        assertTrue(!AutonomyRecoveryPolicy.allowedInRecovery("send_whatsapp"))
    }

    @Test fun trustedSokoSkillsDoNotGetContradictoryModelRecovery() {
        assertTrue(!AutonomyRecoveryPolicy.canReplanAfter("scan_soko_inventory"))
        assertTrue(!AutonomyRecoveryPolicy.canReplanAfter("scan_soko_bookings"))
        assertTrue(!AutonomyRecoveryPolicy.canReplanAfter("audit_soko_services"))
    }
}
