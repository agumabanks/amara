# Amara Self-Directing Loop — Complete Architecture Consult

*Consultant: claude-fable-5 via lightning.ai | Date: 2026-09-02*

---

## Q1: Loop Architecture

**Pattern: supervisor loop + session state machine + Room-backed priority queue.**

State machine:
```
SLEEPING ──(wake pulse | pushed event | alarm)──▶ SENSING
SENSING  ──▶ PLANNING (discover work, score, check budget)
PLANNING ──▶ EXECUTING (if grant + work)  |  ──▶ SLEEPING (if nothing worth doing)
EXECUTING ──▶ REPORTING ──▶ SLEEPING

ANY STATE ──(owner picks up phone)──▶ YIELDING ──▶ SLEEPING
ANY STATE ──(kill switch / policy breach)──▶ HALTED
```

Key class:
```kotlin
class AmaraWorkLoop(
    private val runtime: AgentRuntime,
    private val queue: WorkQueue,
    private val sources: List<WorkSource>,
    private val budgeter: PhoneTimeBudgeter,
    private val governor: SafetyGovernor,
    private val executor: WorkExecutor,
    private val ledger: OutcomeLedger,
) {
    private val wakeSignal = Channel<WakeReason>(Channel.CONFLATED)

    suspend fun run() = coroutineScope {
        while (isActive && !governor.isHalted()) {
            val reason = awaitWake()                      // SLEEPING
            val snapshot = sense()                        // SENSING
            if (snapshot.ownerActive) { yieldToOwner(); continue }

            discoverAndEnqueue(snapshot)                  // PLANNING
            val grant = budgeter.requestSession(snapshot) ?: continue

            val session = ExecutionSession(grant, snapshot)
            val results = executeSession(session)         // EXECUTING
            report(results)                               // REPORTING
        }
    }
}
```

**NOT a multi-agent market** — single phone = single serialized execution resource. Internal bidding adds debugging pain with zero throughput gain. Build the scoring function directly.

---

## Q2: Work Discovery — Pull-Based `WorkSource` + Push Events

**Pattern: hybrid. Cheap pull during PLANNING, push for real-time events.**

```kotlin
interface WorkSource {
    val domain: Domain // SOKO, TIKTOK, WHATSAPP, INTERNAL
    /** MUST be cheap: local DB, caches, time. Never opens an app. <100ms. */
    suspend fun propose(snapshot: WorldSnapshot): List<WorkItem>
}

data class WorkItem(
    val dedupeKey: String,          // e.g. "wa_followup:+2547xx:2025-01-15"
    val domain: Domain,
    val kind: WorkKind,             // enum: WA_FOLLOWUP, TIKTOK_COMMENT_REPLY, SOKO_AUDIT, ...
    val payload: JsonObject,
    val baseValueKes: Double,       // best-guess economic value
    val deadline: Instant?,         // hard expiry
    val urgencyHalfLifeHours: Double, // decay curve param
    val estimatedScreenSeconds: Int,
    val requires: Set<Capability>,  // SCREEN, NETWORK, GROQ, CONSENT_TIER_2
    val createdAt: Instant,
)
```

**The critical trick: discovery that requires screen time is itself a WorkItem.** `SokoWorkSource` can't know if there are low-stock alerts without opening Soko Terminal. So it proposes `SOKO_AUDIT` (cheap to propose), and when *executed*, it opens Soko Terminal, reads alerts/orders, and **emits new WorkItems back into the queue** (`RESTOCK_DRAFT`, `ORDER_CONFIRM`, `PRICE_ADJUST`).

```kotlin
data class WorkResult(
    val item: WorkItem,
    val status: WorkStatus,             // DONE, FAILED, SKIPPED, PARTIAL, ESCALATED
    val discoveredWork: List<WorkItem>, // ← audits emit these
    val outcomeFacts: List<Fact>,       // for OutcomeLedger (Q6)
    val screenSecondsUsed: Int,
    val failure: FailureInfo?,
)
```

