package co.sanaa.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HumanPacingTest {
    @Test fun everyInteractionKindHasAHumanScaleBoundedDelay() {
        InteractionKind.entries.forEach { kind ->
            val value = HumanPacing.delayMillis(kind) { from, _ -> from }
            assertTrue(value >= 200)
            assertTrue(value <= 2_200)
        }
    }

    @Test fun injectedJitterCannotEscapeThePolicyRange() {
        assertEquals(480L, HumanPacing.delayMillis(InteractionKind.TAP_SETTLE) { _, _ -> Long.MAX_VALUE })
    }
}
