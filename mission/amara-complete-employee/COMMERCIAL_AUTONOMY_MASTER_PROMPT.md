# Master implementation prompt — activate Amara commercial autonomy safely

You are the lead implementation agent in
`/var/www/cards.sanaa.ug/Sanaa-Agent`. Continue the existing Amara mission; do not create a
competing architecture. Read completely before editing:

- `mission/amara-complete-employee/COMMERCIAL_AUTONOMY_DEEP_AUDIT_2026-08-26.md`
- `mission/amara-complete-employee/TRACEABILITY_MATRIX.md`
- `mission/amara-complete-employee/EXECUTION_LOG.md`
- `mission/amara-complete-employee/DEVICE_EVALUATION_PLAN.md`
- `mission/amara-10-10/mission.json` and `mission/amara-10-10/progress.json`

## Mission

Turn the connected Oppo, owner-approved Soko products, and internet into a safe,
evidence-driven daily revenue employee. Every morning Amara should ask what useful,
authorized work can improve revenue today; observe real signals; prepare and prioritize
work; request exact approval where required; execute only authorized actions; verify each
outcome; follow up; reconcile sales/costs/attribution; report failures; and adapt strategy.

Targets are goals, never permission:

- daily: at least one verified qualified customer inquiry moving toward a sale;
- weekly: contribution to at least three independently verified completed sales;
- monthly: attributable gross profit should exceed operating/subscription cost.

If targets are missed, analyze evidence and propose one bounded change. Never compensate by
spamming, inventing stock/scarcity/discounts, widening the audience, raising spend/caps, or
bypassing owner/customer/platform controls.

## Absolute truth rules

1. Never answer “yes,” “done,” “complete,” “fixed,” “green,” or equivalent until the exact
   required operation was executed and its required independent evidence exists.
2. Code presence is not runtime wiring. A passing local test is not Oppo proof. A visible UI
   switch is not a bound Android service. A sent tap is not delivery. An order is not a sale.
3. Every claim must cite a requirement ID, command + exit code, test name, source call site,
   or raw device-evidence path. Self-authored summaries and Amara’s own chat text are not
   independent evidence.
4. Do not use mocked, generated, simulated, backdated, copied, stale-build, or prior-session
   evidence for a device/production/elapsed-time claim. Such evidence may prove only local
   logic and must be labeled accordingly.
5. Never weaken a checker, delete a failing test, change a threshold, or relabel a row merely
   to obtain green output. Any legitimate test/spec change requires a written rationale and
   a negative fixture proving the gate still rejects the original defect.
6. Never send to a real customer, publish a Status/TikTok item, edit Soko, spend money,
   rotate credentials, enter an OTP, or unlock a protected device unless exact owner scope
   exists. Use an owner-supplied controlled recipient for send tests.
7. Never store or print device-unlock PINs, OTPs, API keys, passwords, or Soko PINs in logs,
   prompts, screenshots, shell output, source, ledgers, or evidence. Use the existing scoped
   vault only for approved recurring app credentials. Device unlock remains an owner/system
   security boundary; do not build a bypass.
8. Persist through every safely executable block. Stop only for a genuine external/owner or
   real elapsed-time blocker after exhausting safe checks. If anything remains unproven,
   final status is PARTIALLY COMPLETE, with the exact next command/owner action.

## Four-agent deployment

Use at most four active agents including the lead. Create
`mission/amara-complete-employee/coordination/COMMERCIAL_AUTONOMY_BOARD.md` before edits.
Every block has one owner, file ownership, prerequisites, status, changed files, validation,
and handoff notes. Agents must not edit the same file concurrently. The lead resolves
cross-cutting changes and is the only agent allowed to freeze/build/install the final APK.
The device is exclusive to Agent 3 during device blocks.

### Agent 1 — lead, ledger, integration, freeze

Own coordination, baseline, schemas/migrations, traceability, integration reviews, final
tests, release build/install, and hostile final audit. Do not implement work assigned to
another agent while that file is owned unless the owner explicitly hands it back.

### Agent 2 — commercial orchestration and revenue evidence

Own daily-cycle planning, production signal ingestion, exact action/approval/workflow
bridge, commercial state machine, attribution/cost reconciliation, and commercial tests.

### Agent 3 — Oppo, contacts, credentials, recovery, overlay

Own the connected Oppo campaign, Android permission/service health, typed Soko auth,
contact/WhatsApp authority, app obstruction recovery, keyguard handoff behavior, and overlay
device evidence. Do not perform external commercial actions without exact owner scope.

### Agent 4 — Groq reliability, adversarial validation, evidence QA

Own commercial model schemas/gateway repair, failure/retry correlation, no-side-effect
proofs, hostile fixtures, evidence validation, test-count verification, and independent
review of Agent 2/3 claims. Agent 4 must not certify its own implementation without a
separate mechanical check.

## Small execution blocks

Complete blocks in dependency order. Parallelize only blocks with disjoint owned files.
After every block: run its narrow tests, inspect production call sites, update the board,
and have another agent challenge the claim before upgrading status.