**Queue rules (enforce in `WorkQueue`, Room-backed):**

```kotlin
interface WorkQueue {
    suspend fun offer(item: WorkItem): OfferResult   // DEDUPED, ACCEPTED, REJECTED_CAP
    suspend fun peekBest(now: Instant, caps: Capabilities): WorkItem?
    suspend fun markInFlight(id: Long): Boolean       // lease with 5-min TTL
    suspend fun complete(id: Long, result: WorkResult)
    suspend fun requeue(id: Long, notBefore: Instant, attempt: Int)
    suspend fun expireStale(now: Instant): Int        // deadline passed → EXPIRED
}
```

- **Dedupe on `dedupeKey`** — a re-proposed item refreshes `baseValueKes` but never duplicates.
- **Per-domain queue cap: 25 items.** A source proposing more means it's broken, not that there's more work.
- **Everything expires.** Default TTL 48h if no deadline. Stale work is worse than no work.
- Standard sources to build: `WhatsAppFollowUpSource` (dormant chats from `chatStore`), `SokoWorkSource` (proposes audits + carries forward discovered items), `TikTokWorkSource` (comment-check sessions, post schedule from `DailyCommercialCycle` plan), `InternalSource` (reconciliation, briefing prep, ledger compaction).

---

## Q3: Phone Time Budget

**Model: daily screen-time allowance, spent in discrete sessions, gated by owner presence and device health.**

```kotlin
class PhoneTimeBudgeter(
    private val presence: OwnerPresenceMonitor,
    private val device: DeviceHealthMonitor,
    private val policy: CommercialPolicy,
    private val ledgerDao: BudgetLedgerDao,
) {
    suspend fun requestSession(snapshot: WorldSnapshot): SessionGrant?
    fun remainingToday(): Duration
}

data class SessionGrant(
    val maxDuration: Duration,      // the slice
    val hardStopAt: Instant,        // wall-clock kill line
    val allowedCapabilities: Set<Capability>,
    val grantId: String,
)
```

**Budget formula:**

```
dailyBudgetMinutes = policy.maxAgentScreenMinutesPerDay   (default 90)

sessionLength = min(
    remainingToday,
    baseSlice(timeOfDay),          // see table
    thermalCap(device),            // NORMAL→full, WARM→50%, HOT→0
    batteryCap(device)             // >50%→full, 20–50%→50%, <20% & not charging→0, charging→full
)

Grant only if sessionLength >= 3 min AND queue has work scoring above threshold (Q4).
```

**Slice table (defaults, owner-tunable via `CommercialPolicy`):**

| Window | Base slice | Min gap between sessions | Rationale |
|---|---|---|---|
| 06:00–09:00 | 15 min | 30 min | Morning broadcast, overnight orders, Soko audit |
| 09:00–17:00 | 8 min | 45 min | Steady-state: follow-ups, comments |
| 17:00–21:00 | 12 min | 30 min | Peak engagement window for TikTok |
| Quiet hours | 0 min for outbound; 5 min read-only every 2h | — | Health checks, drafting only |

**Owner presence detection — layered, cheapest first:**

```kotlin
class OwnerPresenceMonitor(context: Context) {
    // Layer 1: screen interactive + keyguard state (PowerManager.isInteractive,
    //          KeyguardManager.isDeviceLocked). Screen on + unlocked and Amara
    //          didn't turn it on → owner is holding the phone.
    // Layer 2: UsageStatsManager foreground app ≠ Amara's automation targets
    //          within last 2 min → owner active.
    // Layer 3: ACTION_USER_PRESENT broadcast → immediate YIELDING signal.
    fun presenceFlow(): Flow<OwnerPresence>  // ACTIVE, IDLE_SHORT(<15m), IDLE_LONG, ASLEEP(inferred)
}
```

**Yield rule: owner touch = instant preemption.** On `ACTIVE`, the executor finishes the current *atomic* step via `SideEffectRunner` (never abandon a half-committed transaction), then aborts the session, requeues the item, refunds unused grant time. Amara works when the phone is idle-locked; sweet spots are `IDLE_LONG` and inferred `ASLEEP` (no interaction 45+ min, past 22:00 → read-only work only, per quiet hours).

