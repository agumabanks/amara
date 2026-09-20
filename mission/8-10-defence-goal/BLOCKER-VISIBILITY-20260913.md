# Persistent blocker visibility — 2026-09-13

Owner requested explicit failure reasons and owner-action alerts on every device, without a failed task stopping unrelated eligible work. OPPO reconnected at 192.168.1.66:37785; TPS at 192.168.1.64:41923.

## Root cause and change

The autonomous loop stored a single lastSummary. Idle cycles, owner-presence yields, and unrelated success could overwrite a failure or battery explanation. Admission deferrals did not enter the repeated-failure alert path.

Version 0.10.4 (17) adds durable unresolved blockers independently of lastSummary. Home shows task, observed reason, owner-action/recovery state, next step, continuation scope, and last-observed time. Android notifications do not require WhatsApp. Alerts use a stable per-blocker notification ID with a 30-minute repeat interval; resolution cancels the alert. Battery is measured even during dashboard refresh/owner activity, and clears only above 15%. Heat is measured from battery temperature.

Task failures and budget/governor/phone-availability deferrals are recorded. Retry wording follows the actual recovery decision; dropped/escalated tasks never promise an automatic retry. Failures with missing reasons explicitly say the reason is missing and the outcome unconfirmed. Same-channel/destination success clears its failure; unrelated tasks and different destinations cannot erase it. Entering another attempt clears a wait, not an unconfirmed failure. Existing scheduler isolation remains in place. Device-wide low-battery/heat pauses are distinguished from task-specific blocks.

Both devices retain owner On and existing account data. No battery state is spoofed and no safety threshold is lowered. Screen geometry context from the preceding fix is included in this release.

## Validation and deployment

Backend: new blocker persistence, notification deduplication/cancellation, 15-to-16% boundary, missing failure reason, destination isolation, and truthful recovery disposition tests; existing scheduler integration suite checks admission and unrelated background continuation. Flutter widget test checks blocker visibility alongside idle status and removal after resolution. Final results and live evidence follow.

Final tests passed: 5 WorkBlockers tests, 52 autonomous-work integration tests, and 1 Flutter Home blocker widget test. Release build succeeded in 4m59s.

## Live installed result

Both TPS and OPPO now run 0.10.4 (17), APK SHA-256 `72624d828835e4f4dd5357071557c5eb82b64d474f29d9c29584928e8bfecf3f`. Replace-install succeeded with data retained. Both processes are alive, Accessibility enabled/bound, crashed sets empty, overlay allowed, and deployment checks report no recent FATAL/ANR.

OPPO Home visibly shows: Device battery / Owner action needed; battery 11%; autonomous work pauses at <=15%; connect a reliable charger; automatic resumption above 15%; all autonomous work waits for this device condition. Android notification ID 1320173801 matches the deterministic device:battery notification, in agent_action at importance 4. This is real device state, not an injected failure. TPS Home shows the loop online with no current blocker. Both On. No new post is claimed on OPPO while battery remains low.

Evidence: artifacts/blocker-visibility-20260913/ contains install certificates, Home XML, OPPO screenshot, build/widget logs and exported journals.
