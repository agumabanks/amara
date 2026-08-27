package co.sanaa.agent.services

import co.sanaa.agent.core.RuntimePhase
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentServiceRuntimeStatusTest {
    @Test
    fun legacyIdleWaitingStateRestoresAsBlockedNotReady() {
        assertEquals(
            RuntimePhase.BLOCKED,
            AgentService.durableRuntimePhase("idle", "Waiting for a safer, more specific instruction"),
        )
    }

    @Test
    fun explicitTerminalAndGenuineIdleStatesRemainTruthful() {
        assertEquals(RuntimePhase.FAILED, AgentService.durableRuntimePhase("failed", "Task failed"))
        assertEquals(RuntimePhase.COMPLETE, AgentService.durableRuntimePhase("completed", "Verified"))
        assertEquals(RuntimePhase.IDLE, AgentService.durableRuntimePhase("idle", "Ready for the next thing"))
    }
}