---

## Q4: Self-Assessment — the Scoring Function

**Score = risk-adjusted expected value rate, with urgency decay and deadline boost:**

```kotlin
fun score(item: WorkItem, ctx: ScoringContext, ledger: OutcomeLedger): Double {
    val ageHours   = hoursBetween(item.createdAt, ctx.now)
    val decay      = 2.0.pow(-ageHours / item.urgencyHalfLifeHours)   // urgency decay
    val pSuccess   = ledger.successRate(item.kind, ctx.timeBucket)    // learned, Q6
    val evKes      = item.baseValueKes * pSuccess
    val effortMin  = max(0.5, item.estimatedScreenSeconds / 60.0)
    val deadlineBoost = item.deadline?.let {
        val hrsLeft = hoursBetween(ctx.now, it)
        if (hrsLeft <= 0) 0.0 else 1.0 + (6.0 / max(hrsLeft, 1.0))   // ≤1h left ≈ 7×
    } ?: 1.0
    val domainStarvation = 1.0 + 0.1 * ctx.hoursSinceDomainTouched(item.domain) // anti-starvation
    val riskPenalty = when (item.riskTier) {   // LOW=1.0, MEDIUM=0.7, HIGH=0.4
        RiskTier.LOW -> 1.0; RiskTier.MEDIUM -> 0.7; RiskTier.HIGH -> 0.4
    }
    return (evKes * decay * deadlineBoost * domainStarvation * riskPenalty) / effortMin
}
```

**Default parameters per kind (starting values — the ledger tunes `pSuccess` later):**

| Kind | baseValueKes | halfLife (h) | est. seconds | risk |
|---|---|---|---|---|
| ORDER_CONFIRM | 800 | 2 | 60 | LOW |
| WA_REPLY_INBOUND | 300 | 1 | 45 | MEDIUM |
| WA_FOLLOWUP | 150 | 12 | 40 | MEDIUM |
| TIKTOK_COMMENT_REPLY | 40 | 4 | 30 | LOW |
| TIKTOK_POST_PUBLISH | 250 | 6 | 120 | MEDIUM |
| SOKO_AUDIT | 200* | 8 | 180 | LOW |
| RESTOCK_DRAFT | 400 | 24 | 90 | LOW |

\* Audit value = expected value of work it *discovers*; the ledger learns this from `discoveredWork` value over trailing 14 days.

**Session admission threshold:** don't burn a session unless `bestScore ≥ 30 KES/min` during business hours, `≥ 80` if it would require waking the screen at night. Ordering within a session: re-peek after every item (results change the queue), never re-sort mid-execution of an item.

---

## Q5: Failure Recovery

**Classify first, then dispatch. Never blind-retry UI failures.**

```kotlin
enum class FailureClass { TRANSIENT_NETWORK, UI_MISMATCH, APP_STATE, PRECONDITION_GONE, POLICY_BLOCKED, UNKNOWN }

sealed class RecoveryDecision {
    data class RetryInSession(val afterMs: Long) : RecoveryDecision()
    data class Requeue(val notBefore: Instant, val attempt: Int) : RecoveryDecision()
    data class Drop(val reason: String) : RecoveryDecision()
    data class Escalate(val summaryForOwner: String) : RecoveryDecision()
}

class FailurePolicy {
    fun decide(f: FailureInfo, item: WorkItem, attempt: Int): RecoveryDecision = when (f.klass) {
        TRANSIENT_NETWORK -> if (attempt < 3) RetryInSession(2000L * attempt) else Requeue(now + 30.min, attempt)
        APP_STATE         -> if (attempt == 1) RetryInSession(0) /* after forceStop+relaunch */ else Requeue(now + 1.hour, attempt)
        UI_MISMATCH       -> Requeue(now + backoff(attempt), attempt)   // never retry in-session
        PRECONDITION_GONE -> Drop("state changed under us")             // e.g. customer already replied
        POLICY_BLOCKED    -> Drop("policy")                             // by design, not an error
        UNKNOWN           -> if (attempt < 2) Requeue(now + backoff(attempt), attempt) else Escalate(f.summary())
    }
}

// backoff(attempt) = min(15min * 2^(attempt-1), 4h) + jitter(0..20%)
// Max attempts: 4 total per dedupeKey, then DEAD → include in daily report.
```