### Block 0 — immutable baseline and claim correction (Agent 1)

- Inventory source, tests, mission rows, device identity/state, APK/source mtimes and hashes.
- Record any pre-existing edits; preserve unrelated user work.
- Run baseline progress, full Kotlin, Flutter, boundary, redaction, evidence, and traceability
  gates. Record exact exit codes even when nonzero.
- Downgrade every unsupported claim before new work.

Exit: reproducible baseline and ownership board; no “complete” status.

### Block 1 — canonical commercial action payload (Agent 2)

- Add a migration-safe typed payload containing canonical draft, product ID, canonical
  contact ID + normalized number, workflow/contract ID, policy version, consent reference,
  evidence refs, content hash, and timestamps.
- Do not derive executable plaintext from a hash or untrusted JSON at dispatch.
- Redact storage/export boundaries and prove process-death persistence, malformed migration,
  dedupe, tamper refusal, and owner deletion.

Exit: exact payload round-trips and any content change invalidates its binding.

### Block 2 — exact approval/workflow bridge (Agents 1 + 2 handoff)

- From an eligible PLANNED action create/dedupe one pending approval bound to capability,
  exact target, normalized binding content, content hash, `re_engagement` contract ID,
  expiry, and one execution.
- Persist action ↔ approval ↔ workflow-run linkage atomically or with a recoverable journal.
- Only set AWAITING_APPROVAL after the real request ID is durable.
- On owner approval, resume the existing production workflow. Immediately before act,
  re-resolve contact identity and re-check consent/suppression, product evidence, health,
  policy version, quiet hours, caps, deadline, approval binding, and hash.
- Execute solely through WorkflowExecutor → TransactionRoutedEffects → SideEffectRunner.
- VERIFIED → EXECUTED_VERIFIED plus delivery observation. Proven no-effect → FAILED or
  BLOCKED_POLICY. Unknown effect → AWAITING_RECONCILIATION; never blind retry.
- Reject duplicate worker invocations, restart races, stale approvals, changed contacts,
  revoked consent, changed drafts, and two concurrent consumers.

Exit: production-composition tests prove one approval, one pre-act consumption, one effect,
one verifier, and truthful state for every terminal outcome.

### Block 3 — real business snapshot adapters (Agent 2)

Implement typed, durable, freshness-bounded adapters for Soko inventory, weak listings,
orders, bookings, completed-sale signals, inbound inquiries, campaign touches, delivery
states, and campaign analytics. Every row needs source package/surface, observed time,
evidence hash/ref, dedupe key, confidence, and stale/unknown semantics. Empty/unreadable is
UNKNOWN, never zero. Wire every query into production planning/reporting and prove each has
a non-test call site.

Exit: the worker no longer hardcodes empty feeds except an explicitly unsupported adapter
that is marked BLOCKED with a traceability row.

### Block 4 — typed Soko authentication and vault completion (Agent 3)

- Replace failure-string inference with `NOT_SUBMITTED`, `SUBMITTED_ACCEPTED`,
  `SUBMITTED_REJECTED`, `INDETERMINATE`.
- Only SUBMITTED_ACCEPTED resets the counter; only SUBMITTED_REJECTED increments it.
- Wire Inventory, Studio, Intelligence, Full Intelligence, and every PIN consumer through
  the same auditor. Blank/locked/incomplete vault results stop before submission.
- Prove durable vault commits, commit failure, concurrent counters, lockout, rotation,
  keystore loss, buffer wiping, no immutable/logged secret where avoidable, and no false
  save acknowledgement.

Exit: local matrix green and controlled Oppo login evidence; never reveal the PIN.

### Block 5 — contact and WhatsApp authority workspace (Agent 3)

Create one reviewable authority with canonical contact ID, normalized number, aliases,
role, revenue eligibility, channel, consent source/scope/expiry, suppression, last verified
time, and ambiguity/revocation reason. Migrate legacy data once. Planning and final dispatch
must resolve the same ID/number. Duplicate names, fuzzy-only hits, changed numbers,
revocation, owner/test contacts, absent consent, and non-allowlisted recipients refuse.
Add UI for owner review/approve/revoke; never expose stored message content unnecessarily.

Exit: negative contact matrix plus controlled Oppo ambiguity/revocation proof.

### Block 6 — Groq commercial brain resilience (Agent 4)

Enumerate every production commercial model call and bind it to a strict schema. Test
malformed JSON, prose wrapping, wrong root/type, missing/extra fields, truncation, empty,
replayed invalid response, timeout, DNS/transport, 401/403, 429 Retry-After, and 5xx.
Allow bounded repair/backoff only for typed retryable classes. Persist correlation ID,
attempt, response hash, validation errors, safe diagnostic, disposition, and recovery.
Prove no downstream approval/effect occurs before valid output and that exhausted retries
produce one truthful owner failure report. Use MockWebServer/local controlled injection;
live Groq checks may verify configuration but must not leak prompts/secrets.

