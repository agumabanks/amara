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
            // Group speakers are tracked inside the group; the original notification
            // message time (not observation time) is kept as the event's original time.
            runtime.verifiedConversationKey(entry.id)?.let { scopedKey ->
                if(runtime.groupSettings.observe(entry.id,inbound.signature,observedAt))
                    runtime.chatStore.storeMessage(scopedKey,inbound.sender,"received",inbound.message,
                        originalAt = inbound.messageTimestamp.takeIf { it>0 } ?: observedAt)
            }
            if(!runtime.groupSettings.allows(entry,"reply") || !runtime.groupSettings.acceptsReply(entry,inbound.message)) return
        }
        if (!runtime.config.whatsAppAutomationEnabled || !runtime.config.whatsAppInboundEnabled) return
        if (inbound.isGroup && !runtime.config.whatsAppGroupsEnabled) return
        // WhatsApp's own transport/status notifications are not customer messages,
        // and an unresolved target must never become an autonomous send target.
        if (inbound.target.isBlank() || inbound.target.equals("WhatsApp", true) ||
            inbound.target.equals("Unknown", true) || inbound.message.startsWith("Sending ", true) ||
            (!inbound.isMissedCall && WhatsAppNotificationParser.isNonConversationalEvent(inbound.message))) return
        // An observed scheduling phrase is a candidate only. Neither a proposed
        // time nor an AI reply is evidence that both people agreed to a meeting.
        if (!inbound.isGroup && Regex("\\b(meet|meeting|appointment|callback|call me)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(inbound.message)) {
            runtime.verifiedConversationKey(inbound.conversationIdentity.ifBlank { inbound.target })?.let { key ->
                runCatching {
                    co.sanaa.agent.core.CommitmentStore(runtime.applicationContext).use { store ->
                        val id = "wa-candidate:${ContentHashing.hash(inbound.signature)}"
                        if (store.byId(id) == null) store.upsert(co.sanaa.agent.core.Commitment(
                            id = id, conversationKey = key,
                            participants = org.json.JSONArray().put(org.json.JSONObject()
                                .put("name", inbound.sender).put("target", "")),
                            type = "meeting", subject = inbound.message.take(180), agreedAt = 0,
                            timezone = "", originalPhrase = inbound.message.take(300),
                            sourceMessages = org.json.JSONArray().put(inbound.signature),
                            confirmationStatus = co.sanaa.agent.core.CommitmentStore.Confirmation.CLARIFICATION_NEEDED,
                            location = "", link = "", nextAction = "Confirm date, timezone, participant and agreement",
                            reminderLeadMinutes = 5, revision = 0, reminderRecipients = "owner",
                            createdAt = 0, updatedAt = 0,
                        ), "Observed possible scheduling request; not confirmed")
                    }
                }
            }
        }
        val offered=runtime.workQueue.offer(
            WorkItem(
                dedupeKey = "wa-inbound:${ContentHashing.hash(inbound.signature)}",
                domain = Domain.WHATSAPP,
                kind = WorkKind.WA_REPLY_INBOUND,
                payload = JSONObject()
                    .put("inbound", true)
                    .put("sender", inbound.sender)
                    .put("sender_phone", inbound.senderPhone)
                    .put("manager_command_candidate", !inbound.isGroup && !inbound.isMissedCall && runtime.actions.isManagerCandidate(runtime.config.managerWhatsApp,inbound.sender,inbound.senderPhone))
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