**Non-negotiable invariants:**

1. **Recovery preamble before every UI item:** `pressHome() → verifyLauncher() → launchTarget() → verifyLandmark()`. Never assume screen state from the previous item. This single rule kills ~70% of cascading UI failures.
2. **`UI_MISMATCH` on the same `WorkKind` twice in one session → quarantine that kind for 6h** (probable app update changed the layout). Emit an `Escalate` note: *"TikTok comment screen changed — paused comment work, screenshot attached."*
3. **Side-effect boundary:** `SideEffectRunner` already gives you idempotency — every retry must route through it so a "failed" send that actually landed doesn't double-send. The `dedupeKey` doubles as the idempotency key.
4. **Session-level fuse: 2 consecutive item failures → end the session.** Something systemic is wrong (network down, app update broke UI). Don't burn budget.
5. **Escalation format** (for the owner):
   ```
   🔴 Amara needs you: [specific issue]
   She was trying to: [action]
   Problem: [human-readable]
   What she did: [what she already tried]
   Reply 1/2/3: [numbered options]
   ```
6. **Dead letter queue:** Items that exhaust 4 attempts → DEAD. Reviewed in daily report, auto-archived after 7 days.

---

## Q6: Learning Loop — Two-Layer Adaptation

**The mechanical layer changes numbers, the reflective layer changes strategy.**

### 6.1 Mechanical Layer — Per-Execution Statistics

```kotlin
@Entity(tableName = "execution_records")
data class ExecutionRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workItemId: String,
    val domain: String,
    val kind: String,
    val startedAt: Long,
    val outcome: Outcome,          // SUCCESS, FAILED, PARTIAL, ABORTED_FUSE, ABORTED_GUARDIAN
    val failureClass: FailureClass?,
    val estimatedScreenSeconds: Int,
    val actualScreenSeconds: Int,
    val realizedValueKes: Double?,  // null if unknowable; else actual value captured
    val hourOfDay: Int,
    val attemptNumber: Int,
    val selectorFallbacksUsed: Int  // how many UI selector fallbacks fired
)

@Entity(tableName = "kind_stats", primaryKeys = ["domain", "kind"])
data class KindStats(
    val domain: String,
    val kind: String,
    val successEwma: Double,        // α = 0.15
    val effortRatioEwma: Double,    // actual/estimated screen seconds, α = 0.20
    val valueRatioEwma: Double,     // realized/predicted value, α = 0.10
    val attemptsTotal: Int,
    val successesTotal: Int,
    val lastSuccessAt: Long,
    val hourBucketSuccess: String,  // JSON: 4 buckets [00-06, 06-12, 12-18, 18-24] of EWMA
    val quarantinedUntil: Long = 0
)
```

**Update rule after every execution:**

```
successEwma'     = α * outcome01 + (1 - α) * successEwma,  α = 0.15
effortRatioEwma' = 0.20 * (actual/estimated) + 0.80 * effortRatioEwma
```

**Bayesian smoothing for young kinds.** Raw EWMA is jumpy under 10 samples. Blend with a Beta(3, 1) prior (optimistic — assume new work is worth trying):

```
n = attemptsTotal
effectiveSuccess = (successEwma * min(n, 20) + 3.0 * priorMean) / (min(n, 20) + 3.0)
// priorMean = 0.75
```

**How stats feed back into the Q4 scoring function** — three concrete hooks:

