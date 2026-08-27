package co.sanaa.agent.core

import android.content.Context

data class CommandResult(
    val success: Boolean,
    val status: String,
    val message: String,
    val observation: String = "",
    val analysis: String = "",
    val steps: List<Map<String, Any>> = emptyList(),
) {
    fun asMap(): Map<String, Any> = mapOf(
        "success" to success,
        "status" to status,
        "message" to message,
        "observation" to observation,
        "analysis" to analysis,
        "steps" to steps,
    )
}

/** Entry point used by Flutter and WorkManager. */
class CommandExecutor(context: Context) {
    private val controller = AutonomyController(context)
    suspend fun execute(command: String, contactName: String, contactPhone: String): CommandResult =
        controller.execute(command, contactName, contactPhone)
}
