package co.sanaa.agent.core.work

import co.sanaa.agent.core.ContentHashing
import org.json.JSONObject

object ManagerReportWork {
    fun blockerMessage(blockers: List<String>, recoveryRequested: Boolean = false): String {
        val issues = blockers.map(String::trim).filter(String::isNotEmpty).distinct()
        return buildString {
            append("Amara update — I need help with ${issues.size} ${if (issues.size == 1) "issue" else "issues"}.\n")
            issues.take(4).forEach { append("• ").append(it.take(220)).append('\n') }
            if (issues.size > 4) append("Plus ").append(issues.size - 4).append(" other issues in Amara Doctor.\n")
            if (recoveryRequested) append("I requested a background recovery check; the issues above still need verification.\n")
            val next = when {
                issues.any { it.contains("battery", true) && (it.contains("low", true) || it.contains("pauses", true)) } -> "Please connect the phone to power."
                issues.any { it.contains("timezone", true) } -> "Please set the business timezone in Commercial policy."
                issues.any { it.contains("phone control", true) || it.contains("Accessibility", true) } -> "Please restore Amara's phone access in Android Settings."
                issues.any { it.contains("internet", true) } -> "Please check the phone's internet connection."
                else -> "Open Amara → Doctor for the next step on each issue."
            }
            append("Next: $next\n")
            append("Other eligible tasks can continue. Reply with the task or information you want me to use; I will verify your sender identity before acting. ")
            append("Waiting tasks are not confirmed deliveries.")
        }
    }

    fun from(target: String, source: String, body: String): WorkItem? {
        if(target.isBlank() || source.isBlank() || body.isBlank()) return null
        return WorkItem(dedupeKey="manager-report:${ContentHashing.hash("$target:$source")}",domain=Domain.WHATSAPP,
            kind=WorkKind.WA_REPLY_INBOUND,payload=JSONObject().put("manager_report",true).put("target",target).put("message",co.sanaa.agent.core.Redactor.redact(body).take(1600)),
            baseValueKes=260.0,urgencyHalfLifeHours=1.0,estimatedScreenSeconds=45,
            requires=setOf(Capability.SCREEN,Capability.NETWORK),riskTier=RiskTier.MEDIUM)
    }
}
