package co.sanaa.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision-table coverage for the pure recovery planner. No Android classes involved.
 */
class ForegroundRecoveryPlannerTest {

    private fun obs(
        packageName: String? = "com.other.app",
        activityName: String? = "com.other.app.MainActivity",
        isLauncher: Boolean = false,
        dialog: Boolean = false,
        protectedKind: String? = null,
        targetInForeground: Boolean = false,
    ) = ForegroundObservation(
        packageName = packageName,
        activityName = activityName,
        isLauncher = isLauncher,
        obstructingDialogDetected = dialog,
        protectedScreenKind = protectedKind,
        targetInForeground = targetInForeground,
    )

    @Test
    fun targetAlreadyInForegroundPlansNothingButDone() {
        val plan = ForegroundRecoveryPlanner.planRecoverySteps(obs(targetInForeground = true))
        assertEquals(listOf(RecoveryStep.DONE), plan)
    }

    @Test
    fun everyProtectedKindRoutesToOwnerHandoff() {
        for (kind in listOf("otp", "captcha", "biometric", "account_security", "secure_keyguard")) {
            val plan = ForegroundRecoveryPlanner.planRecoverySteps(
                obs(dialog = true, protectedKind = kind),
                maxBackActions = 2,
            )
            assertEquals("kind=$kind", listOf(RecoveryStep.HANDOFF_OWNER), plan)
        }
    }

    @Test
    fun protectedHandoffTakesPriorityOverSafeDialogDismissal() {
        // An OTP surface behind a tappable OK button must NEVER be tapped.
        val plan = ForegroundRecoveryPlanner.planRecoverySteps(
            obs(dialog = true, protectedKind = "otp"),
        )
        assertEquals(listOf(RecoveryStep.HANDOFF_OWNER), plan)
        assertTrue(RecoveryStep.DISMISS_SAFE_DIALOG !in plan)
    }

    @Test
    fun obstructingDialogPlansDismissThenBackThenLaunch() {
        val plan = ForegroundRecoveryPlanner.planRecoverySteps(
            obs(packageName = "com.some.game", dialog = true),
            maxBackActions = 2,
        )
        assertEquals(
            listOf(RecoveryStep.DISMISS_SAFE_DIALOG, RecoveryStep.GLOBAL_BACK, RecoveryStep.LAUNCH_TARGET),
            plan,
        )
    }

    @Test
    fun launcherOrUnknownAppGetsBackHomeLaunch() {
        for (o in listOf(obs(isLauncher = true), obs(packageName = null), obs(packageName = "com.unknown"))) {
            val plan = ForegroundRecoveryPlanner.planRecoverySteps(o, maxBackActions = 2)
            assertEquals(
                listOf(RecoveryStep.GLOBAL_BACK, RecoveryStep.GLOBAL_BACK, RecoveryStep.GLOBAL_HOME, RecoveryStep.LAUNCH_TARGET),
                plan,
            )
        }
    }

    @Test
    fun zeroBackActionsSkipsStraightToHomeThenLaunch() {
        val plan = ForegroundRecoveryPlanner.planRecoverySteps(obs(), maxBackActions = 0)
        assertEquals(listOf(RecoveryStep.GLOBAL_HOME, RecoveryStep.LAUNCH_TARGET), plan)
    }

    @Test
    fun backActionRepeatsScaleWithTheRequestedBudget() {
        val plan = ForegroundRecoveryPlanner.planRecoverySteps(obs(), maxBackActions = 3)
        assertEquals(
            listOf(
                RecoveryStep.GLOBAL_BACK, RecoveryStep.GLOBAL_BACK, RecoveryStep.GLOBAL_BACK,
                RecoveryStep.GLOBAL_HOME, RecoveryStep.LAUNCH_TARGET,
            ),
            plan,
        )
    }

    @Test
    fun negativeBackBudgetStillEndsWithAValidPlan() {
        val plan = ForegroundRecoveryPlanner.planRecoverySteps(obs(), maxBackActions = -4)
        assertEquals(listOf(RecoveryStep.GLOBAL_HOME, RecoveryStep.LAUNCH_TARGET), plan)
    }

    @Test
    fun everyPlanExceptDoneAndHandoffEndsWithLaunchTarget() {
        val variants = listOf(
            obs(targetInForeground = true),
            obs(protectedKind = "biometric"),
            obs(dialog = true),
            obs(isLauncher = true),
            obs(),
        )
        for (o in variants) {
            val plan = ForegroundRecoveryPlanner.planRecoverySteps(o, maxBackActions = 1)
            when (plan.singleOrNull()) {
                RecoveryStep.DONE, RecoveryStep.HANDOFF_OWNER -> Unit
                else -> assertEquals("obs=$o", RecoveryStep.LAUNCH_TARGET, plan.last())
            }
        }
    }
}
