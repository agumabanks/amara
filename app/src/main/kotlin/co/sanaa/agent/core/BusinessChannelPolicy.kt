package co.sanaa.agent.core

/** Narrow channel admission; dispatch wrappers still prove recipient and content on screen. */
internal object BusinessChannelPolicy {
    fun managerBlocker(configuredTarget: String, inputs: Map<String, Any?>): String? =
        if (configuredTarget.isBlank() || inputs["target"] != configuredTarget || inputs["manager_report"] != true)
            "Manager notification is not bound to the configured owner destination" else null

    fun shopBlocker(capability: String, inputs: Map<String, Any?>, scope: String): String? = when {
        scope.isBlank() || inputs["shop_scope"] != scope -> "This action is not bound to the currently logged-in Terminal shop. Old drafts and unscoped work remain held."
        capability !in setOf(CapabilityIds.POST_YOUTUBE_SHORT, CapabilityIds.POST_TIKTOK, CapabilityIds.POST_TIKTOK_STORY, CapabilityIds.BROADCAST_GROUP_WHATSAPP, CapabilityIds.REPLY_WHATSAPP, CapabilityIds.APPLY_SOKO_EDIT) ->
            "This channel needs shop-scoped memory and target verification before it can resume."
        else -> null
    }
}
