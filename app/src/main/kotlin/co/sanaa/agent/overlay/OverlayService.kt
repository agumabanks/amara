package co.sanaa.agent.overlay

import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import co.sanaa.agent.R
import co.sanaa.agent.core.OverlayChipState
import co.sanaa.agent.core.RuntimePhase
import co.sanaa.agent.core.RuntimeStatusBus
import co.sanaa.agent.core.WorkStatus

/**
 * Observational status chip. It never intercepts touches while automation is acting,
 * never dismisses the keyguard, and exposes no on-chip emergency controls — stopping
 * happens exclusively through [requestStop], which the persistent notification action
 * calls; the service polls the flag on every tick.
 */
class OverlayService : Service() {
    private var overlayView: View? = null
    private val overlayLock = Any()
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pulseAnimator: ValueAnimator? = null
    private var pulseActive: Boolean = false
    private val busListener: (WorkStatus?) -> Unit = { renderLatest() }
    private var expanded = false
    internal var screenWidth = 0
        private set
    internal var screenHeight = 0
        private set
    private var snapMarginPx = 8
    private var density = 1f

    /** Test seams: prove teardown actually detached the listener and killed the tick loop. */
    internal var isBusListenerAttached: Boolean = false
        private set
    internal var isTickScheduled: Boolean = false
        private set
    /** Test seam: number of real window attach attempts — exactly one per service lifetime. */
    internal var addViewCalls: Int = 0
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        density = resources.displayMetrics.density
        snapMarginPx = (EDGE_MARGIN_DP * density).toInt()
        val size = Point()
        windowManager?.defaultDisplay?.getSize(size)
        screenWidth = size.x
        screenHeight = size.y
        RuntimeStatusBus.addListener(busListener)
        isBusListenerAttached = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureOverlay()
        startPolling()
        return START_STICKY
    }

    /** Single tick loop: honors a requested stop, then re-renders observational state. */
    private fun startPolling() {
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.post(tickRunnable)
        isTickScheduled = true
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            isTickScheduled = false
            if (consumeStopRequest()) {
                Log.i(TAG, "Stop requested via notification action flag")
                stopSelf()
                return
            }
            if (overlayView == null && canDrawOverlays()) ensureOverlay()
            renderLatest()
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
            isTickScheduled = true
        }
    }

    internal fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)

    private fun consumeStopRequest(): Boolean {
        val prefs = stopPrefs()
        if (!prefs.getBoolean(PREF_STOP_REQUESTED, false)) return false
        prefs.edit().putBoolean(PREF_STOP_REQUESTED, false).apply()
        return true
    }

    private fun stopPrefs(): SharedPreferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun ensureOverlay() {
        synchronized(overlayLock) {
            // Exactly-one guarantee: repeated starts/ticks can never create a second window.
            if (overlayView != null) return
            if (!canDrawOverlays()) return
            prepareLayoutParams()
            val view = buildOverlayView()
            try {
                addViewCalls++
                windowManager?.addView(view, params)
                overlayView = view
                // Align once the view has been measured (width is 0 straight after addView).
                mainHandler.postDelayed({ rightAlignChip() }, FIRST_LAYOUT_ALIGN_DELAY_MS)
                val container = view.findViewById<View>(R.id.overlay_card)
                container?.apply {
                    scaleX = 0f
                    scaleY = 0f
                    animate().scaleX(1f).scaleY(1f).setDuration(420)
                        .setInterpolator(OvershootInterpolator(1.2f)).start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "addView failed: ${e.message}")
                runCatching { windowManager?.removeViewImmediate(view) }
                overlayView = null
            }
        }
    }

    /**
     * Anchors the chip's right edge to the screen margin. The window width equals the
     * measured card when collapsed, so the chip can never hang off-screen or cover
     * target controls at the right edge (the defect behind the R1/R2 evidence).
     */
    internal fun rightAlignChip() {
        val view = overlayView ?: return
        val p = params ?: return
        val visibleContent = if (expanded) {
            view.findViewById<View>(R.id.overlay_expanded)
        } else {
            view.findViewById<View>(R.id.overlay_card)
        } ?: return
        val contentWidth = visibleContent.width
        if (contentWidth <= 0) return
        val targetX = (screenWidth - contentWidth - snapMarginPx).coerceAtLeast(snapMarginPx)
        // ColorOS otherwise keeps the FrameLayout's widest-ever child as a
        // transparent touch region after collapse. Match the window itself to
        // exactly the currently visible card/panel.
        if (p.x != targetX || p.width != contentWidth) {
            p.x = targetX
            p.width = contentWidth
            runCatching { windowManager?.updateViewLayout(view, p) }
        }
    }

    internal fun prepareLayoutParams() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            baseFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - snapMarginPx - chipWidthBudgetPx()
            y = (80 * density).toInt()
        }
    }

    internal fun buildOverlayView(): View {
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflater.inflate(R.layout.overlay_premium, null)
        // This is a status surface, not an automation control. Keep it permanently
        // non-interactive and reserve room for icon, margins and card padding so the
        // COMPLETE card (not merely its text) stays inside the width budget.
        val textBudget = chipTextWidthBudgetPx()
        view.findViewById<TextView>(R.id.overlay_phase)?.apply {
            maxWidth = textBudget
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        view.findViewById<TextView>(R.id.overlay_detail)?.apply {
            maxWidth = textBudget
            maxLines = 1
        }
        return view
    }

    /** Flags are pure so the acting/keyguard touch policy is unit-testable. */
    internal fun baseFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    /** The overlay is observational in every phase; it can never intercept app input. */
    internal fun flagsFor(base: Int, acting: Boolean): Int =
        base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

    internal fun currentLayoutParams(): WindowManager.LayoutParams? = params

    private fun applyActingFlags(acting: Boolean) {
        val p = params ?: return
        p.flags = flagsFor(p.flags, acting)
        val view = overlayView ?: return
        runCatching { windowManager?.updateViewLayout(view, p) }
            .onFailure { Log.w(TAG, "flag update failed: ${it.javaClass.simpleName}") }
    }

    private fun addGestureSupport(view: View) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        val touchSlop = (12 * density).toInt()

        view.setOnTouchListener { _, event ->
            // While automation acts the chip must be fully inert: no drag, no expansion.
            if (RuntimeStatusBus.isActing()) return@setOnTouchListener true
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                        moved = true
                        clampToEdges(initialX + dx, initialY + dy)?.let { clamped ->
                            params?.x = clamped.first
                            params?.y = clamped.second
                            runCatching { windowManager?.updateViewLayout(view, params) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleExpanded() else snapToEdge()
                    true
                }
                else -> false
            }
        }
    }

    /** Keeps the chip inside screen-edge margins; null while geometry is unknown. */
    internal fun clampToEdges(x: Int, y: Int): Pair<Int, Int>? {
        val view = overlayView ?: return null
        if (screenWidth <= 0 || screenHeight <= 0) return x to y
        val windowWidth = params?.width?.takeIf { it > 0 } ?: view.width
        val maxX = (screenWidth - windowWidth - snapMarginPx).coerceAtLeast(snapMarginPx)
        val maxY = (screenHeight - view.height - snapMarginPx).coerceAtLeast(snapMarginPx)
        return x.coerceIn(snapMarginPx, maxX) to y.coerceIn(snapMarginPx, maxY)
    }

    /** Hard budget keeping the chip under 30% of the screen width. */
    internal fun chipWidthBudgetPx(): Int =
        ((screenWidth * MAX_WIDTH_SCREEN_FRACTION).toInt())
            .coerceAtMost((MAX_WIDTH_DP * density).toInt())
            .coerceAtLeast(1)

    /** Text budget after the compact card's 42dp fixed visual footprint. */
    internal fun chipTextWidthBudgetPx(): Int =
        (chipWidthBudgetPx() - (CHIP_FIXED_WIDTH_DP * density).toInt()).coerceAtLeast(1)

    private fun snapToEdge() {
        val view = overlayView ?: return
        val p = params ?: return
        val windowWidth = p.width.takeIf { it > 0 } ?: view.width
        if (windowWidth <= 0) return
        val targetX = if (p.x + windowWidth / 2 > screenWidth / 2) {
            screenWidth - windowWidth - snapMarginPx
        } else {
            snapMarginPx
        }
        p.x = targetX
        runCatching { windowManager?.updateViewLayout(view, p) }
    }

    internal fun toggleExpanded() {
        val view = overlayView ?: return
        // Expansion is an owner-only convenience: never while automation works or acts,
        // never on the lock screen.
        if (RuntimeStatusBus.isActing() || keyguardLocked()) return
        val detailPanel = view.findViewById<LinearLayout>(R.id.overlay_expanded) ?: return
        expanded = !expanded
        // Let WindowManager remeasure the newly selected child before clamping
        // the final exact width in rightAlignChip().
        params?.let { p ->
            p.width = WindowManager.LayoutParams.WRAP_CONTENT
            overlayView?.let { view -> runCatching { windowManager?.updateViewLayout(view, p) } }
        }
        if (expanded) {
            detailPanel.visibility = View.VISIBLE
            detailPanel.alpha = 0f
            detailPanel.animate().alpha(1f).setDuration(200).start()
        } else {
            detailPanel.animate().cancel()
            detailPanel.visibility = View.GONE
        }
        // Window width changes with the panel: re-anchor the right edge.
        mainHandler.postDelayed({ rightAlignChip() }, FIRST_LAYOUT_ALIGN_DELAY_MS)
    }

    internal fun keyguardLocked(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager ?: return false
        return km.isKeyguardLocked
    }

    internal fun renderLatest() {
        // The chip is derived SOLELY from the canonical runtime state source: no
        // static default, no cached label, no stale status can produce "Ready"
        // while active/blocked/retrying/recovering work exists.
        render(RuntimeStatusBus.canonicalChipState(), keyguardLocked())
    }

    internal fun render(chip: OverlayChipState, keyguardLocked: Boolean) {
        val view = overlayView ?: return
        applyActingFlags(chip.acting)
        view.visibility = View.VISIBLE
        val working = chip.working || chip.acting
        val phaseView = view.findViewById<TextView>(R.id.overlay_phase) ?: return
        val detailView = view.findViewById<TextView>(R.id.overlay_detail) ?: return
        val card = view.findViewById<LinearLayout>(R.id.overlay_card) ?: return
        val pulse = view.findViewById<View>(R.id.overlay_pulse) ?: return
        val iconView = view.findViewById<ImageView>(R.id.overlay_icon) ?: return
        val expandedPanel = view.findViewById<LinearLayout>(R.id.overlay_expanded)
        val expandedPhase = view.findViewById<TextView>(R.id.overlay_expanded_phase)
        val expandedDetail = view.findViewById<TextView>(R.id.overlay_expanded_detail)

        // The label ALWAYS derives from the canonical state even on the lockscreen:
        // the phase labels are already generic single words, so keyguard redaction
        // blanks the DETAIL lines but can never swap in a static lie ("working"
        // while idle, or "ready" mid-work). Offline only occurs at rest by contract.
        val phaseLabel = if (chip.offline) OFFLINE_LABEL else phaseLabel(chip.phase)
        val compactDetail = if (keyguardLocked) "" else compactDetail(chip.status)

        // Exactly one compact chip during automation: the expanded detail panel can
        // never be visible while the agent works or acts — it collapses immediately.
        if ((working || chip.acting) && expanded) {
            expanded = false
            expandedPanel?.visibility = View.GONE
            mainHandler.postDelayed({ rightAlignChip() }, FIRST_LAYOUT_ALIGN_DELAY_MS)
        }

        phaseView.text = phaseLabel
        detailView.text = compactDetail
        expandedPhase?.text = phaseLabel
        expandedDetail?.text = if (keyguardLocked) "" else expandedDetailText(chip.status)
        if (keyguardLocked && expanded) {
            expanded = false
            expandedPanel?.visibility = View.GONE
        }
        detailView.visibility = if (compactDetail.isBlank()) View.GONE else View.VISIBLE

        val (bgColor, accentColor, iconRes) = paletteFor(chip.phase)
        val bg = GradientDrawable().apply {
            cornerRadius = 28f * density
            setColor(bgColor)
            setStroke((1.5f * density).toInt(), accentColor and 0x44FFFFFF.toInt())
        }
        card.background = bg
        iconView.setImageResource(iconRes)

        if (working) {
            card.alpha = 1f
            pulse.visibility = View.VISIBLE
            startPulse(pulse, accentColor)
        } else {
            card.alpha = 0.85f
            pulse.visibility = View.GONE
            stopPulse()
        }
    }

    internal fun phaseLabel(phase: RuntimePhase?): String = when (phase) {
        RuntimePhase.OBSERVE -> "Observing"
        RuntimePhase.THINK -> "Thinking"
        RuntimePhase.ACT -> "Working"
        RuntimePhase.VERIFY -> "Checking"
        RuntimePhase.RETRY -> "Retrying"
        RuntimePhase.RECOVER -> "Adapting"
        RuntimePhase.BLOCKED -> "Needs you"
        RuntimePhase.FAILED -> "Stalled"
        RuntimePhase.COMPLETE -> "Done"
        RuntimePhase.IDLE, null -> "Ready"
    }    /**
     * Compact single-line chip body: target app · step n/m · retry n · blocker word.
     * Labels only — never message bodies or contact details.
     */
    internal fun compactDetail(status: WorkStatus?): String {
        status ?: return ""
        val parts = mutableListOf<String>()
        status.targetApp?.takeIf { it.isNotBlank() }?.let { parts += it }
        if (status.stepCount > 0) parts += "step ${status.stepIndex + 1}/${status.stepCount}"
        if (status.retryCount > 0) parts += "retry ${status.retryCount}"
        status.blocker?.takeIf { it.isNotBlank() }?.let { parts += blockerWord(it) }
        return parts.joinToString(" · ")
    }

    /** Blockers render as one short word so the chip can never widen into the screen. */
    private fun blockerWord(blocker: String): String =
        blocker.trim().split(Regex("\\s+")).firstOrNull()?.take(BLOCKER_WORD_MAX) ?: ""

    /**
     * Expanded panel body: sanitized label pieces only — the task label truncated
     * hard and the blocker reduced to its single short word — so no message body,
     * number, credential, or raw diagnostic can ever surface on the chip.
     */
    private fun expandedDetailText(status: WorkStatus?): String {
        status ?: return ""
        return listOfNotNull(
            status.taskLabel.trim().take(TASK_LABEL_MAX).takeIf(String::isNotEmpty),
            status.blocker?.let(::blockerWord)?.takeIf(String::isNotEmpty),
        ).joinToString(" — ")
    }

    /**
     * Mission palette: ACT amber, BLOCKED/FAILED red, COMPLETE green, everything else
     * blue-gray. (bg color, accent color, icon.)
     */
    internal fun paletteFor(phase: RuntimePhase): Triple<Int, Int, Int> = when (phase) {
        RuntimePhase.ACT ->
            Triple(0xDD2F240D.toInt(), AMBER, R.drawable.ic_overlay_working)
        RuntimePhase.BLOCKED, RuntimePhase.FAILED ->
            Triple(0xDD2F0D0D.toInt(), RED, R.drawable.ic_overlay_adapting)
        RuntimePhase.COMPLETE ->
            Triple(0xDD0D2F1A.toInt(), GREEN, R.drawable.ic_overlay_ready)
        else ->
            Triple(0xDD111827.toInt(), BLUE_GRAY, R.drawable.ic_overlay_observing)
    }

    private fun startPulse(pulseView: View, color: Int) {
        pulseView.setBackgroundColor(color)
        pulseActive = true
        val animator = pulseAnimator ?: ValueAnimator.ofFloat(0.4f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { anim -> pulseView.alpha = anim.animatedValue as Float }
        }
        pulseAnimator = animator
        if (!animator.isRunning) animator.start()
    }

    private fun stopPulse() {
        pulseActive = false
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    /**
     * Truthful test/observation seam: whether the chip is currently pulsing.
     * Owned by this service rather than delegated to framework animator
     * internals, so it reads identically before and after looper frames run.
     */
    internal fun isPulseRunning(): Boolean = pulseActive && pulseAnimator != null

    /** Test seam: the exactly-one pulse animator currently attached, if any. */
    internal fun activePulseAnimator(): ValueAnimator? = pulseAnimator

    /** Test seam: the attached overlay root, or null before attach / after destroy. */
    internal fun currentOverlayViewForTest(): View? = overlayView

    /** Test seam: expanded-panel state for the exactly-one-compact-chip proofs. */
    internal fun isExpandedForTest(): Boolean = expanded

    override fun onDestroy() {
        RuntimeStatusBus.removeListener(busListener)
        isBusListenerAttached = false
        mainHandler.removeCallbacksAndMessages(null)
        isTickScheduled = false
        overlayView?.animate()?.cancel()
        pulseActive = false
        pulseAnimator?.cancel()
        pulseAnimator = null
        overlayView?.let { view ->
            runCatching { windowManager?.removeViewImmediate(view) }
                .onFailure { Log.w(TAG, "removeView failed: ${it.javaClass.simpleName}") }
        }
        overlayView = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SanaaOverlay"
        private const val PREFS = "co.sanaa.agent.overlay"
        private const val PREF_STOP_REQUESTED = "stop_requested"
        private const val EDGE_MARGIN_DP = 8
        private const val POLL_INTERVAL_MS = 400L
        private const val FIRST_LAYOUT_ALIGN_DELAY_MS = 60L
        private const val BLOCKER_WORD_MAX = 16
        private const val TASK_LABEL_MAX = 60
        private const val MAX_WIDTH_DP = 220
        private const val MAX_WIDTH_SCREEN_FRACTION = 0.28f
        private const val CHIP_FIXED_WIDTH_DP = 42
        private const val AMBER = 0xFFF59E0B.toInt()
        private const val RED = 0xFFF87171.toInt()
        private const val GREEN = 0xFF4ADE80.toInt()
        private const val BLUE_GRAY = 0xFF94A3B8.toInt()
        internal const val OFFLINE_LABEL = "Offline"

        /**
         * Emergency-stop entry point for the persistent notification action. Writes a
         * flag the running service observes within ~400ms; safe to call when the
         * service is not running (the flag is simply consumed on next start).
         */
        fun requestStop(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_STOP_REQUESTED, true).apply()
            Log.i("SanaaOverlay", "Emergency stop requested via notification action")
        }

        fun start(context: Context) {
            if (Settings.canDrawOverlays(context)) {
                context.startService(Intent(context, OverlayService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
