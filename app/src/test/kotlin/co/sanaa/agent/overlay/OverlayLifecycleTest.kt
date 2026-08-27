package co.sanaa.agent.overlay

import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import co.sanaa.agent.R
import co.sanaa.agent.core.RuntimePhase
import co.sanaa.agent.core.RuntimeStatusBus
import co.sanaa.agent.core.WorkStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OverlayLifecycleTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ShadowSettings.setCanDrawOverlays(true)
        RuntimeStatusBus.clear(WORKER_ID)
        RuntimeStatusBus.setProviderOffline(false)
        RuntimeStatusBus.endActing()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        RuntimeStatusBus.clear(WORKER_ID)
        RuntimeStatusBus.setProviderOffline(false)
        RuntimeStatusBus.endActing()
    }

    private fun status(
        phase: RuntimePhase = RuntimePhase.ACT,
        targetApp: String? = "com.whatsapp",
        taskLabel: String = "Send receipt to owner",
        stepIndex: Int = 1,
        stepCount: Int = 3,
        retryCount: Int = 2,
        blocker: String? = "Network timeout",
    ) = WorkStatus(
        workerId = WORKER_ID,
        targetApp = targetApp,
        taskLabel = taskLabel,
        phase = phase,
        stepIndex = stepIndex,
        stepCount = stepCount,
        retryCount = retryCount,
        blocker = blocker,
    )

    /** onCreate + one real onStartCommand tick so the overlay view is attached. */
    private fun startedService(): OverlayService {
        val service = Robolectric.setupService(OverlayService::class.java)
        service.onStartCommand(null, 0, 1)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNotNull("overlay should be attached after the first tick", service.currentOverlayViewForTest())
        return service
    }

    @Test
    fun reportedWorkStatusRendersPhaseTargetStepRetryAndBlockerWord() {
        val service = startedService()
        RuntimeStatusBus.report(status())
        val view = service.currentOverlayViewForTest()!!
        assertEquals("Working", view.findViewById<TextView>(R.id.overlay_phase).text.toString())
        val detail = view.findViewById<TextView>(R.id.overlay_detail)
        assertEquals(View.VISIBLE, detail.visibility)
        val rendered = detail.text.toString()
        assertTrue(rendered.contains("com.whatsapp"))
        assertTrue(rendered.contains("step 2/3"))
        assertTrue(rendered.contains("retry 2"))
        assertTrue(rendered.contains("Network"))
    }

    @Test
    fun actingAutomationMakesTheChipUntouchableAndIgnoresDrags() {
        val service = startedService()
        RuntimeStatusBus.beginActing()
        RuntimeStatusBus.report(status())
        val flags = service.currentLayoutParams()?.flags ?: throw AssertionError("params missing")
        assertNotEquals(0, flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        assertEquals(0, drag(service))
        RuntimeStatusBus.endActing()
    }

    @Test
    fun endingActingLeavesObservationalChipUntouchable() {
        val service = startedService()
        RuntimeStatusBus.beginActing()
        RuntimeStatusBus.report(status())
        RuntimeStatusBus.endActing()
        RuntimeStatusBus.report(status())
        val flags = service.currentLayoutParams()?.flags ?: throw AssertionError("params missing")
        assertNotEquals(0, flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        assertEquals(0, drag(service))
    }

    /** Drags the chip left by 500px; returns 1 when params moved, 0 when inert. */
    private fun drag(service: OverlayService): Int {
        val view = service.currentOverlayViewForTest()!!
        val startX = service.currentLayoutParams()?.x ?: return -1
        val y = 100f
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, startX.toFloat(), y, 0)
        view.dispatchTouchEvent(down)
        down.recycle()
        val move = MotionEvent.obtain(0, 60, MotionEvent.ACTION_MOVE, startX - 500f, y + 10f, 0)
        view.dispatchTouchEvent(move)
        move.recycle()
        val up = MotionEvent.obtain(0, 120, MotionEvent.ACTION_UP, startX - 500f, y + 10f, 0)
        view.dispatchTouchEvent(up)
        up.recycle()
        val endX = service.currentLayoutParams()?.x ?: return -1
        return if (endX != startX) 1 else 0
    }

    @Test
    fun keyguardLockShowsOnlyTheGenericWorkingLabelWithNoDetails() {
        val service = startedService()
        RuntimeStatusBus.report(status())
        val view = service.currentOverlayViewForTest()!!
        // Force the keyguard branch directly (Robolectric KeyguardManager shadow is app-global).
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        Shadows.shadowOf(keyguard).setKeyguardLocked(true)

        service.renderLatest()

        // The label stays TRUTHFUL (derived from the bus) but detail lines are redacted.
        assertEquals("Working", view.findViewById<TextView>(R.id.overlay_phase).text.toString())
        val detail = view.findViewById<TextView>(R.id.overlay_detail)
        assertTrue(detail.text.isNullOrBlank())
        assertEquals(View.GONE, detail.visibility)
        val expandedDetail = view.findViewById<TextView>(R.id.overlay_expanded_detail)
        assertTrue(expandedDetail.text.isNullOrBlank())
        val phaseText = view.findViewById<TextView>(R.id.overlay_phase).text.toString()
        val everything = listOf(phaseText) +
            listOfNotNull(detail.text?.toString(), expandedDetail.text?.toString())
        for (secret in listOf("com.whatsapp", "Send receipt", "Network", "step", "retry")) {
            assertFalse("keyguard chip leaked '$secret'", everything.any { it.contains(secret) })
        }
    }

    @Test
    fun keyguardIdleChipNeverClaimsWorkAndActiveChipNeverClaimsReady() {
        val service = startedService()
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        Shadows.shadowOf(keyguard).setKeyguardLocked(true)

        // Idle bus + locked screen: the static "Amara working" lie is forbidden.
        service.renderLatest()
        assertEquals(
            "an idle agent must never claim to be working, even behind the keyguard",
            "Ready",
            service.currentOverlayViewForTest()!!.findViewById<TextView>(R.id.overlay_phase).text.toString(),
        )
        assertFalse(service.isPulseRunning())

        // And the inverse direction holds too: blocked work is never hidden as Ready.
        RuntimeStatusBus.report(status(phase = RuntimePhase.BLOCKED, blocker = "Approval needed"))
        service.renderLatest()
        assertEquals(
            "Needs you",
            service.currentOverlayViewForTest()!!.findViewById<TextView>(R.id.overlay_phase).text.toString(),
        )
    }

    @Test
    fun onDestroyCancelsPulseDetachesListenerAndKillsTickLoopWithoutThrowing() {
        val service = startedService()
        RuntimeStatusBus.report(status(phase = RuntimePhase.ACT))
        assertTrue(service.isPulseRunning())
        assertTrue(service.isBusListenerAttached)
        assertTrue(service.isTickScheduled)

        service.onDestroy()

        assertNull(service.activePulseAnimator())
        assertFalse(service.isBusListenerAttached)
        assertFalse(service.isTickScheduled)

        // A late report after destroy must neither throw nor resurrect the overlay.
        RuntimeStatusBus.report(status(phase = RuntimePhase.COMPLETE))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNull(service.currentOverlayViewForTest())
    }

    @Test
    fun requestStopFlagIsConsumedByTheNextTickAndStopsTheService() {
        val service = startedService()
        OverlayService.requestStop(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean(STOP_KEY, false))

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))

        assertTrue(Shadows.shadowOf(service).isStoppedBySelf())
        assertFalse(prefs.getBoolean(STOP_KEY, false))
    }

    @Test
    fun dragClampingKeepsTheChipWithinScreenEdgeMargins() {
        val service = startedService()
        val clamped = service.clampToEdges(-10_000, -10_000)!!
        assertTrue(clamped.first >= 8)
        assertTrue(clamped.second >= 8)
        val far = service.clampToEdges(100_000, 100_000)!!
        assertTrue(far.first <= service.screenWidth - 8)
        assertTrue(far.second <= service.screenHeight - 8)
    }

    @Test
    fun chipWidthBudgetStaysUnderThirtyPercentOfScreen() {
        val service = startedService()
        val budget = service.chipWidthBudgetPx()
        assertTrue("$budget must stay under 30% of ${service.screenWidth}", budget < (service.screenWidth * 0.30f).toInt())
    }

    @Test
    fun fullCardBudgetReservesItsFixedChromeAndNeverAcceptsTouches() {
        val service = startedService()
        assertTrue(service.chipTextWidthBudgetPx() < service.chipWidthBudgetPx())
        assertNotEquals(
            0,
            service.currentLayoutParams()!!.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        )
    }

    @Test
    fun idleChipIsGenuinelyNonTouchableAndNeverCarriesAFocusableFlag() {
        val service = startedService()
        val params = service.currentLayoutParams()!!
        assertNotEquals(
            "idle chip must keep FLAG_NOT_TOUCHABLE",
            0,
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        )
        assertNotEquals(
            "idle chip must keep FLAG_NOT_FOCUSABLE",
            0,
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        )
        val view = service.currentOverlayViewForTest()!!
        assertFalse("idle chip must have no click listeners attached", view.hasOnClickListeners())
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        val consumed = view.dispatchTouchEvent(down)
        down.recycle()
        assertFalse("idle chip must not consume any touch event", consumed)
    }

    @Test
    fun widthBudgetAppliesToTheCompleteVisualFootprintNotJustTheText() {
        val service = startedService()
        val budget = service.chipWidthBudgetPx()
        val textBudget = service.chipTextWidthBudgetPx()
        val reserved = budget - textBudget
        val chromeExpectedDp = 42
        val density = service.resources.displayMetrics.density
        val chromeExpectedPx = (chromeExpectedDp * density).toInt()
        assertEquals(
            "fixed chrome (padding + icon + gap) must be reserved before text",
            chromeExpectedPx,
            reserved,
        )
        val view = service.currentOverlayViewForTest()!!
        val widthSpec = View.MeasureSpec.makeMeasureSpec(service.screenWidth, View.MeasureSpec.AT_MOST)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(service.screenHeight, View.MeasureSpec.AT_MOST)
        view.measure(widthSpec, heightSpec)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val card = view.findViewById<LinearLayout>(R.id.overlay_card)
        assertTrue(
            "card footprint ${card.width} must stay under 30% of ${service.screenWidth}",
            card.width < (service.screenWidth * 0.30f).toInt(),
        )
        assertTrue(
            "card footprint ${card.width} must stay under the 28% width budget $budget",
            card.width <= budget,
        )
    }

    @Test
    fun expandedPanelIsTheOnlyTouchableBranchAndItIsNeverOpenedAtStart() {
        val service = startedService()
        val view = service.currentOverlayViewForTest()!!
        val panel = view.findViewById<LinearLayout>(R.id.overlay_expanded)
        assertEquals("expanded panel must start hidden", View.GONE, panel.visibility)
        assertFalse("expanded state must be false at start", service.isExpandedForTest())
        assertNotEquals(
            "no touch path can be live before the user explicitly opens the panel",
            0,
            service.currentLayoutParams()!!.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        )
    }

    @Test
    fun transitioningIdleToActiveKeepsNonTouchableAndKeepsBudget() {
        val service = startedService()
        val idleFlags = service.currentLayoutParams()!!.flags
        val budgetBefore = service.chipWidthBudgetPx()
        RuntimeStatusBus.beginActing()
        service.renderLatest()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val activeFlags = service.currentLayoutParams()!!.flags
        assertNotEquals("FLAG_NOT_TOUCHABLE must remain set during acting", 0, activeFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        assertEquals(
            "the acting transition must never flip the touchable bit",
            idleFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            activeFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        )
        assertEquals("width budget must not change when automation starts acting", budgetBefore, service.chipWidthBudgetPx())
        RuntimeStatusBus.endActing()
    }

    @Test
    fun paletteMatchesMissionColorsPerPhase() {
        val amber = 0xFFF59E0B.toInt()
        val red = 0xFFF87171.toInt()
        val green = 0xFF4ADE80.toInt()
        val blueGray = 0xFF94A3B8.toInt()
        val service = startedService()
        assertEquals(amber, service.paletteFor(RuntimePhase.ACT).second)
        assertEquals(red, service.paletteFor(RuntimePhase.BLOCKED).second)
        assertEquals(red, service.paletteFor(RuntimePhase.FAILED).second)
        assertEquals(green, service.paletteFor(RuntimePhase.COMPLETE).second)
        assertEquals(blueGray, service.paletteFor(RuntimePhase.OBSERVE).second)
        assertEquals(blueGray, service.paletteFor(RuntimePhase.THINK).second)
        assertEquals(blueGray, service.paletteFor(RuntimePhase.IDLE).second)
    }

    // ---------- duplicate/expanded-overlay directive (R1/R2 defects) ----------

    @Test
    fun repeatedStartsAndTicksCreateExactlyOneWindow() {
        val service = startedService()
        val first = service.currentOverlayViewForTest()!!
        assertEquals(1, service.addViewCalls)

        // Repeated start intents and poll ticks must never add a second window.
        service.onStartCommand(null, 0, 2)
        service.onStartCommand(null, 0, 3)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1_000))

        assertEquals(1, service.addViewCalls)
        assertEquals("the attached view must be the one and only window", first, service.currentOverlayViewForTest())
    }

    @Test
    fun expandedPanelCollapsesTheMomentAutomationWorksOrActs() {
        val service = startedService()
        service.toggleExpanded()
        val view = service.currentOverlayViewForTest()!!
        val panel = view.findViewById<LinearLayout>(R.id.overlay_expanded)
        assertEquals(View.VISIBLE, panel.visibility)

        // Any working automation phase collapses the panel immediately.
        RuntimeStatusBus.report(status(phase = RuntimePhase.OBSERVE))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, panel.visibility)
        assertFalse(service.isExpandedForTest())

        // And it can never be re-opened while automation is acting.
        RuntimeStatusBus.beginActing()
        service.toggleExpanded()
        assertEquals(View.GONE, panel.visibility)
        RuntimeStatusBus.endActing()
    }

    @Test
    fun chipRightEdgeStaysInsideTheScreenMarginAfterAlignment() {
        val service = startedService()
        val view = service.currentOverlayViewForTest()!!
        // Simulate the measure pass the real window manager performs.
        val widthSpec = View.MeasureSpec.makeMeasureSpec(service.screenWidth, View.MeasureSpec.AT_MOST)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(service.screenHeight, View.MeasureSpec.AT_MOST)
        view.measure(widthSpec, heightSpec)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        service.rightAlignChip()
        val params = service.currentLayoutParams()!!
        assertTrue(
            "chip right edge ${params.x + params.width} must stay within ${service.screenWidth - 8}",
            params.x + params.width <= service.screenWidth - 8 + 1,
        )
        assertEquals(view.findViewById<View>(R.id.overlay_card).width, params.width)
        assertTrue(params.x >= 8)
    }

    @Test
    fun keyguardRedactionAlsoCollapsesAnyExpandedPanel() {
        val service = startedService()
        service.toggleExpanded()
        val view = service.currentOverlayViewForTest()!!
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        Shadows.shadowOf(keyguard).setKeyguardLocked(true)
        service.renderLatest()
        assertEquals(View.GONE, view.findViewById<LinearLayout>(R.id.overlay_expanded).visibility)
        assertFalse(service.isExpandedForTest())
    }

    // ---------------- canonical runtime state (overlay truth directive) ----------------

    private fun renderedPhase(service: OverlayService): String {
        // Flag-only transitions (offline/acting) never fire bus listeners; the
        // poll loop is what re-renders them in production, so force one here.
        service.renderLatest()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        return service.currentOverlayViewForTest()!!
            .findViewById<TextView>(R.id.overlay_phase).text.toString()
    }

    @Test
    fun idleBusIsTheOnlyStateThatEverShowsReady() {
        val service = startedService()
        assertEquals("Ready", renderedPhase(service))
        assertFalse(service.isPulseRunning())
    }

    @Test
    fun noWorkingPhaseCanEverRenderReadyOrAStaticDefault() {
        val service = startedService()
        val expected = mapOf(
            RuntimePhase.OBSERVE to "Observing",
            RuntimePhase.THINK to "Thinking",
            RuntimePhase.ACT to "Working",
            RuntimePhase.VERIFY to "Checking",
            RuntimePhase.RETRY to "Retrying",
            RuntimePhase.RECOVER to "Adapting",
        )
        for ((phase, label) in expected) {
            RuntimeStatusBus.report(status(phase = phase, blocker = null))
            assertEquals("phase $phase", label, renderedPhase(service))
            assertNotEquals("Ready", renderedPhase(service))
            RuntimeStatusBus.clear(WORKER_ID)
        }
    }

    @Test
    fun blockedWorkShowsNeedsYouAndPulsesUntilCleared() {
        val service = startedService()
        RuntimeStatusBus.report(status(phase = RuntimePhase.BLOCKED, blocker = "Permission denied"))
        assertEquals("Needs you", renderedPhase(service))
        assertTrue(service.isPulseRunning())
        RuntimeStatusBus.clear(WORKER_ID)
        assertEquals("Ready", renderedPhase(service))
        assertFalse(service.isPulseRunning())
    }

    @Test
    fun failedWorkShowsStalledNotReady() {
        val service = startedService()
        RuntimeStatusBus.report(status(phase = RuntimePhase.FAILED, blocker = "Verify failed"))
        assertEquals("Stalled", renderedPhase(service))
    }

    @Test
    fun completedWorkShowsDoneThenReturnsToReadyOnlyAfterClear() {
        val service = startedService()
        RuntimeStatusBus.report(status(phase = RuntimePhase.COMPLETE))
        assertEquals("Done", renderedPhase(service))
        RuntimeStatusBus.clear(WORKER_ID)
        assertEquals("Ready", renderedPhase(service))
    }

    @Test
    fun providerOfflineShowsOfflineInsteadOfReadyWhileIdleButNeverHidesLiveWork() {
        val service = startedService()
        RuntimeStatusBus.setProviderOffline(true)
        assertEquals(OverlayService.OFFLINE_LABEL, renderedPhase(service))
        assertFalse(service.isPulseRunning())
        // Live work always outranks the offline flag.
        RuntimeStatusBus.report(status(phase = RuntimePhase.ACT))
        assertEquals("Working", renderedPhase(service))
        assertTrue(service.isPulseRunning())
    }

    @Test
    fun staleStatusFromAnInterruptedProcessNeverSticksTheChipOnWorking() {
        val service = startedService()
        RuntimeStatusBus.report(
            status(phase = RuntimePhase.ACT).copy(updatedAtMillis = System.currentTimeMillis() - RuntimeStatusBus.STALE_AFTER_MS - 1),
        )
        // The debris status is older than the freshness window: the chip must fall back to truth.
        assertEquals("Ready", renderedPhase(service))
        assertFalse(service.isPulseRunning())
    }

    @Test
    fun terminalAttentionStateDoesNotExpireIntoAFalseReadyClaim() {
        val service = startedService()
        RuntimeStatusBus.report(
            status(phase = RuntimePhase.BLOCKED, blocker = "Permission denied").copy(
                updatedAtMillis = System.currentTimeMillis() - RuntimeStatusBus.STALE_AFTER_MS - 1,
            ),
        )
        assertEquals("Needs you", renderedPhase(service))
        assertTrue(service.isPulseRunning())
    }

    @Test
    fun blockedSeverityDominatesConcurrentLowerSeverityWork() {
        val service = startedService()
        RuntimeStatusBus.report(status(phase = RuntimePhase.OBSERVE, blocker = null))
        RuntimeStatusBus.report(status(phase = RuntimePhase.BLOCKED, blocker = "Approval needed").copy(workerId = "$WORKER_ID-2"))
        assertEquals("Needs you", renderedPhase(service))
        RuntimeStatusBus.clear("$WORKER_ID-2")
        RuntimeStatusBus.clear(WORKER_ID)
    }

    @Test
    fun actingLockAlwaysPresentsAsWorkingEvenWithoutAnyStatus() {
        val service = startedService()
        RuntimeStatusBus.beginActing()
        try {
            assertEquals("Working", renderedPhase(service))
            assertNotEquals("Ready", renderedPhase(service))
        } finally {
            RuntimeStatusBus.endActing()
        }
        assertEquals("Ready", renderedPhase(service))
    }

    @Test
    fun serviceRestartReattachesAndRendersTheCanonicalStateImmediately() {
        val first = startedService()
        first.onDestroy()
        assertFalse(first.isBusListenerAttached)

        val second = Robolectric.setupService(OverlayService::class.java)
        second.onStartCommand(null, 0, 10)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(second.currentOverlayViewForTest())
        assertEquals(1, second.addViewCalls) // exactly one window after restart
        assertTrue(second.isBusListenerAttached)

        RuntimeStatusBus.report(status(phase = RuntimePhase.RETRY))
        assertEquals("Retrying", renderedPhase(second))

        second.onDestroy()
    }

    @Test
    fun chipNeverRendersSensitiveTextEvenWhenBlockersCarryIt() {
        val service = startedService()
        val hostile = status(
            taskLabel = "Send order confirmation to the customer who lives near the old taxi park in Ntinda",
            blocker = "pin 5555 rejected by terminal with password hunter2 inside stacktrace overflow text",
        )
        RuntimeStatusBus.report(hostile)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val view = service.currentOverlayViewForTest()!!
        val everything = listOf(
            view.findViewById<TextView>(R.id.overlay_phase).text.toString(),
            view.findViewById<TextView>(R.id.overlay_detail).text.toString(),
            view.findViewById<TextView>(R.id.overlay_expanded_phase).text.toString(),
            view.findViewById<TextView>(R.id.overlay_expanded_detail).text.toString(),
        )
        for (secret in listOf("5555", "hunter2", "password", "stacktrace")) {
            assertFalse("chip leaked '$secret'", everything.any { it.contains(secret) })
        }
    }

    private companion object {
        const val PREFS = "co.sanaa.agent.overlay"
        const val STOP_KEY = "stop_requested"
        const val WORKER_ID = "worker-under-test"
    }
}
