# Employee workflow audit — 26 September 2026

Goal: audit community growth, Doctor, WhatsApp communication, and manager commands; repair subtle failures and validate against tests and OPPO evidence. Work remains active. Existing workspace changes were retained.

## Confirmed findings and source repairs

- WhatsApp queue deleted earlier pending questions/instructions from the same conversation and rejected distinct older notifications arriving out of order. Exact-key deduplication remains; distinct messages and unanswered reviews now survive.
- Doctor treated valid outbound jobs as malformed inbound routes because they had no conversation field. Valid target/message drafts now survive cleanup.
- Manager consultation parsing rejected lowercase `ask-` references. References now parse case-insensitively. Pending consultations are filtered to the current shop rather than allowing another shop to intercept a reply.
- A manager missed-call event could enter the command executor. Missed calls are excluded from command candidacy and handled before command execution. Ordinary customer text mentioning a missed call no longer becomes a call-only alert.
- WhatsApp reply/status/follow-up duplicate blocks were counted as success regardless of original receipt state. Only VERIFIED duplicates count as DONE; unverified duplicates escalate without replay. Follow-up commercial actions no longer become EXECUTED_VERIFIED on an uncertain duplicate.
- Community model deferral ended the task without timed recovery. It now defers five minutes without opening the publication breaker. Uncertain comments escalate rather than representing partial success.
- Community own-post exclusion compared only display names. It now checks normalized display name and handle variants.
- Another shop's latest observations could crowd scoped learning out of a limited query. Limit now applies after scope filtering.
- Comment reconciliation could inspect an old reservation under another account/shop. Original reservation scope and account are retained across later observations and checked before reconciliation.
- Doctor skip/failure state was invisible. Persisted last check reason/time and last completed session are now exposed in health snapshots and read-only observation export.

## Evidence

Initial four new regression tests all failed against the prior behavior. Expanded run: 97 tests, one failure; the remaining failure was an older assertion expecting first-message deletion. Updated it to require preservation. Final expanded run: 98 tests, zero failures/errors/skips.

OPPO baseline export: 226 inbound observations, 16 community results including ten MODEL_DEFERRED, two priority yields, two missing-profile results, one failed interaction, and one unreadable feed. Only one inbound_reply_result in this window; counts do not establish unique deliveries. No nightly cleanup event in the sampled daytime window is not proof of failure.

## Pending validation

Release candidate 87 / 0.10.74 is built. The TPS phone was reconnected and in-place installed/certified successfully. The OPPO transport dropped offline again and its previous TCP endpoint now refuses connections; no OPPO install was attempted after this reconnection. Live manager command validation needs an actual notification from the configured manager; no synthetic customer messages or public comments have been sent for testing. Existing uncertain group receipts remain held; broad live WhatsApp delivery and community interaction reliability are not yet certified.

Expanded validation completed: {'tests': 98, 'failures': 0, 'errors': 0, 'skipped': 0}. Build successful. A subsequent same-chat message-ordering repair needs its final regression run; final release build is being prepared.

## Release build continuation

The offline combined test/release build terminated at mergeReleaseNativeLibs because Flutter release runtime dependencies were not cached. No installation was attempted. Retried with network dependency resolution enabled; current process/log is artifacts/employee-audit-20260926/release87-online-build.log. OPPO Accessibility recheck: bound, crashed set empty. Existing APK metadata is still release 86 until this build succeeds.

Final same-chat ordering regression: 98 tests, zero failures/errors/skips. Mechanical side-effect boundary passes across 235 Kotlin source files. Release 87 / 0.10.74 completed successfully. APK SHA-256: `8fbb6cb2adc1360528d2caea1f5f474d7164e101c2f78ae0956a37b41b5bdfe1`.

## Current deployment state

The OPPO was connected and Accessibility was bound with an empty crashed-service set before the first deployment attempt. That remote transfer stopped receiving data after roughly three minutes; it was terminated without uninstalling or resetting app data. After the user's reconnection, the bridge responded, but the OPPO remained unavailable: its prior endpoint refused connection and it did not answer ping. The TPS phone accepted `adb install -r` for release 87 and passed the full certificate: APK identity, Accessibility, overlay, notification listener, battery exemption, foreground service, and zero recent fatal/ANR events. Certificate log: `artifacts/employee-audit-20260926/tps-release87-certificate.log`.

Fresh manager-command delivery was not claimed: the available OPPO journal contains no verified manager-command event for this audit window. Existing live evidence shows WhatsApp inbound processing and prior verified replies, but it cannot prove a new command was received and executed.

The earlier connectivity blocker was resolved on the next user reconnection: OPPO returned at `192.168.1.64:43385`, rather than its old port. Both devices were online.

## OPPO deployment and fresh evidence

Release 87 / 0.10.74 was successfully installed in place on OPPO. The slow transfer continued making progress and completed successfully. Installed SHA-256 matches the release artifact. Certificate: `artifacts/employee-audit-20260926/oppo-release87-certificate.log`.

Accessibility enabled/bound with no crashed services; overlay, notification listener, battery exemption, AgentService and foreground service all PASS. PID 20794, 95 scheduler matches, zero recent fatal/ANR matches in the certificate's sampled log window.

Fresh observation: `artifacts/employee-audit-20260926/oppo-release87-observation.jsonl`, build segment `amara-release-observation:1790440798125`. It includes a new inbound notification and a running work loop. The first cycle yielded because the phone was in use. The new Doctor diagnostic was present and reported `not_checked`; this is not evidence of a completed nightly run.

67 inbound jobs remain NEEDS_REVIEW: 43 exact contact-number verification failures, nine conversation-opening failures, eight replies-disabled results, three originating-conversation verification failures, two ambiguous manager responses requiring a consultation reference or explicit command, one uncertain delivery, and one destination failure without detail. Group blockers include uncertain prior effects and non-unique exact group search results. These historical receipts were not reset or replayed. No fresh manager-command execution or broad WhatsApp delivery success is claimed.

The earlier TPS TikTok VERIFIED receipt preceded installation of release 87; it demonstrates historical posting, not post-update validation. Likewise, pending queue entries alone do not prove a live Doctor cleanup succeeded. Regression validation remains 98 passing tests; deployment readiness is now certified on both devices, while these live workflow limitations remain recorded.