1. **Effort calibration.** Replace raw estimate with calibrated effort:
   ```
   effortMin = (estimatedScreenSeconds * clamp(effortRatioEwma, 0.5, 3.0)) / 60
   ```
   Amara learns "M-Pesa reconciliation always takes 2.1× my estimate" without anyone editing the estimate.

2. **Risk penalty becomes learned, not configured:**
   ```
   riskPenalty = clamp(effectiveSuccess, 0.15, 1.0)
   ```
   Floor at 0.15 so failing kinds still occasionally surface (otherwise you get permanent starvation and never learn the app was fixed).

3. **Time-of-day multiplier** — new factor in the score:
   ```
   todFactor = clamp(hourBucketSuccess[currentBucket] / effectiveSuccess, 0.6, 1.4)
   score = (evKes * decay * deadlineBoost * domainStarvation * riskPenalty * todFactor) / effortMin
   ```
   This makes Amara naturally learn "WhatsApp customer replies land better 18–24h; payment follow-ups fail at night because customers don't confirm" — without anyone encoding it.

**Selector reliability sub-learning.** For UI automation specifically, track per-selector success:

```kotlin
@Entity(tableName = "selector_stats", primaryKeys = ["flowId", "stepId", "selectorHash"])
data class SelectorStat(
    val flowId: String, val stepId: String, val selectorHash: String,
    val strategy: SelectorStrategy,   // VIEW_ID, TEXT_EXACT, TEXT_CONTAINS, CONTENT_DESC, BOUNDS_RELATIVE
    val hitEwma: Double,              // α = 0.25 (fast — UI changes are step functions)
    val avgResolveMs: Int,
    val lastHitAt: Long
)
```

Rule: when resolving a step, try selectors ordered by `hitEwma desc`. If a fallback selector succeeds 3 consecutive times while the primary fails, **promote it to primary** and log a `SelectorPromotion` event. This is the single highest-leverage learning mechanism you'll build — app updates break `VIEW_ID` selectors constantly, and self-healing selectors are the difference between 95% and 60% uptime.

### 6.2 Reflective Layer — The Nightly Reflection Job

Runs as a WorkManager job: constraints `requiresCharging = true`, window 01:00–05:00, owner absent per Q3 presence detection. **It never executes work — it only reads records and writes proposals.**

```kotlin
class ReflectionEngine(
    private val records: ExecutionRecordDao,
    private val stats: KindStatsDao,
    private val overrides: ParameterOverrideDao
) {
    suspend fun reflect(day: LocalDate): ReflectionReport
}

data class ReflectionReport(
    val day: LocalDate,
    val minutesUsed: Int, val minutesBudget: Int,
    val valueRealizedKes: Double, val valuePredictedKes: Double,
    val proposals: List<ParameterProposal>,
    val anomalies: List<Anomaly>,
    val quarantines: List<QuarantineAction>
)
```

**What reflection computes, concretely:**

| Check | Trigger | Action |
|---|---|---|
| **Calibration drift** | `valueRatioEwma < 0.6` for a kind over 7 days | Propose `baseValueKes *= 0.8` for that kind (deflate systematic over-prediction) |
| **Regression detection** | 7-day success < (28-day success − 0.30) with n ≥ 5 | Quarantine kind 24h; on expiry, HALF_OPEN canary |
| **Budget misallocation** | Domain consumed > 40% of minutes but delivered < 15% of realized KES | Propose `domainStarvation` weight −20% for that domain |
| **Deadline misses** | ≥ 3 items expired (`PRECONDITION_GONE` via deadline) in a domain | Propose `urgencyHalfLifeHours *= 0.7` for the offending kind |
| **Dead-letter clustering** | ≥ 3 DLQ items share a `failureClass + flowId` | Emit `Anomaly(FLOW_BROKEN, flowId)` → guardian notification to owner |
| **Wasted-slice detection** | ≥ 2 sessions ended on session-fuse with 0 successes | Propose raising min-score admission threshold +15% for next day |

**Bounded self-modification.** Proposals apply automatically only within clamps; the clamp is the safety property.

---

