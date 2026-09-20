package co.sanaa.agent.core.work

import co.sanaa.agent.core.AgentRuntime
import co.sanaa.agent.core.ContentHashing
import co.sanaa.agent.modules.WhatsAppInbound
import co.sanaa.agent.modules.WhatsAppNotificationParser
import org.json.JSONObject

/** Converts an observed notification into durable work; it never executes the reply. */
object InboundWorkProposer {
    fun offerWhatsApp(runtime: AgentRuntime, inbound: WhatsAppInbound, observedAt: Long = System.currentTimeMillis()) {
        runtime.evaluation.record("inbound_observed", "wa-inbound:${ContentHashing.hash(inbound.signature)}",
            JSONObject().put("observed_at", observedAt).put("is_group", inbound.isGroup)
                .put("automation_enabled", runtime.config.whatsAppAutomationEnabled && runtime.config.whatsAppInboundEnabled))
        // Discover group identities even while replies are off, so the owner can
        // find and configure them. Listening does not imply permission to reply.
        if(inbound.isGroup && inbound.conversationIdentity.isNotBlank()) {
            val directory=co.sanaa.agent.core.ContactDirectoryProvider.instance
            var entry=directory?.byId(inbound.conversationIdentity)
            if(entry==null && directory!=null) entry=directory.upsert(co.sanaa.agent.core.DirectoryEntry(
                inbound.conversationIdentity,inbound.target,null,emptySet(),true,
                co.sanaa.agent.core.EntrySource.WHATSAPP,System.currentTimeMillis(),co.sanaa.agent.core.Ambiguity.UNIQUE,
                co.sanaa.agent.core.Classification.UNKNOWN,co.sanaa.agent.core.CommercialConsent.UNKNOWN,emptyMap(),
                "WhatsApp notification origin",null))
            if(entry==null || !runtime.groupSettings.allows(entry,"listen")) return
            if(runtime.groupSettings.observe(entry.id,inbound.signature,observedAt))
                runtime.chatStore.storeMessage(entry.id,inbound.sender,"received",inbound.message)
            if(!runtime.groupSettings.allows(entry,"reply") || !runtime.groupSettings.acceptsReply(entry,inbound.message)) return
        }
        if (!runtime.config.whatsAppAutomationEnabled || !runtime.config.whatsAppInboundEnabled) return
        if (inbound.isGroup && !runtime.config.whatsAppGroupsEnabled) return
        // WhatsApp's own transport/status notifications are not customer messages,
        // and an unresolved target must never become an autonomous send target.
        if (inbound.target.isBlank() || inbound.target.equals("WhatsApp", true) ||
            inbound.target.equals("Unknown", true) || inbound.message.startsWith("Sending ", true) ||
            (!inbound.isMissedCall && WhatsAppNotificationParser.isNonConversationalEvent(inbound.message))) return
        val offered=runtime.workQueue.offer(
            WorkItem(
                dedupeKey = "wa-inbound:${ContentHashing.hash(inbound.signature)}",
                domain = Domain.WHATSAPP,
                kind = WorkKind.WA_REPLY_INBOUND,
                payload = JSONObject()
                    .put("inbound", true)
                    .put("sender", inbound.sender)
                    .put("manager_command_candidate", !inbound.isGroup && runtime.actions.isManagerCandidate(runtime.config.managerWhatsApp,inbound.sender,inbound.senderPhone))
                    .put("message", inbound.message)
                    .put("conversation", inbound.conversation)
                    .put("conversation_identity", inbound.conversationIdentity)
                    .put("is_group", inbound.isGroup)
                    .put("is_missed_call", inbound.isMissedCall)
                    .put("trusted_whatsapp_notification", true)
                    .put("inbound_observed_at", observedAt)
                    .put("inbound_message_at", inbound.messageTimestamp)
                    .put("owner_takeover_at", observedAt + 5_000L)
                    .put("owner_always_on", runtime.config.whatsAppAlwaysOn),
                baseValueKes = WorkScorer.defaultBaseValue(WorkKind.WA_REPLY_INBOUND),
                urgencyHalfLifeHours = WorkScorer.defaultHalfLife(WorkKind.WA_REPLY_INBOUND),
                estimatedScreenSeconds = WorkScorer.defaultScreenSeconds(WorkKind.WA_REPLY_INBOUND),
                requires = setOf(Capability.SCREEN, Capability.NETWORK, Capability.GROQ, Capability.CONSENT_TIER_2),
                riskTier = WorkScorer.defaultRiskTier(WorkKind.WA_REPLY_INBOUND),
            ),
        )
        if(offered==WorkQueue.OfferResult.REJECTED_CAP) {
            runtime.evaluation.record("inbound_queue_rejected", "wa-inbound:${ContentHashing.hash(inbound.signature)}",
                JSONObject().put("reason", "queue_capacity").put("observed_at", observedAt))
            runtime.workBlockers.flag("inbound:queue_capacity", "WhatsApp auto-reply",
                "The work queue is full; a received message has no scheduled reply.", ownerAction=true,
                action="Review unanswered chats and queued work. Free queue space does not confirm those messages were answered.")
        }
        runtime.workLoop.wake(WakeReason.WhatsAppNotification(inbound.sender, inbound.message))
    }
}