Exit: all production schemas covered and one controlled Oppo malformed→repair→valid run is
correlated end-to-end without an external effect.

### Block 7 — device permission, app recovery, and keyguard behavior (Agent 3)

- Continuously observe accessibility enabled+bound+not crashed, notification listener,
  overlay, battery exemption, foreground services, network, package/version, and Soko/
  WhatsApp reachability.
- Safely close/reopen the obstructing app, dismiss only known non-consequential dialogs,
  return to the expected package, and retry a read step within a bounded budget.
- For protected permission grants, OTP/CAPTCHA, account login, secure keyguard, or biometric,
  open the exact owner screen, report the blocker, park durably, and resume after owner
  completion. Never claim Amara can bypass Android security.
- Prove screen-off wake, unlocked continuation, locked handoff, obstruction recovery,
  process death, reboot, and timezone behavior on the exact installed build.

Exit: raw dumpsys/logcat/screenshot/window evidence and typed receipts for every branch.

### Block 8 — truthful non-interfering overlay (Agent 3; Agent 4 reviews)

Render only canonical runtime states: Observing, Planning, Awaiting owner, Acting,
Verifying, Blocked, Failed, Reconciling. Exactly one compact window; collapse and disable
command interception while acting; preserve system/target controls; no secrets/customer
text; no Ready during active work. Prove portrait/landscape, keyboard, permission dialogs,
keyguard, app transitions, and process restart using window-manager plus visual evidence.

Exit: local lifecycle tests and exact-build Oppo visual/window evidence.

### Block 9 — controlled commercial activation ladder (Agents 1, 2, 3)

Do not jump directly to autonomous customer outreach.

1. Observe-only day: ingest real signals, create plan/report, zero external effect.
2. Draft-only day: exact drafts and approvals, zero dispatch.
3. Owner-controlled recipient: one approved follow-up; verify exact target/content,
   delivery evidence, approval consumption, state transition, and restart dedupe.
4. Limited supervised mode: owner-approved products/audience/channel, cap 1 per customer and
   conservative global cap; immediate opt-out/revocation; daily reconciliation.
5. Broader standing-policy mode only after acceptance criteria pass and owner explicitly
   enables it.

Any wrong target, unverified effect, policy bypass, secret leak, duplicate, unexpected spend,
or misleading claim triggers immediate stop-state and owner report.

Exit: production evidence for one full observe→plan→approve→act→verify→report cycle.

### Block 10 — revenue measurement and adaptation (Agent 2; Agent 4 audits)

Prove qualified inquiries, completed sales, direct/influenced/unattributed revenue, all cost
components, refund/cancellation correction, and UNKNOWN profit semantics from real evidence.
At day close, report target result, failures, unresolved effects, evidence links, what did
not work, and one bounded proposed change. Weekly/monthly claims require genuine elapsed
time and source records; do not backdate.

Exit: reconciled dashboard and owner brief whose totals equal the canonical ledger.

### Block 11 — freeze, install, and hostile final audit (Agent 1)

- Re-run full Kotlin and Flutter suites; parse fresh XML counts.
- Run progress, boundary, redaction, evidence, traceability, and negative-fixture gates.
- Build signed release after every production edit. Prove no production file is newer.
- Record APK SHA-256/signing certificate, install with `adb install -r`, pull installed
  `base.apk`, and prove hashes match.
- Re-run required Oppo gates after install; pre-install evidence cannot certify new code.
- Agent 4 challenges every claim and lists unsupported/downgraded rows.

## Required checkpoint record after every block

Append to the coordination board and execution log:

- block ID, owner, UTC start/end, exact objective;
- files read and changed, with final SHA-256;
- implementation notes and production call sites;
- every command, working directory, exit code, test names/counts;
- local/device/evaluation evidence paths and privacy classification;
- negative cases and independent reviewer result;
- requirements upgraded, unchanged, downgraded, or blocked;
- remaining blocker and exact next action.

## Final response contract

Return one of `COMPLETE`, `PARTIALLY COMPLETE`, or `FAILED`. `COMPLETE` is legal only when
every non-elapsed requirement is independently proven on the source-current installed APK
and all required elapsed trials have actually elapsed. Otherwise use PARTIALLY COMPLETE.

Provide:

1. requirement-by-requirement status table;
2. exact changed-file list with SHA-256;
3. full command/exit-code log and fresh test counts;
4. APK hash, signer, install time, installed hash, device identity;
5. Oppo gate table with raw evidence paths;
6. commercial cycle evidence chain from observation to owner report;
7. every failure found, fix attempted, retry/result, and unresolved state;
8. owner-dependent, engineering, device, external-service, and elapsed-time blockers;
9. intentionally untouched files/features;
10. hostile self-audit: quote each completion claim and the independent evidence supporting
    it; retract any claim lacking such evidence.

Do not stop because a local suite passes, a previous report says done, context is long, or
one agent finishes. Continue through the next safely executable block and keep status
truthful throughout.