## Q7: Safety Boundaries — Circuit Breakers & SafetyGovernor

### 7.1 Design Principle

Safety is a **separate authority** from planning. The planner (Q4 scorer) proposes; the `SafetyGovernor` disposes. It sits between `PLANNING` and `EXECUTING` in your state machine and can veto, defer, or demand human approval for any `WorkItem`. It never trusts the planner's judgment — it enforces invariants with its own counters, persisted in Room so they survive process death.

### 7.2 Action Irreversibility Tiers

```kotlin
enum class ActionTier {
    T0_READ_ONLY,     // scraping, checking balances, reading messages
    T1_REVERSIBLE,    // drafting, archiving, marking read, internal DB writes
    T2_COSTLY,        // sending messages, posting listings, replying to customers
    T3_IRREVERSIBLE   // payments, transfers, deletions, contract acceptance
}
```

Tier determines which breakers apply and which guardian checks run. **T3 always requires two-phase verification** regardless of governor state.

### 7.3 Circuit Breaker Inventory — Concrete Thresholds

**A. Per-Session Breakers (reset when session slice ends)**

| Breaker | Threshold | Trip Action |
|---|---|---|
| Consecutive failures | 2 (the Q5 session fuse — governor owns it now) | End session, `CAUTION` |
| Total failures in session | 4 (even non-consecutive) | End session |
| Actions per session | 60 UI-driving tasks OR 1.5× planned slice actions | End session |
| Session KES spend | ≥ `sessionSpendCapKes` (default 3,000) | Block further T3, finish T0–T2 |
| Session overrun | actual screen time > 1.4 × granted slice | Hard end, log budget violation for Q6 reflection |
| Unexpected foreground app | 2 occurrences in one session | End session, `CAUTION` |

**B. Per-Day Breakers (reset at local midnight, Africa/Nairobi)**

| Breaker | Default Threshold | Trip Action |
|---|---|---|
| Daily screen budget | 90 min (Q3) — hard stop at 100%, no borrow | `SLEEPING` until midnight, emergency-only wake (deadline < 2h AND tier ≤ T1) |
| Daily KES spend (T3 cumulative) | 20,000 KES | Quarantine all T3 kinds for the day |
| Single-transaction cap | 5,000 KES | Item → approval queue, never auto-executed |
| Messages sent (T2) | 40/day | Quarantine messaging kinds |
| Daily failure rate | > 40% with n ≥ 10 attempts | Governor → `RESTRICTED` |
| Dead-letter arrivals | ≥ 5 in one day | Governor → `RESTRICTED` |
| Kinds simultaneously quarantined | ≥ 3 | Governor → `RESTRICTED` |

**C. Per-Kind Breakers (classic circuit breaker with half-open probe)**

```
Trip:      3 consecutive failures for a kind
           OR smoothed failure rate > 50% over last 10 attempts
              (Bayesian: (failures + 1) / (attempts + 2) > 0.5, n ≥ 6)
Cooldown:  2^tripCount hours, capped at 24h
Half-open: after cooldown, allow exactly 1 probe attempt of that kind,
           lowest-risk pending item, T0/T1 preferred
Close:     probe succeeds → reset tripCount decay: tripCount = max(0, tripCount - 1)
Re-trip:   probe fails → cooldown doubles, item does NOT count against
           dead-letter attempts (it's the breaker's fault, not the item's)
```

**D. Rate Limits (token buckets, persisted)**

```kotlin
data class RateLimit(val capacity: Int, val refillPerHour: Double)

val RATE_LIMITS = mapOf(
    "messages.send"     to RateLimit(capacity = 8,  refillPerHour = 5.0),
    "payments.execute"  to RateLimit(capacity = 3,  refillPerHour = 1.0),
    "app.launch.<pkg>"  to RateLimit(capacity = 10, refillPerHour = 4.0), // per package
    "external.api.<host>" to RateLimit(capacity = 30, refillPerHour = 20.0)
)
```

