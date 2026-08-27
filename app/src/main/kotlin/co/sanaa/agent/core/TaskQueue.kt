package co.sanaa.agent.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TaskQueue {
    private val deviceActionLock = Mutex()
    suspend fun <T> withExclusiveDeviceAction(block: suspend () -> T): T = deviceActionLock.withLock {
        DeviceActivityMonitor.beginAutomation()
        try { block() } finally { DeviceActivityMonitor.endAutomation() }
    }
}
