package co.sanaa.agent.core

object NativeBridge {
    init { System.loadLibrary("sanaa_agent") }
    external fun parseMessage(rawTree: String): String
    external fun nextScheduledDelay(nowEpochMs: Long, targetEpochMs: Long): Long
}
