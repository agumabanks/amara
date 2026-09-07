# Owner-cadence and takeover repair — 2026-09-05

## Verified deployment and live result

- Built successfully; 45 targeted tests passed, zero failures/errors.
- Installed in place; APK SHA-256:
  `028e968aaebc50127847ff4b662cb086e862ea880c7fe804d3f68320442b4e63`.
- OPPO recovery helper passed: PID 3282, accessibility bound/not crashed, overlay
  allowed, sampled recent fatal/ANR count zero.
- Live logcat at 10:22:35, 10:23:06, and 10:23:36 EAT proves approximately 30-second
  execution cycles. Each reports blocked work kinds due to persisted circuit
  breakers. This verifies the heartbeat, NOT completed business work.

## Root causes found

- Waiting between wake events was labelled SLEEPING and could last 15 minutes.
- TikTok non-always-on publication had a hidden 17:00–20:59 restriction independent
  of owner quiet hours. The phone budget also had a separate hidden night restriction.
- WhatsApp notification proposals had no explicit 30-second owner-response grace.
- Awake foreign-app foreground could count as owner-active indefinitely.
- A score below 30 prevented starting a session even for an enabled owner schedule.
- TikTok retries reselected a product/regenerated a caption under the old transaction
  key. The live ledger rejected this at 06:47:12 UTC as changed content under a reused key.

## Implemented

- WAITING state and a maximum 30-second heartbeat, shortened to a pending due time.
- Newly observed inbound work persists its 30-second not-before deadline atomically
  with insertion. The existing already-answered check still runs before a reply.
- TikTok source and phone budget use configured quiet hours rather than hidden
  active-hour windows; the existing explicit always-on override remains unchanged.
- Stable readable foreground can become idle after 30 seconds without observed
  interaction. Fresh interaction still wins. This is an accessibility-based idle
  heuristic, not proof that a person is absent (e.g. someone reading without touching).
- Scoring orders work but no longer vetoes session admission below an arbitrary floor.
- Every item checks the live quiet-hours snapshot, not only the session-start snapshot.
- TikTok listing ID and caption are durably bound to the claimed queue item before
  publishing. Retry reloads that binding. Uncertain/rejected outcomes are escalated
  rather than automatically requeued; the side-effect ledger remains intact.

## Remaining live blockers

- At inspection, WhatsApp had 23 pending items and a breaker until 11:32 UTC
  (14:32 EAT); TikTok publication was in cooldown until 10:47 UTC (13:47 EAT).
  These persisted cooldowns were not cleared for appearance's sake.
- An earlier TikTok publish remains uncertain and needs read-only reconciliation.
- Soko inventory still reports no usable Terminal PIN.
- Timing is eligibility, not a hard real-time guarantee: secure lock, owner activity,
  consent, battery, budgets, network, and existing failures can defer execution.
- This repair does not certify new lead generation, autonomous shop edits, revenue,
  or successful end-to-end WhatsApp/TikTok canaries.
