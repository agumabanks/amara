# Reliability gates: preserve working behavior, close observed gaps

This addendum is mandatory for every phase. It strengthens the mission; it does not claim a universal fix or guarantee future platform compatibility.

## Fresh baseline before fixing

The owner reports WhatsApp has improved. Historical failure attempts must not be treated as current distinct defects. P0 must segment fresh evidence by device, app build, platform version, binding and settings revision. Record installation boundary and journal coverage; start a fresh 24-hour observation on each current build. While collecting it, reproduce failures with safe fixtures and inspect code. Do not downgrade working devices or change working settings merely to reproduce old errors.

Each observed signature gets: first/last seen, attempt count, distinct job count, distinct target count (pseudonymous), eligible opportunities, last successful comparable operation, current-build recurrence, build/version, evidence and next action. Pair success and failure traces of the same operation. Distinguish historical, confirmed-current, unconfirmed, fixed-pending-verification, verified-fixed and expected-policy behavior. No activity is not proof of a fix. Existing working cases become regression fixtures before editing their paths.

`issue-register.json` is the required coverage ledger. Its observation rows cover every work-kind/failure-class pair in the captured summaries; focused rows add known reasons and safety gaps. Rows may overlap and must not be summed as unique incidents. New recurring signatures require new rows. Audit PARTIAL, ESCALATED, NEEDS_REVIEW, UNCERTAIN, SKIPPED, deferred and accepted-without-terminal-outcome as well as FAILED. A successful health check does not prove its monitored operations are healthy.

## Queue recovery and fairness

Classify failures by cause and dispatch evidence, not wording alone. Dependency unavailable → scoped cooldown with bounded attempts and explicit next retry. Proven pre-dispatch transient → bounded retry with original logical job ID. Possible dispatch → reconcile or review, never automatic replay. Permanent/configuration problem → visible actionable hold. Expected schedule/cap/Off deferral → no failure counter and no alert storm. New inbound information can supersede uncommitted work; preserve both history and effect-ledger evidence. Automatic recovery records trigger, attempted repair and verified result separately.

Lease expiry recovers abandoned internal work, not blindly re-sending external work. Test cancellation and process death between claim, preparation, send, verification and queue commit. No single group/conversation/platform hold can globally block unrelated eligible work. Exercise competition among inbound replies, groups, TikTok publishing, community interaction, Shorts and internal maintenance. Measure accepted-to-start delay and oldest eligible job; give eligible inbound work service within two configured scheduler budgets after the current non-preemptible action ends. Report fairness delays caused by quiet hours, user activity or genuine resource limits separately.

## Mandatory completion subgates

P1 has five independently evidenced tracks: W1 inbound/long replies, W2 group ads, C1 community engagement, Q1 queue isolation/recovery, H1 health alerts. Each requires fixtures, negative tests and device-specific live evidence. Parent P1 cannot close while any track is blocked. Later infrastructure phases may prepare independent designs while this gate is pending; they must not be reported as delivered by association.

Every issue closure references evidence/<report>.md. `verified_fixed` requires current-build success under the original trigger and a recurrence observation window. `expected_policy` requires proof of the configured policy and absence of unintended external effects. `historical_not_reproduced` requires comparable eligible work on the current build, not elapsed idle time. `blocked` remains unresolved with owner, dependency and next safe step. No “accepted risk” silently counts as repaired.

Before the final complete state: no open/blocked issues; every observed signature classified; all P1 tracks and P0–P8 verified; both devices complete the defined soak; reconcile unique effect receipts to admin counts. A scope change needs an explicit owner decision and revised acceptance criteria rather than deleting failing rows.

## Soak and regression rules

For P1 canary, use an owner-designated test conversation and group: three distinct short replies, two long replies (including interruption/restart), two scheduled group opportunities, one failed group followed by a healthy group's opportunity, and community cases in COMMUNITY_GROWTH.md. Repeat on both compatible devices. If a device lacks an enabled/authorized module, mark its live gate blocked with that reason; no fabricated activity. Synthetic fixture coverage complements but never substitutes for live delivery gates.

Observe 24 hours per changed build before P1/P2 acceptance and seven days for final rollout; include at least two naturally eligible schedule opportunities for each enabled periodic flow. A longer configured interval extends the observation or uses a separately authorized test schedule restored afterward. For low traffic, report sample counts and limits. Do not generate unsolicited messages to fill a quota.

Zero wrong-recipient, cross-shop, unapproved external actions or duplicate external effects in acceptance tests. Compare current-build p50/p95 response delay, verified/eligible completion, retries/job and queue age to the fresh baseline. Any deterioration requires investigation and documented resolution before rollout; no blanket percentage claims from tiny or mixed samples. On severe regression stop the new capability, preserve receipts, and use the existing rollback procedure.
