# Amara commercial-autonomy deep audit — 2026-08-26

## Verdict

**PARTIALLY COMPLETE. Do not activate unsupervised commercial sending yet.** The codebase
has a strong safety/evidence foundation and the connected Oppo is operational, but the
production daily planner still stops before a real exact-bound approval and execution
workflow. That is correct fail-closed behavior, not completion.

## Independently proven now

- Oppo CPH1933 (`7aef1a4c`, Android 11) is connected by USB.
- Accessibility is enabled and bound; no crashed accessibility service remains.
- Overlay permission, notification listener, battery whitelist, and foreground services
  are present.
- The installed package is the source-current signed APK. Local and pulled-device hashes
  match: `b903fcb61b495258df4cfe54c26307a0076b580dfe564e6fa0108cf08ed7e614`.
- Full JVM/Robolectric suite: 72 suites, 607 tests, zero failures/errors/skips.
- Flutter: format clean, analyze clean, 12 tests passed.

## Corrective changes completed in this audit

1. Durable campaign touches and delivery observations, including SQLite v15 migration,
   idempotency, deletion, queries, dashboard consumption, and tests.
2. Sale evidence advances a new opportunity through each legal funnel stage instead of
   failing on a non-adjacent target.
3. Live WhatsApp inquiry observations resolve a product only when exactly one
   owner-approved product is explicitly named. Generic and ambiguous messages fail closed.
4. Owner timezone is used consistently; missing/invalid configuration falls back to
   explicit UTC reporting and cannot authorize a commercial cycle.
5. Dashboard unattributed totals include both explicit UNATTRIBUTED rows and sales without
   attribution rows, with reconciliation evidence.
6. Commercial health defaults degraded and combines live channel, escalation, and
   uncertain-outcome blockers.
7. Commercial worker uses proven inventory, corrected phase boundaries, and fresh health.
   It no longer fabricates AWAITING_APPROVAL when no real request exists.
8. The first commercial policy can be configured through a fail-closed UI; opening the
   editor does not save permissive defaults.
9. Follow-up wording asks to check availability rather than asserting invented stock.
10. Credential writes are checked durable commits; validation updates are synchronized;
    PIN-save failure cannot be reported as success; temporary codec plaintext bytes are
    wiped.

## Remaining activation blockers

### P0 — exact commercial action bridge

The daily plan persists a content hash but not a safe retrievable canonical draft or a
workflow/approval identifier. `CommercialCycleWorker` therefore cannot create and later
resume an exact-bound `re_engagement` workflow. Required completion:

- persist canonical draft + structured inputs with redaction and schema migration;
- create/dedupe an approval bound to capability, exact contact ID/number, exact normalized
  content hash, contract ID, expiry, and execution count;
- link action ↔ approval ↔ workflow run durably;
- after approval, re-check policy, consent, contact identity, product evidence, health,
  quiet hours, caps, deadline, and content hash immediately before act;
- execute only through `WorkflowExecutor` → `TransactionRoutedEffects` →
  `SideEffectRunner`;
- set `EXECUTED_VERIFIED` only from independent target/content/delivery evidence;
- map no-effect to FAILED/BLOCKED, uncertain effects to a new reconciliation state, and
  never blindly retry an uncertain send.

### P0 — real production snapshot feeds

The worker still supplies empty weak listings, orders, bookings, sales signals, inbound
inquiries, and campaign analytics. Each feed needs one typed durable observation source,
freshness/provenance, dedupe, and explicit unknown/stale behavior. Empty data must never be
interpreted as “no problems” or “zero sales.”

### P0 — typed Soko authentication outcomes

Current modules infer acceptance from a successful scan. Reaching an already-authenticated
home screen must not reset prior PIN failures. Introduce `NOT_SUBMITTED`,
`SUBMITTED_ACCEPTED`, `SUBMITTED_REJECTED`, and `INDETERMINATE`; wire every PIN consumer,
including Inventory and Studio, through the same credential auditor.

### P0 — controlled device execution proof

No commercial action may be released until an owner supplies a controlled recipient and
the exact build proves: correct target, exact content, one send, delivery observation,
ledger transition, approval consumption once, and no duplicate after restart/retry.

### P1 — permission and obstruction recovery

Amara may open/close/reopen apps and navigate recoverable obstructions. Android-protected
permission grants, lock-screen PIN/biometric challenges, OTPs, CAPTCHAs, and account
consent remain owner boundaries. The app must detect, explain, and park rather than claim
self-repair. Permission health must be continuously observable and reported.

### P1 — contact authority and WhatsApp audience quality

Add a reviewable contact workspace: canonical ID, normalized number, aliases, role,
revenue eligibility, consent scope/source/expiry, suppression, last verified time, and
ambiguity/revocation status. Planning and final dispatch must both re-resolve the same
identity. No fuzzy or duplicate-name match may select a recipient.

### P1 — Groq repair on production paths

All commercial schemas need controlled malformed/empty/wrong-root/truncated/replayed/
timeout/429/5xx tests through the production gateway, bounded repair and backoff, durable
failure/recovery correlation, and proof of zero downstream effect before valid JSON.

### P1 — truthful overlay

The overlay must show canonical state only: observing, planning, awaiting owner, acting,
verifying, blocked, failed, or reconciling. It must not accept command interference while
acting, obscure the target/control, expose secrets/customer text, or say Ready while a task
is active.

### P2 — evaluation and elapsed time

A staged trial must prove daily planning, missed-target adaptation without authority
expansion, daily failure report, weekly sales contribution, monthly cost comparison,
reboot/timezone recovery, outage/adversarial matrices, seven-day soak, and 30-day
supervised trial. Time may not be simulated or backdated for elapsed-time claims.

## Mission interpretation

“Survival instinct” means relentless measurement, useful safe work, honest failure
reporting, and strategy adaptation. It never outranks consent, owner policy, customer
safety, truthfulness, spend limits, platform rules, or evidence. Missing a target should
produce analysis and a bounded proposal—not spam, invented urgency, unauthorized pricing,
or silent authority expansion.
