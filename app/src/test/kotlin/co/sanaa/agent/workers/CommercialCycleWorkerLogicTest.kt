package co.sanaa.agent.workers

import co.sanaa.agent.core.commerce.DailyCommercialCycle
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommercialCycleWorkerLogicTest {

    @Test
    fun `morning window is six inclusive to eleven exclusive`() {
        assertEquals(CommercialCycleWorker.CommercialPhase.SIGNAL_PASS, CommercialCycleWorker.phaseAt(LocalTime.MIDNIGHT))
        assertEquals(CommercialCycleWorker.CommercialPhase.SIGNAL_PASS, CommercialCycleWorker.phaseAt(LocalTime.of(5, 59, 59)))
        assertEquals(CommercialCycleWorker.CommercialPhase.MORNING, CommercialCycleWorker.phaseAt(LocalTime.of(6, 0)))
        assertEquals(CommercialCycleWorker.CommercialPhase.MORNING, CommercialCycleWorker.phaseAt(LocalTime.of(10, 59, 59)))
        assertEquals(CommercialCycleWorker.CommercialPhase.SIGNAL_PASS, CommercialCycleWorker.phaseAt(LocalTime.of(11, 0)))
        assertEquals(CommercialCycleWorker.CommercialPhase.END_OF_DAY, CommercialCycleWorker.phaseAt(LocalTime.of(18, 0)))
    }

    @Test
    fun `health never inherits optimistic composition defaults without live proof`() {
        val optimistic = DailyCommercialCycle.HealthSignals(true, true, true)
        val missingProof = CommercialCycleWorker.failClosedHealth(
            optimistic, accessibilityReady = true, whatsappInstalled = true, recentSokoRead = false,
        )

        assertTrue(missingProof.deviceSessionHealthy)
        assertTrue(missingProof.whatsappChannelHealthy)
        assertFalse(missingProof.sokoReachable)
        assertTrue(missingProof.blockers.any { "Soko inventory" in it })
    }

    @Test
    fun `accessibility outage blocks every live commercial channel`() {
        val health = CommercialCycleWorker.failClosedHealth(
            DailyCommercialCycle.HealthSignals(true, true, true),
            accessibilityReady = false, whatsappInstalled = true, recentSokoRead = true,
        )

        assertFalse(health.deviceSessionHealthy)
        assertFalse(health.sokoReachable)
        assertFalse(health.whatsappChannelHealthy)
    }

    @Test
    fun `base stop state cannot be widened by healthy device observations`() {
        val health = CommercialCycleWorker.failClosedHealth(
            DailyCommercialCycle.HealthSignals(true, true, false, listOf("open escalation stop-state is active")),
            accessibilityReady = true, whatsappInstalled = true, recentSokoRead = true,
        )

        assertFalse(health.whatsappChannelHealthy)
        assertTrue(health.blockers.contains("open escalation stop-state is active"))
    }

    @Test
    fun `snapshot inventory contains only distinct owner allowed observed products`() {
        assertEquals(
            listOf("Product A", "Product B"),
            CommercialCycleWorker.provenInventory(
                listOf(" Product A ", "", "Unapproved", "Product A", "Product B"),
                setOf("Product A", "Product B"),
            ),
        )
        assertTrue(CommercialCycleWorker.provenInventory(listOf("Observed"), emptySet()).isEmpty())
    }
}
