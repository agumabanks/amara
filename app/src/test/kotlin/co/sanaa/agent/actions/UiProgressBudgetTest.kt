package co.sanaa.agent.actions

import org.junit.Assert.*
import org.junit.Test

class UiProgressBudgetTest {
    @Test fun newProgressExtendsIdleWaitButRepeatedOrOscillatingScreensDoNot() {
        var time = 0L
        val budget = UiProgressBudget(10, 40) { time }
        budget.progress("upload:10")
        time = 9; budget.progress("upload:20")
        time = 18; assertFalse(budget.expired())
        budget.progress("upload:10")
        budget.progress("upload:20")
        time = 19; assertTrue(budget.expired())
    }
    @Test fun progressingTaskStillHasAHardMaximum() {
        var time = 0L
        val budget = UiProgressBudget(10, 30) { time }
        for (n in 1..4) { time = n * 7L; budget.progress("upload:$n") }
        time = 30; assertTrue(budget.expired())
    }
}
