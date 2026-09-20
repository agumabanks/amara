# Amara Doctor and continuity audit — 14 September 2026

This is a repair and readiness audit, not a certification that every external workflow now succeeds. Prior verified sends, current phone readiness, and unresolved outcomes must be reported separately.

## Findings and changes

| Finding | Change | Evidence required to resolve |
|---|---|---|
| Legacy group alert keys survived later successful sends | Reconcile old and current destination keys using group success time; retain resolution history | Same group, success later than the failure; unrelated/newer failures remain |
| Normal group schedule waits appeared as failed deliveries | Reclassify the exact normal-wait result without claiming delivery | Recorded scheduling state |
| Health verdict was captured before service recovery | Read current conditions again after recovery; remove duplicate unthrottled notification path | Current service and loop state |
| Background work could lose its session when screen time was exhausted | Separate non-screen session allowance from screen budget | Background admission regression test |
| Home/Chat attention card lacked a repair route | Doctor screen with check-and-repair, relevant controls and evidence history | Fresh native snapshot after repair |
| Group check had no owner-facing result | Queue a no-send conversation check and persist its observed result per group | Matching conversation opened; this does not establish posting permission or delivery |
| Saved group names containing phone digits were altered by database scrubbing | Treat contact IDs and display names as identity fields; still remove credential shapes | Repeat-scrub regression; no credential leakage |
| Existing redacted group name could not match WhatsApp | Restore only an exact, unique redaction fingerprint from original owner configuration; preserve permissions and row ID | Exact original configured value; no fuzzy expansion |
| Truncated or stale saved name had no correction path | Owner can correct the complete saved name, then run an access check | Full WhatsApp name and later check |
| Market research button only woke the loop | Queue Jiji/Jumia work with prerequisites, queue-cap and error feedback | Queued status is not collected evidence |
| Market comparison could use a stale shop catalogue | Read current promotable offerings and scope the displayed growth review | Fresh logged-in shop identity |
| Market UI overclaimed verification and hid refresh failures | Saved-observation label, source freshness, explicit research scope, visible failures and Doctor route | Source observations and timestamps |
| Settings was a long flat wall of controls | Collapsible sections; power remains prominent | UI analyzer and device checks |

The health worker already schedules independent checks every 30 minutes, including offline. WorkManager timing is approximate; Android can defer it. The Doctor does not override owner Off, Android permissions, low battery, lock screen, thermal limits or ambiguous send outcomes.

## Live group audit before this update

The OPPO UI showed: Announcements (no matching recipient), BandaChristianFellowship (picker_next), Business Centre Uganda (older DNS failure), Cash My Clutter Ugand… (truncated identity), CK Phones Accessories And Electronics (phone digits replaced by redaction markers), DR HILLARY KATANDIKWA, MAKINDYE WEST MP (recipient not unique), FINTECH AFRICA (picker_next), and other configured groups with mixed historical outcomes. CUSCCC and General had historical successful deliveries; those historical timestamps are not proof of current delivery. Further observations are retained in the local audit artifacts.

## Production gaps requiring live evidence

- All configured groups have not yet been certified for successful delivery. Complete names, membership and posting rights must be established for unresolved groups; uncertain sends cannot simply be replayed.
- The OPPO shared screen budget was 90 minutes and exhausted. This can hold WhatsApp and research while a separate TikTok allowance remains. Continuous availability requires an appropriate owner-configured budget, alongside power and OS availability.
- TikTok audio selection now requires visible selected-track evidence. Audible playback on a newly published automated video and sustained cross-device publication still need observation.
- Manager notifications have earlier verified sends; a real manager reply becoming a completed authorized command remains a live validation gap.
- Jiji research is currently scoped to Printers & Scanners; Jumia uses public storefront capture and requires configured vision consent/model. Arbitrary category research is not certified.
- Soko product edits and confirmed-order workflows were not newly certified by this Doctor UI release.
- A successful install and unit tests cannot guarantee OS scheduling, app UI stability or uninterrupted operation indefinitely.

## Validation

First Android batch: 65 tests passed. Three focused Flutter tests passed (Doctor repair/resolution, Home blocker persistence, research prerequisites). Analyzer on the six changed screen areas reported no issues after cleanup. Final build and device certificates are recorded separately under `artifacts/amara-doctor-20260914/`.

## Additional auto-reply audit

The group permission callback received a shop-prefixed memory key, while the contact directory lookup requires its canonical group ID. Corrected both permission and group-context lookups to use the resolved contact ID; conversation memory remains shop-scoped. Added a regression that exercises the actual reply engine and asserts its directory lookup without sending or calling a model.

Full inbound queues previously ignored rejection. They now create a durable owner-visible auto-reply blocker and a redacted rejection event, explicitly stating that the received message has no scheduled reply. This does not imply that queue space later becoming available delivered the missed reply.

The OPPO maximum screen time was increased to 1440 minutes/day through its Settings UI and read back as 1440. Existing quiet hours and device protections remain. TPS Terminal 2.0.5 (2030) was installed successfully; its prior version was 2.0.4 (2029).

