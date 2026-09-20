package co.sanaa.agent.core.work

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class LearningOutcomePolicyTest {
    private val item=WorkItem("social",Domain.TIKTOK,WorkKind.TIKTOK_COMMENT_REPLY,baseValueKes=1.0,urgencyHalfLifeHours=1.0,estimatedScreenSeconds=1)
    @Test fun emptyScanAndYieldDoNotTeachCommentSuccess() {
        for(outcome in listOf("NO_RELEVANT_POST","YIELDED_TO_PRIORITY_WORK","READ_ONLY_RECONCILIATION"))
            assertFalse(LearningOutcomePolicy.countsAsExecution(WorkResult(item,WorkStatus.DONE,outcomeFacts=listOf("{\"interaction_outcome\":\"$outcome\"}"))))
        assertTrue(LearningOutcomePolicy.countsAsExecution(WorkResult(item,WorkStatus.DONE,outcomeFacts=listOf("{\"interaction_outcome\":\"VERIFIED\"}"))))
        assertTrue(LearningOutcomePolicy.countsAsExecution(WorkResult(item,WorkStatus.FAILED,failure=FailureInfo(FailureClass.UI_MISMATCH,"Profile unavailable"))))
    }
}
