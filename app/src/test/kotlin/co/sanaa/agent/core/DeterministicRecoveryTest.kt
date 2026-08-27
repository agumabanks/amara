package co.sanaa.agent.core

import co.sanaa.agent.actions.WhatsAppScreenSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicRecoveryTest {
    @Test fun sokoTerminalSignedOutReturnsUnsupportedStep() {
        val snapshot = WhatsAppScreenSnapshot(
            "com.soko24.soko_seller_terminal",
            listOf("Enter your phone number", "We'll check if you already have an account", "Continue"),
            "sig",
        )
        val plan = AutonomyController.DeterministicRecovery.plan(
            PlannedStep("scan_soko_inventory", "", "", "Soko Terminal", "Read products"),
            snapshot,
        )
        assertTrue(plan.isNotEmpty())
        assertEquals("unsupported", plan.first().action)
    }

    @Test fun sokoTerminalSessionExpiredReturnsUnsupportedStep() {
        val snapshot = WhatsAppScreenSnapshot(
            "com.soko24.soko_seller_terminal",
            listOf("Session expired", "signing you out"),
            "sig",
        )
        val plan = AutonomyController.DeterministicRecovery.plan(
            PlannedStep("scan_soko_bookings", "", "", "Soko Terminal", "Read bookings"),
            snapshot,
        )
        assertTrue(plan.isNotEmpty())
        assertEquals("unsupported", plan.first().action)
    }

    @Test fun sokoTerminalStaffLoginReturnsReopenStep() {
        val snapshot = WhatsAppScreenSnapshot(
            "com.soko24.soko_seller_terminal",
            listOf("Staff Login", "Enter PIN"),
            "sig",
        )
        val plan = AutonomyController.DeterministicRecovery.plan(
            PlannedStep("scan_soko_inventory", "", "", "Soko Terminal", "Read products"),
            snapshot,
        )
        assertTrue(plan.isNotEmpty())
        assertEquals("open_app", plan.first().action)
    }

    @Test fun whatsappObstructionReturnsReopenStep() {
        val snapshot = WhatsAppScreenSnapshot(
            "com.whatsapp",
            listOf("Set up your secret code", "Welcome to WhatsApp"),
            "sig",
        )
        val plan = AutonomyController.DeterministicRecovery.plan(
            PlannedStep("read_screen", "", "", "WhatsApp", "Read WhatsApp screen"),
            snapshot,
        )
        assertTrue(plan.isNotEmpty())
        assertEquals("open_app", plan.first().action)
    }

    @Test fun unknownScreenReturnsEmptyPlan() {
        val snapshot = WhatsAppScreenSnapshot(
            "com.unknown.app",
            listOf("Some random screen"),
            "sig",
        )
        val plan = AutonomyController.DeterministicRecovery.plan(
            PlannedStep("read_screen", "", "", "Unknown", "Read screen"),
            snapshot,
        )
        assertTrue(plan.isEmpty())
    }

    @Test fun networkErrorReturnsWaitAndReobserve() {
        val snapshot = WhatsAppScreenSnapshot(
            "com.android.settings",
            listOf("No internet connection", "Please try again"),
            "sig",
        )
        val plan = AutonomyController.DeterministicRecovery.plan(
            PlannedStep("read_screen", "", "", "device", "Read screen"),
            snapshot,
        )
        assertTrue(plan.isNotEmpty())
        assertEquals("wait", plan.first().action)
    }
}
