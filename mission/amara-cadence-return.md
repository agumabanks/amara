# Sanaa Media: publication cadence and task handoff

Live learning logs repeatedly recorded `TikTok daily cap reached`. This is a concrete blocker independent of the interval setting; it does not prove that all previously counted publications are currently visible on TikTok. Historical circuit-breaker records also contain long cooldowns. The current evaluation reports no configured commercial policy, so proactive sales follow-ups still require policy setup and individual contact consent.

Implemented:
- Honor the configured TikTok gap exactly (30 minutes), rather than hidden 20–42 minute randomization.
- Advance successful opportunities by the full interval; terminal failed opportunities get a five-minute next-opportunity check. Recoverable retries retain their durable key and due time until their queue disposition is terminal. Uncertain external sends retain their no-repeat ledger protection.
- Prioritize eligible inbound replies, then due publications, then existing permitted follow-ups, ahead of optional research. TikTok browsing yields between posts when inbound or due-publication work is pending.
- Cap TikTok kind-wide failure cooldown at 30 minutes; inbound retains its two-minute cap. Owner-disabled autonomy, device health, quiet hours, media validation and send verification remain enforced.
- Persist task outcome and owner-visible summary before returning to Amara after screen work. Return is skipped during owner activity and is bounded to five seconds including device-lock acquisition. This is a foreground handoff; logging itself never required opening Amara.
- Apply the owner's requested 30-minute schedule with a 48-publication daily ceiling via a shell-protected debug configuration action, without clearing any transaction history.
- Publication cap checks use local calendar-day boundaries and expose count, cap and interval in the failure reason.
- Updated operating brief makes the scheduling order, truthful reporting and permitted lead follow-up explicit.

Evaluation continues in the existing five-hour window. New phone events carry `build_segment=cadence-return-v2` and the configuration change is a separate journal event. Do not treat this as a single unchanged-build unattended experiment.

Validation/deployment evidence will follow. No new publication or revenue result is claimed merely from code changes.

The first build passed 74 focused tests. A final rebuild includes the last foreground-return verification and terminal-disposition cadence corrections. Read-only audit database copies are private under `/tmp/amara-audit/cadence-*`. The evaluation remains active; deployment will be an intervention within that window.

Due-publication and inbound priority is applied before the SQL candidate limit, so a large high-value background backlog cannot hide those tasks. Foreground handoff events distinguish verified return from an unverified return attempt.

Final validation: 74 focused tests passed with zero failures/errors; `git diff --check` passed. Installed SHA-256 `f569988715c561a1b4fac104f75fabdce0e1e0c62aafd9c0544115ebfe0478f1`, version 0.10.0 (13), PID 7069. Installed checksum matches the built APK. Accessibility enabled/bound/not crashed, overlay, notification listener, battery exemption and foreground service passed; 58 scheduler matches, zero recent fatal/ANR entries.

Device log confirmed `Owner cadence applied: interval=30 minutes; daily cap=48` at 12:26:18 device-local time. Inspection pause cleared. Collector received new `cadence-return-v2` events. Deployment interventions are separately recorded in the existing evaluation directory. No new publication or live foreground return is claimed at this point; both await task evidence.