The expanded auto-reply batch passed 85 tests with zero failures/errors, including notification parsing, outgoing-message filtering, durable reply drafts, tone/acknowledgment handling, group permission identity, blocker persistence, directory restoration and work scheduling. Both OPPO and TPS screen budgets were read back as 1440 minutes/day.

OPPO pre-install auto-reply event counts (deferrals can repeat for the same message, so these are not customer/message counts): 294 low-battery deferrals, 123 exhausted-screen-budget deferrals, 53 circuit-breaker deferrals, 26 exact-destination-unavailable results, 7 expired-shop rejections, 7 original-message visibility failures, and 6 expired notification-route failures. TPS export contained no auto-reply work events. These distinguish scheduling/phone availability from recipient routing failures.

## Installed release

Amara 0.10.9 (22), SHA-256 `2ef897b50849be5f8961879657e03673ad83725ddea8dd4619f9e5d60df0cd5d`, installed with data retained on OPPO `192.168.1.65:38493` and TPS `192.168.1.66:5555`. Both full device certificates passed: running foreground service, Accessibility enabled/bound/not crashed, overlay allowed, notification listener enabled, battery exemption, scheduled jobs present, and no recent fatal/ANR reported. Installed hashes match the built APK. Both devices remained On.

Live UI validation: OPPO Home opens Doctor; manual health check updates its last-check timestamp and reports actual held groups/cooldowns. Both Settings screens show compact expandable sections and owner On. TPS Market displays saved observations, explicit source scope, empty-evidence states and the research action. Screenshots/XML and certificates are in the artifact folder.

The live OPPO Doctor still reports old held group outcomes and active cooldowns. This is not an all-green production certification. A fresh manager-command exchange, newly verified direct customer reply, all-group delivery checks and sustained TikTok audio/publication observation remain necessary to close those workflow-level gaps.

A post-install screenshot showed the detailed Doctor health summary pushing action cards below the fold. The final UI adjustment collapses this summary under “Latest check details”; its widget regression passed. This adjustment was made after the certified APK above. The ADB bridge subsequently accepted connections but timed out on the basic `host:version` request, so the last device state must not be inferred from an unresponsive connection. Deployment status of this final presentation-only adjustment is recorded below when verified.

Final layout deployment completed after the bridge recovered at OPPO `192.168.1.67:39501` and TPS `192.168.1.68:37305`. Both devices now have the final 0.10.9 (22) APK, SHA-256 `fbada4d4fd69ee44076818e1af17569b71621ec36a5140bf90b6482e8086d60d`. Both final certificates passed all service/permission checks with matching installed hashes and zero recent fatal/ANR. The collapsed-report widget test and Doctor analyzer passed. See `oppo-final-certificate.txt` and `tps-final-certificate.txt` in the artifact folder.

## Follow-up gap repairs — candidate 0.10.10 (23), not yet installed

The following changes address additional defects found while reviewing the open gaps:

- Group outcomes now retain the originating work key and dispatch state. Automatic recovery requires a durable no-effect receipt, no unresolved group dispatch, fresh catalogue access and a matching conversation. Historical failures without dispatch evidence remain held.
- Shared bounded Terminal recovery reopens its existing session only inside admitted screen work, then rereads signed identity. It does not invent a session or bypass sign-in. Cancellation stops recovery.
- Manager escalations, task results, booking alerts and health reports use a durable outbox when queue admission fails. Queue admission is distinct from verified WhatsApp delivery.
- Jiji search no longer invokes a WhatsApp send helper or returns to category browsing. The public Jiji editor submits the requested term and matching query evidence is required before collecting results. The Market screen accepts a product query. Both market adapters check whether their actual packages are installed.
- Jumia research can collect accessible cards without a vision model. Inaccessible cards still require the existing configured vision consent/model.
- Soko field editing selects an explicitly labelled editable field, rejects ambiguous/unknown fields, reads fields while scrolling, and compares complete field values. Renames reopen the new title. The workflow no longer puts every approved value into the first editor.
- Soko edit proposals and application use the same capability ID and bind approval content to the shop. The runtime edit runner uses the shared business guard and rechecks the shop immediately before saving. Old unscoped approvals remain invalid.

The first focused Kotlin batch passed 19 tests and the Market/Doctor widget tests passed. The expanded final test/build results are pending below.

Device limitation: ADB currently lists no devices; connection attempts to the last OPPO/TPS addresses were refused. The existing installed 0.10.9 remains the last certified device build. No new group send, manager reply, order mutation or TikTok playback has been verified in this follow-up. All-groups delivery, real manager-command execution, sustained TikTok audio/publication and real product-edit/order workflows remain live validation gates. Order confirmation is still explicitly routed to an exact approved workflow, rather than assuming a confirmed order from a conversation.

Final candidate regression batch: 38 Android tests passed, zero failures/errors/skips; 2 Market/Doctor Flutter widget tests passed, and Market analysis reported no issues. XML reports and logs are retained under `artifacts/amara-gap-closure/`.

