package co.sanaa.agent.core

/**
 * Read-only snapshot of what currently owns the foreground, as observed through the
 * accessibility root window. [packageName] is null when no window is observable.
 * [protectedScreenKind] is one of "otp" | "captcha" | "biometric" | "account_security" |
 * "secure_keyguard", or null when the surface is not owner-protected.
 */
data class ForegroundObservation(
    val packageName: String?,
    val activityName: String?,
    val isLauncher: Boolean,
    val obstructingDialogDetected: Boolean,
    val protectedScreenKind: String? = null,
    val targetInForeground: Boolean = false,
)

enum class RecoveryStep { DISMISS_SAFE_DIALOG, GLOBAL_BACK, GLOBAL_HOME, LAUNCH_TARGET, HANDOFF_OWNER, DONE }

/**
 * Pure, JVM-testable decision table for foreground recovery. It plans steps only;
 * execution lives in AccessibilityActions.recoverToTarget inside a transaction.
 *
 * Decision table (first match wins):
 *  1. target already in foreground            -> [DONE]
 *  2. protected screen (OTP/captcha/biometric/account_security/secure_keyguard)
 *     -> [HANDOFF_OWNER] — takes priority over dialog dismissal; automation never
 *        interacts with these surfaces
 *  3. obstructing dialog detected             -> [DISMISS_SAFE_DIALOG, GLOBAL_BACK, LAUNCH_TARGET]
 *  4. launcher or any other app               -> maxBackActions x GLOBAL_BACK, then
 *     GLOBAL_HOME, then LAUNCH_TARGET (repeats of GLOBAL_BACK allowed)
 *
 * Every plan except DONE/HANDOFF_OWNER ends with LAUNCH_TARGET.
 */
object ForegroundRecoveryPlanner {

    fun planRecoverySteps(obs: ForegroundObservation, maxBackActions: Int = 2): List<RecoveryStep> {
        if (obs.targetInForeground) return listOf(RecoveryStep.DONE)
        if (obs.protectedScreenKind != null) return listOf(RecoveryStep.HANDOFF_OWNER)
        if (obs.obstructingDialogDetected) {
            return listOf(RecoveryStep.DISMISS_SAFE_DIALOG, RecoveryStep.GLOBAL_BACK, RecoveryStep.LAUNCH_TARGET)
        }
        // Launcher, unknown package, or any other foreground app: navigate away with
        // bounded BACK presses, fall back to HOME, then launch the allowlisted target.
        return buildList {
            repeat(maxBackActions.coerceAtLeast(0)) { add(RecoveryStep.GLOBAL_BACK) }
            add(RecoveryStep.GLOBAL_HOME)
            add(RecoveryStep.LAUNCH_TARGET)
        }
    }
}