Rate-limited items aren't failed — they're **deferred** with `nextEligibleAt` and re-enter the priority queue (their Q4 decay keeps ticking, which is correct: delay legitimately erodes EV).

### 7.4 SafetyGovernor State Machine

```kotlin
enum class GovernorState {
    NORMAL,     // Full autonomy within budget/tier rules
    DEGRADED,   // Sensing + T0/T1 only; no new T2/T3; reduced rates; auto-recovers
    HALTED      // No execution at all; sensing may continue read-only; owner ack required
}
```

**Transition rules (asymmetric on purpose):**

| From → To | Trigger class | Recovery |
|---|---|---|
| NORMAL → DEGRADED | Soft triggers | Automatic after cooldown + health check |
| NORMAL → HALTED | Hard triggers | Owner acknowledgment only |
| DEGRADED → HALTED | Hard trigger while degraded, OR 3 consecutive degrade cycles in 24h | Owner acknowledgment only |
| DEGRADED → NORMAL | Cooldown elapsed AND breaker half-open probes succeed | — |
| HALTED → NORMAL | `requestResume(OwnerAck)` — never automatic | — |

Key asymmetry: **degradation is cheap and automatic in both directions; halting is cheap to enter and expensive to leave.** You want the agent trigger-happy about degrading and requiring a human to un-halt.

### 7.5 Triggers

```kotlin
sealed class DegradeReason(val cooldown: Duration) {
    object ThermalWarning : DegradeReason(15.minutes)
    object BatterySaver : DegradeReason(30.minutes)
    data class BreakerStorm(val openKinds: Int) : DegradeReason(60.minutes)  // ≥2 kind-breakers open
    object SessionFailureRate : DegradeReason(45.minutes)                     // >40% failures this session
    object BudgetNearExhausted : DegradeReason(untilNextBudgetWindow)         // <10% of daily allowance left
    object SelectorDrift : DegradeReason(120.minutes)  // selector miss rate 3x baseline → likely app UI update
}

sealed class HaltTrigger {
    // Financial integrity — the ones that actually matter
    data class FinancialAnomaly(val expectedKes: Long, val observedKes: Long) : HaltTrigger()
    data class DailyMoneyOutCapBreached(val capKes: Long) : HaltTrigger()
    data class IndeterminateT3(val commitIntentId: String) : HaltTrigger()   // see 7.4

    // Verification integrity
    data class T3VerificationFailed(val commitIntentId: String) : HaltTrigger()
    data class RepeatedT2VerifyFail(val count: Int) : HaltTrigger()          // ≥3 in 24h

    // Environment integrity
    data class UnexpectedForegroundApp(val expected: String, val actual: String) : HaltTrigger()
    data class PermissionRevokedMidAction(val permission: String) : HaltTrigger()
    object ClockSkewDetected : HaltTrigger()                                  // wall clock jumped >5min mid-session

    // Behavioral integrity
    data class RunawayLoop(val workItemId: String, val attempts: Int) : HaltTrigger()  // same item >5x/day despite DLQ
    object SupervisorWatchdogTimeout : HaltTrigger()   // no heartbeat 2x expected interval
    data class ConsecutiveSessionFuses(val count: Int) : HaltTrigger()        // 3 in a row

    // Human authority
    object OwnerStopCommand : HaltTrigger()  // SMS keyword, notification action, or in-app
}
```

### 7.6 Governor API

```kotlin
class SafetyGovernor(
    private val db: AmaraDb,
    private val breakers: BreakerRegistry,      // Q7 per-kind breakers
    private val budget: BudgetLedger,           // Q3
    private val clock: Clock,
) {
    val state: StateFlow<GovernorState>
    fun evaluate(item: WorkItem, tier: ActionTier): GovernorVerdict
    fun recordExecution(result: WorkResult)
    fun requestResume(ack: OwnerAck): Boolean
}
```

---

## Q8: Existing Code Leverage — Refactor vs Build Fresh

### Keep as-is (use directly):