Remaining source limitations: the generic workflow edit route still requires a shop-bound workflow input; the module approval route now supplies that binding. Recovery/startup manager notices have not all migrated to the new outbox. Missing manager-number configuration creates an actionable blocker but requires reviewing the originating task after configuration. These are not certified as fully recovered workflows.

Candidate release build completed successfully in 6m 2s. APK: `artifacts/amara-gap-closure/amara-0.10.10-23.apk`; SHA-256 `c332fd807b1c263b4016bca90346f9ec96ca7ff31152a0f41a50aeb29e582789`. Version metadata confirms 0.10.10 (23). This candidate is **not installed**: both last connected addresses and the two mDNS-advertised addresses refused ADB connections. Installation and live certification require reconnected phones.

## Reconnected-device deployment of 0.10.10 (23)

Installed with data retained on OPPO `192.168.1.65:39555` and TPS `192.168.1.66:5555`. Both installed hashes match `c332fd807b1c263b4016bca90346f9ec96ca7ff31152a0f41a50aeb29e582789`. Both certificates passed Accessibility enabled/bound/not crashed, overlay, notification access, battery exemption, foreground service and scheduled-job checks; recent fatal/ANR count was zero in the checked window. Fresh observation exports confirm `ownerOn=true`, `running=true` on both devices.

Fresh exports include historical unresolved failures, not proof of new candidate failures: OPPO WhatsApp identity/session errors and uncertain comment work; TPS sound/share preparation errors and TikTok identity failures. Both report missing owner timezone for the internal commercial cycle. OPPO has 176 needs-review queue entries; TPS has two pending entries. These were not bulk-cleared or replayed. No new external delivery, audible publication or completed manager command was certified during installation. Evidence is in `artifacts/amara-gap-closure/`, including `post-install-status.json`.

## Home, Work and Market UX — 0.10.11 (24) candidate

Home now uses a compact attention card with owner-action priority, a complete scrollable issue sheet and a Doctor action. It includes operational-health issues and preserves a visible stale-status warning if refresh fails. Chat's health preview is condensed, with the complete details in Doctor.

Work supports swipes plus visible buttons: approve/reject proposals after reviewing the exact description, resolve held work, and archive pending work. Pending archival is an atomic status-checked update and refuses claimed tasks. Records remain available and future scheduled runs are not cancelled.

Market opportunity, comparison and evidence cards open detail sheets. Comparisons include a validated percentage reduction and exact UGX preview. The action sends an owner request to prepare a proposal, verify current shop/product/price and wait for approval. It is not a direct price-write endpoint. Trend actions request dated research and audience-specific post suggestions; they do not claim demand from listing counts or automatically publish. Request results and ambiguous failures are displayed. Live execution of those owner requests remains to be verified.

Health sweeps now include overlay, battery exemption, notification listener and notification permission, missing Terminal installation, and all current work blockers. A persistent history tracks recurrence and recovery attempts. Only a later health observation can resolve a condition; a recovery request alone cannot. This provides recurrence evidence, not a guarantee of automatic repair for every failure. Manager health messages include a next step and distinguish requested recovery from verified recovery.

Validation: six initial widget tests and three follow-up responsive/Home/Work tests passed (seven distinct tests); analysis of changed screen/widget areas found no issues. Native final regression/build and device checks are pending below.

0.10.11 validation completed: 59 Android tests passed, zero failures/errors. Release build succeeded in 5m 33s. Candidate SHA-256 `b20b7c12473af2361fad56226826413cdde1e0be6df2c26cb9f18576755f3856`. Reports and APK are retained under `artifacts/amara-ui-20260914/`.

Final Home correction: show “Review” when issues exist and avoid a duplicate health action. Both follow-up widget tests and Home analysis passed; final release rebuilt successfully in 4m 19s. Installed the final 0.10.11 (24) APK on OPPO and TPS, SHA-256 `f3cd7a00a596072afada9cd1a59f1c22ac4a53bd128e779fd22cd0857f7453c1`. Final live screenshots confirm the Review badge on both, the full attention bottom sheet on OPPO, and a real saved Market opportunity detail sheet with the trend/post-suggestion action. No price mutation or new external message was performed for UI testing.

Observed remaining conditions: TPS reports no configured manager WhatsApp number and no saved market observations; OPPO reports an expired Terminal shop session and has 52 saved Jiji observations. These are visible blockers, not claimed resolved by this visual update. Recurrence tracking supplies repair evidence; it does not guarantee every future issue is automatically fixed.

Final device certification completed: both installed APK hashes match the final build, both foreground services are running, Accessibility is enabled/bound/not crashed, overlay and notification access are enabled, battery exemption and scheduled jobs are present, and recent fatal/ANR counts are zero in the checked windows. Both remain On. Certificates: `oppo-final-certificate.txt` and `tps-final-certificate.txt` under the UI artifact directory.
