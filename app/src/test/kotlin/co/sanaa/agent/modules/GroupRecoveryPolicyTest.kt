package co.sanaa.agent.modules

import org.junit.Assert.*
import org.junit.Test

class GroupRecoveryPolicyTest {
    @Test fun onlyDurableNoEffectReceiptsAllowRecovery() {
        for(state in listOf("NOT_STARTED","CANCELLED","EXPIRED","FAILED")) {
            assertTrue(GroupRecoveryPolicy.canRetry("attempt-1",state,false))
            assertFalse(GroupRecoveryPolicy.canRetry("attempt-1",state,true))
            assertFalse(GroupRecoveryPolicy.canRetry("",state,false))
        }
        for(state in listOf("UNKNOWN","CLAIMED","ACTING","VERIFICATION_PENDING","UNCERTAIN","VERIFIED"))
            assertFalse(GroupRecoveryPolicy.canRetry("attempt-1",state,false))
    }
}