| Component | Role in new loop |
|---|---|
| `AgentRuntime` | Composition root — AmaraWorkLoop gets constructed by it |
| `SideEffectRunner` | Transactional external actions — route all outbound through it |
| `CredentialVault` | Soko PIN access |
| `CommercialPolicy` | Caps, quiet hours, consent |
| `TargetBoundVerifiers` | Verify sends/posts landed |
| `ChatStore` | Conversation persistence |
| `AmaraMemory` | Action history |
| `GroqClient` | LLM calls |

### Refactor (adapt to new loop):

| Component | Change |
|---|---|
| `AutonomyController` | Extract the `executeStep` logic into `WorkExecutor` — it becomes the execution engine that the loop calls |
| `DailyCommercialCycle` | Becomes `SokoWorkSource` — its morning plan feeds the queue |
| `ProactiveWorkers` | Decompose into WorkSources (WhatsAppFollowUpSource, TikTokWorkSource) |
| `ConversationEngine` | `observeWhatsApp()` becomes a push event that wakes the loop; reply generation moves into WorkExecutor |
| `MorningBroadcastModule` | Becomes a WorkSource that proposes BROADCAST items on schedule |
| `TikTokSkill` | Becomes the execution backend for TikTok WorkKinds |
| `WorkScheduler` / `AgentWorkScheduler` | Replace WorkManager-based scheduling with WorkQueue + AlarmManager wake pulses |

### Build fresh:

| Component | Purpose |
|---|---|
| `AmaraWorkLoop` | Supervisor coroutine |
| `WorkQueue` | Room-backed priority queue |
| `WorkSource` interface + 4 implementations | Work discovery |
| `PhoneTimeBudgeter` | Screen time budget |
| `SafetyGovernor` | Circuit breakers |
| `OutcomeLedger` | Mechanical learning |
| `OwnerPresenceMonitor` | Detect owner activity |
| `WorkExecutor` | Execute WorkItems (adapted from AutonomyController) |
| `WorldSnapshot` | Current device/business state |

### Migration plan (suggested order):

1. Build `WorkQueue` + `WorkItem` + `WorkSource` interface (tests first)
2. Build `AmaraWorkLoop` skeleton that polls queue and prints what it WOULD do
3. Implement `WhatsAppFollowUpSource` (simplest, already have dormant chat logic)
4. Build `PhoneTimeBudgeter` + `OwnerPresenceMonitor`
5. Build `WorkExecutor` by extracting from `AutonomyController`
6. Implement `SokoWorkSource` + `TikTokWorkSource` + `InternalSource`
7. Build `SafetyGovernor` + `OutcomeLedger`
8. Wire wake signals (Alarms, push events) into `wakeSignal`
9. Remove old `ProactiveWorkers` scheduling
10. Dogfood on the OPPO device

---

## Summary: The Full Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    AmaraWorkLoop (coroutine)                  │
│  SLEEPING → SENSING → PLANNING → EXECUTING → REPORTING     │
│         ↑ wake signals (alarms, notifications)               │
└─────────────────────────────────────────────────────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        ↓                     ↓                     ↓
  ┌──────────┐        ┌──────────┐        ┌──────────────┐
  │WorkQueue │        │Budgeter  │        │SafetyGovernor│
  │(Room DB) │        │(session) │        │(breakers)    │
  └──────────┘        └──────────┘        └──────────────┘
        │
        │ proposed by
        ↓
  ┌──────────────────────────────────────────────────┐
  │ WorkSources: WhatsApp, Soko, TikTok, Internal     │
  └──────────────────────────────────────────────────┘
        │
        │ executes via
        ↓
  ┌──────────────┐     ┌────────────────┐
  │ WorkExecutor │────▶│ SideEffectRunner│ (existing)
  └──────────────┘     └────────────────┘
        │
        │ outcomes feed
        ↓
  ┌──────────────┐
  │ OutcomeLedger│ → tunes scoring parameters
  └──────────────┘
```

That's the full design. It's a big build but each component is independently testable and you can migrate incrementally — the old workers keep running until the new loop is proven.
