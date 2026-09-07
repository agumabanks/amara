package co.sanaa.agent.core.social

import co.sanaa.agent.core.*

object TikTokSocialPolicy {
    fun safeContext(text: String): Boolean = text.length >= 20 &&
        !PromptInjectionGuard.blocksSideEffects(PromptInjectionGuard.scan(TrustedContent.message(text)))
    fun validResponse(response: String, evidence: String, post: String, recent: List<String>): Boolean =
        response.isNotBlank() && response.length <= 150 && evidence.length >= 8 && post.contains(evidence) &&
        !Regex("(?i)(https?://|www\\.|follow.?for.?follow|follow me|check (my|our)|buy now|dm (me|us)|@[a-z0-9_])").containsMatchIn(response) &&
        !PromptInjectionGuard.blocksSideEffects(PromptInjectionGuard.scan(TrustedContent.message(response))) &&
        recent.none { ContentHashing.normalize(it)==ContentHashing.normalize(response) }
    fun parseCount(raw: String): Long? {
        val match=Regex("(?i)^([0-9]+(?:\\.[0-9]+)?)([km]?)$").matchEntire(raw.replace(",", "").trim()) ?: return null
        return (match.groupValues[1].toDouble() * when(match.groupValues[2].lowercase()) { "k"->1000; "m"->1000000; else->1 }).toLong()
    }
}
