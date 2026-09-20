package co.sanaa.agent.core.work

object LearningOutcomePolicy {
    fun countsAsExecution(result: WorkResult): Boolean {
        if(result.status==WorkStatus.SKIPPED) return false
        if(result.item.kind!=WorkKind.TIKTOK_COMMENT_REPLY || result.failure!=null) return true
        return result.outcomeFacts.any { fact ->
            runCatching { org.json.JSONObject(fact).optString("interaction_outcome")=="VERIFIED" }.getOrDefault(false) ||
                fact=="Own-ad comment reply: VERIFIED"
        }
    }
}
