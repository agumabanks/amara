package co.sanaa.agent.modules

import co.sanaa.agent.core.work.WorkStatus
import co.sanaa.agent.core.work.FailureClass
import co.sanaa.agent.core.work.FailureInfo

/** A failed read is not evidence that the feed contains no relevant posts. */
internal object TikTokCommunityObservation {
    fun blocker(observed: Int, outcome: String): String? =
        if (observed == 0 && outcome == "NO_RELEVANT_POST")
            "TikTok post captions could not be read; community discovery is unverified"
        else null

    fun status(outcome: String, blocked: String): WorkStatus = when {
        blocked == "Public interactions are disabled" -> WorkStatus.SKIPPED
        blocked.isNotBlank() -> WorkStatus.FAILED
        outcome in setOf("NO_RELEVANT_POST", "YIELDED_TO_PRIORITY_WORK") -> WorkStatus.SKIPPED
        outcome == "MODEL_DEFERRED" -> WorkStatus.SKIPPED
        outcome == "UNCERTAIN" -> WorkStatus.ESCALATED
        outcome == "FAILED" -> WorkStatus.PARTIAL
        outcome == "VERIFIED" -> WorkStatus.DONE
        else -> WorkStatus.FAILED
    }

    fun failure(outcome: String, blocked: String): FailureInfo? = when {
        blocked == "Public interactions are disabled" -> null
        blocked.isNotBlank() -> FailureInfo(FailureClass.UI_MISMATCH, blocked, true)
        outcome == "MODEL_DEFERRED" -> FailureInfo(FailureClass.TRANSIENT_NETWORK,
            "Community decision service unavailable; no comment dispatched", true, retryAfterMs = 5 * 60_000L)
        outcome in setOf("VERIFIED", "NO_RELEVANT_POST", "YIELDED_TO_PRIORITY_WORK") -> null
        else -> FailureInfo(FailureClass.UNKNOWN, "TikTok interaction: $outcome", false)
    }
}
