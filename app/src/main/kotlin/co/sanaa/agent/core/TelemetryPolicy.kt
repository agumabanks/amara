package co.sanaa.agent.core

/**
 * Pure, testable telemetry governance (Phase A6).
 * Nothing leaves the device unless the owner explicitly opted in, and every
 * exported string passes redaction first.
 */
object TelemetryPolicy {

    data class ExportDecision(val allowed: Boolean, val payload: String?, val reason: String)

    fun gate(optIn: Boolean, payload: String): ExportDecision = when {
        !optIn -> ExportDecision(false, null, "Telemetry is not opted in; payload stayed on the device.")
        else -> ExportDecision(true, Redactor.redactForExport(payload), "Opted-in export after redaction.")
    }

    fun gateError(optIn: Boolean, error: String?): ExportDecision = when {
        !optIn -> ExportDecision(false, null, "Telemetry is not opted in; error detail stayed on the device.")
        else -> ExportDecision(true, error?.let(Redactor::redactForExport), "Opted-in error export after redaction.")
    }
}
