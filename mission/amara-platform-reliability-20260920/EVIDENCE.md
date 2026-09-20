# Evidence and checkpoint log

Dates use Europe/Berlin unless specified. Earlier tool logs may use UTC or device EAT.

## Checkpoint 1 — 2026-09-20: mission established

Installed baseline: Amara **0.10.16 (29)**, OPPO CPH1933 / Android 11. Last observed wireless serial `192.168.1.64:42517`; rediscover before use.

APK SHA-256: `ce5090129fdb5913e59ff55ab24d081722964b3a27d29ec54e35de808cfbe6a4`.

Evidence already collected:

- Final TikTok-focused tests: **85 passed**, zero failures/errors.
- Separate preliminary Shorts policy/media tests: **5 passed**, zero failures/errors. These utilities are not a shipped Shorts module.
- Device certificate: Accessibility enabled/bound/non-crashed, overlay allowed, listener available, battery exemption, foreground service, 65 job matches, zero recent Amara FATAL/ANR evidence.
- Earlier full suite: 983 tests, 11 failures. Do not describe the full suite as green or all failures as independently established baseline defects.
- Earlier 0.10.14 journal: seven VERIFIED receipts among ten recorded TikTok dispatch attempts, plus two predispatch sound failures and one uncertain result. At least one published ad independently inspected. This does not certify the final 0.10.16 posting path.
- Backend DNS: phone and independent resolver returned NXDOMAIN for cards.sanaa.ug and sanaa.ug. Workspace resolution still fails at this checkpoint.
- Finished TikTok export: H.264 720x1280, video 12.000s, MP3 audio 10.213878s. YouTube import visibly selected about 10.2s. Duration repair remains open.
- YouTube details: channel/audience/description controls visible in screenshots but missing text in the captured Accessibility tree. No YouTube publication dispatched.
- Amara master power restored On at end of prior device inspection.

Current audit finding: Doctor's deepRepairGroups and closeAllReviewHolds invoke load while busy remains true; load exits immediately. Finding read from source; fix and regression test pending.

Private artifacts: `artifacts/tiktok-repair-20260919/` (gitignored). Reports link to evidence without exposing private journal contents.

## Update template

For each milestone append:

- Change and reason:
- State: implemented / tests passed / installed / live verified / blocked
- Tests and result:
- Release/device evidence:
- Remaining limits or external dependency:
- Next action:

Then update the matching MISSION.md checklist and PLAN.md next work.

## Checkpoint 2 — Doctor refresh fix

- Change: release the busy guard before refreshing status after group repair and closing review holds. Added a widget regression proving group repair triggers a second status read and removes the resolved repair control.
- State: implemented and tests passed; not installed.
- Validation: `flutter test test/doctor_screen_test.dart` passed (exit 0); `git diff --check` passed.
- Limits: this fixes stale Doctor UI, not all native health diagnoses or backend DNS.
- Next: shared receipt-backed module stats and dedicated YouTube settings/persistence.

## Checkpoint 3 — module implementation underway

- Added native YouTube settings and Flutter section, durable source queue/work source, transaction capability and scheduled executor, finished TikTok export binding, audio-tail normalization, local OCR fallback, and a queued preparation-only check run by Amara herself.
- Added shared durable task activity statistics, separate receipt-backed verified/uncertain counts, and module activity UI.
- Doctor now checks backend reachability separately from Android network availability; returning to Doctor performs passive refresh rather than an automatic repair.
- Native/UI tests and release build are in progress; none of these changes are yet claimed deployed or live-verified.
- Backend DNS now resolves from the workspace. Verify phone and authenticated catalogue path before declaring the incident resolved.
- Live acceptance will be initiated through the owner-facing preparation check and normal scheduler. Manual editor taps are not autonomous success evidence.

## Checkpoint 4 — release 0.10.17 deployed

- Focused Android suite: 91 tests, zero failures/errors. Flutter settings/Doctor: 9 passed.
- Release SHA-256: `0e9476975ae1fbe31b7d32c44e4c7a2832d238403331d247d258aab180f9e415`. Replace-installed on OPPO; version code 30.
- Certificate: Accessibility enabled/bound/not crashed, overlay/listener/battery exemption/foreground service PASS; 75 scheduled-job matches; recent FATAL/ANR 0.
- Dedicated YouTube settings and all-module statistics visible in installed Flutter UI. Media permission granted through Android prompt. Current signed-in channel entered for preparation test; soundtrack clearance remains false, so no automatic upload is permitted.
- Phone DNS still reports unknown host for cards.sanaa.ug; development machine resolves. This is not authenticated catalogue recovery.
- Subsequent repo refinements (not yet in release 30): preserve UNCERTAIN/VERIFIED queue states against reset; report rejected settings; record owner Doctor actions.

## Checkpoint 5 — autonomous check found missing historical metadata

- The owner-facing Shorts preparation check reported that the latest verified TikTok receipt had no source metadata in the work queue. No export or upload was attempted; this is a blocked check, not success.
- New verified TikTok posts already persist an independent Shorts payload. Preparation now consults that durable store before the expiring work queue; historical lost metadata is not fabricated.
- Phone network resolver fails cards.sanaa.ug, while Google and Cloudflare HTTPS resolvers both return current A records. The phone resolves/reaches dns.google. Implemented a system-first, configured-backend-only HTTPS DNS fallback with standard TLS verification; no backend IP is pinned and unrelated/private host lookups are not forwarded by the fallback.
- Implementation uses the matching OkHttp 4.12.0 DNS-over-HTTPS library; reference: https://raw.githubusercontent.com/square/okhttp/parent-4.12.0/okhttp-dnsoverhttps/src/main/kotlin/okhttp3/dnsoverhttps/DnsOverHttps.kt .
- Validation: 94 focused Android tests passed; 9 Flutter settings/Doctor tests passed. Full Android suite is running to reassess the supplied audit rather than assuming its reported failures are all current.
- Release 0.10.18 (31) is building. Master power was paused through visible Settings for replacement; restore ON after certification. YouTube automatic upload remains gated by soundtrack clearance=false.

## Checkpoint 6 — resumed mission, 0.10.18 installed

- Replace-installed 0.10.18 (31) on OPPO `192.168.1.64:42517`; APK SHA-256 `8ad0792468f45e62694f5bc7c38907fceacc57f0d59afdbc51288c69bc3b0718`.
- Recovery helper passed: process alive, Accessibility enabled/bound/not crashed, overlay allowed, zero recent detected FATAL/ANR. Master power observed On. Deployment log: `artifacts/platform-reliability-20260920/deploy.txt`.
- Full Flutter suite initially found four failures from outdated test expectations/bridge mocks. After correcting collapsed Soko navigation, operational-health map mocks and current work heading, **37 tests passed**. Log: `/tmp/amara-reliability-flutter-retest.log` (to archive with final checks).
- Source audit found audience/visibility were hardcoded. Added persistent settings and preparation/dispatch checks, plus attempted editor cleanup after failed preparation. These additions are not yet release-installed or live verified.
- Native full-suite run is in progress. Catalogue test fixtures now supply the required shop identity; production identity enforcement is retained. Boundary script currently passes across 205 Kotlin files.
- Requested confirmation of YouTube destination and soundtrack reuse permission; no reply yet, and upload permission remains held. Authenticated catalogue recovery and final-release publication remain unproven.

## Checkpoint 7 — authenticated catalogue restored

- Installed 0.10.18 Settings → Soko Access → Verify logged-in shop returned **47 active listings** for signed shop **708:128 (Sanaa Media)**. This is an authenticated in-app catalogue read through the configured backend, beyond resolver reachability. Evidence: `artifacts/platform-reliability-20260920/catalogue-check.xml`.
- Shorts preparation on this release refused the newest-updated historical receipt because it lacked source metadata, despite one pending queue record. No upload was dispatched. Source selection now considers retained verified publications in creation-time order; exact own-profile caption verification remains mandatory. Not installed yet.
- Targeted native retest passed after manager notification schema/truthfulness corrections and test fixture repairs. Full suites are rerunning. Latest Flutter run: 37 passed, including new audience/visibility control interactions.

## Checkpoint 8 — full test suites green before release 32

- Native full suite: **1,004 tests, zero failures/errors**, `./gradlew :app:testDebugUnitTest`; log `/tmp/amara-reliability-native-final.log`. Flutter full suite: **37 passed**, `flutter test`; log `/tmp/amara-reliability-flutter-final.log`.
- Fixed legacy ConversationEngine manager notification inputs and destination binding. Unverified relay/escalation no longer reports completed delivery. Updated authenticated catalogue fixtures; refreshed FileProvider attachment in sandboxed tests rather than widening production file sharing roots.
- Further release-32 safeguards require another native run: retain no-replay protection across channel changes/restart; recheck full description immediately before upload; independent persistent visibility/audience settings. Release **0.10.19 (32)** building with continuity signing.
- Installed TikTok controls observed enabled: posting, Stories, all-day cadence, public comments and analytics; interval **30 minutes**, cap **94**. These are observed settings, not proof all behaviors have passed live acceptance.
- A preparation-only tap returned missing historical source metadata; screenshot `artifacts/platform-reliability-20260920/preparation-tap.png`. No successful export/preparation is claimed.
- Live journal also shows owner review closures and phone navigation during this session. Requested an idle phone window to avoid confusing owner activity with autonomous evidence. Existing uncertain-publication history is retained.

- Final release-32 native test execution completed with **1,005 tests, zero failures/errors** before the release optimizer stage. Build command: `./gradlew :app:testDebugUnitTest :app:assembleRelease -PdeviceContinuitySigning`; ongoing log `/tmp/amara-reliability-release32.log`.
- Master power temporarily paused via visible Settings before replacement; restore On after final certificate. No uninstall, force-stop or direct protected-permission write was used.

## Checkpoint 9 — release 0.10.19 built

- Build and full native suite passed in one sequential Gradle invocation: **1,005 tests, zero failures/errors/skips**; build finished successfully in 7m32s. Archived log: `artifacts/platform-reliability-20260920/release32-build.log`.
- APK **0.10.19 (32)** SHA-256: `ae9018851540d3a451d7178722d9291101ba1a332ba12224372ba5e3a4f43dfa`.
- Release-32 changes: audience/visibility settings and verification; full description recheck before final dispatch; no replay across destination changes after interrupted uploads; source selection from retained verified metadata; held/skipped/partial stats separated; legacy manager notification schema and truthful outcome fix.
- Doctor on 0.10.18, while paused, completed the owner check without stuck busy UI, retained the owner-pause condition and exposed latest check details. Evidence: `doctor-paused-repair.xml` in the artifact directory. This is not a repair-success claim.
- Replace-install of release 32 started. Final certificate and preparation acceptance remain pending.

## Checkpoint 10 — 0.10.19 installed and certified

- OPPO wireless endpoint changed to `192.168.1.64:37623`; rediscovered the single connected CPH1933 before continuing.
- Installed **0.10.19 (32)** checksum matches the built APK: `ae9018851540d3a451d7178722d9291101ba1a332ba12224372ba5e3a4f43dfa`.
- Certificate PASS: Accessibility enabled/bound/not crashed; overlay; notification listener; battery exemption; foreground AgentService; 66 scheduler matches; zero recent detected FATAL/ANR. Evidence: `artifacts/platform-reliability-20260920/certificate32.txt` and `deploy32.txt`.
- Restored master power On through visible Settings. Shorts preparation and final-release publication acceptance are still being checked.

## Checkpoint 11 — release-32 live acceptance found unresolved faults

- New YouTube visibility (Public) and audience controls observed in installed UI: `youtube32-controls.xml`. Destination remains `@sanaasanaa1774`; soundtrack-clearance gate remains unresolved and was not enabled.
- Preparation-only check returned: “Verified TikTok posts have no retained source metadata. A new verified ad is required for preparation.” Evidence: `preparation32-response.png`. Two pending queue rows are visible, but their correspondence to retained verified receipts is unproven. No successful export, editor preparation or YouTube upload is claimed.
- Release-32 observation contains a TikTok attempt with `PICKER_OPENING` → `PICKER_STALLED` → `sound_selection_unconfirmed`; transaction reports final external trigger never dispatched. Evidence: `release32-journal.jsonl`. Backend restoration alone has not completed TikTok acceptance.
- Read-only UI hierarchy capture coincided with Accessibility destroy/reconnect messages; final system inspection showed bound service and empty crashed set. Future autonomous acceptance should avoid hierarchy instrumentation during action execution and use passive journal/screenshot evidence.
- Master power is On. No YouTube upload/editor was opened by the failed preparation check. No uncertain receipts were erased by this work.
- Outstanding: diagnose live sound-picker layout/loading, reconcile pending Shorts source identities against verified ledger keys without fabricating metadata, then run final source/export/metadata checks. Confirm channel and soundtrack permission before actual YouTube publication. Final mission remains ACTIVE, not complete.

## Checkpoint 12 — sound recovery follow-up in progress

- Connected OPPO `192.168.1.64:37623`, installed 0.10.19 (32); Accessibility initially bound with empty crashed set. Master power observed Off, contrary to the earlier checkpoint. Preserving the pause during preparation/build.
- Current TikTok picker exposes four usable rows through Amara's read-only diagnostic. Manual selection attached “Fire Up the Place” and returned to the composer. This is manual UI evidence, not autonomous acceptance.
- Live failure remains correlated with missing Accessibility observations; existing picker click revalidation also compared every decorative descendant. Updated revalidation retains track/artist, target path, bounds and same-window checks while allowing decorative animation. Picker now observes bounded stage progress, allows 30 seconds of idle recovery within a 90-second hard ceiling, distinguishes absent windows/rows/rejected taps, and stops explicitly on owner pause.
- Five new regression cases cover decorative changes, changed artists, transient/prolonged missing windows and owner pause. Focused sound/Shorts suite: **40 tests passed**, zero failures/errors/skips. Full native suite and continuity-signed release 0.10.20 (33) building.
- Owner explicitly confirmed @sanaasanaa1774 and permission to reuse the soundtrack on YouTube for the final upload test. Clearance enabled through visible Settings; master power remains paused during build. No upload dispatched yet.
- Evidence directory: `artifacts/sound-shorts-20260920/`. Final deployment and autonomous acceptance still pending.

## Checkpoint 13 — release 33 installed; Shorts root cause proven

- Release 0.10.20 (33) built with **1,010 passing native tests** and installed by replacement. SHA-256 `c21a0e567ee8f5a0f7f501f22d1c0e6361bb4051f42c5f9fb6f289b948b19876` matches the installed APK. Device certificate: enabled/bound/non-crashed Accessibility, overlay, notification listener, battery exemption, foreground service, 67 job matches, zero recent detected FATAL/ANR.
- Read-only source diagnostics establish that startup SecretScrubber changed timestamp-bearing transaction IDs while ShortsQueue retained the original IDs. The deterministic historical redaction of the original source produces exactly the observed verified receipt identity (hashed evidence retained privately). The retained source metadata was present; the earlier missing-metadata conclusion was incorrect.
- Release 34 adds identity/digest preservation during scrub, deterministic legacy receipt lookup with ambiguity held as uncertain, source recovery without fabricated receipts, newest-source-first export, and legacy no-replay checks. Evidence text remains scrubbed. Story source lookup uses the same receipt resolution.
- Manual YouTube import of an existing TikTok export reached full 12-second trim, editor, exact @sanaasanaa1774 channel and metadata controls. No upload was tapped. Cleanup exposed a second “Delete edits?” / “Continue” dialog; release 34 handles it explicitly.
- Initial release-34 test run failed only in four new test fixtures due to Robolectric SDK-28 AutoCloseable incompatibility; corrected test setup and rerunning. No failed build installed.
- A TPS450M also connected during work. Subsequent commands explicitly target OPPO `192.168.1.64:37623`.

## Checkpoint 14 — release 34 installed and certified

- **0.10.21 (34)** built with **1,014 native tests passing**, zero failures/errors/skips. Side-effect boundary clean across 205 Kotlin files; `git diff --check` passed.
- Replace-installed APK SHA-256 `8caba099a101b2366aa24d58e278cff41cc78054cb87bf918bd8810a98c8e976`, matching device bytes. Certificate: Accessibility enabled/bound/not crashed; overlay; notification listener; battery exemption; foreground AgentService; 78 scheduler matches; zero recent detected FATAL/ANR.
- Release-33 passive journal establishes five verified TikTok posts with confirmed soundtrack selection, alongside two missing-window preparation failures. Most recent two sound selections attached “Level Up” and ended in VERIFIED publication. This supports the sound repair but does not establish immunity to Accessibility interruptions.
- Release-34 read-only diagnostics show retained metadata linked to the five recent verified feed receipts. Seven prior Shorts attempts are held; no upload was dispatched. Preparation test is next.

## Checkpoint 15 — owner follow-up: public contact, cadence and cleanup

- Owner requests remaining OPPO/TikTok cleanup, restoration of the public WhatsApp line in video ads, and investigation of the 10-minute cadence. Existing YouTube destination/upload permission remains authorized.
- Root cause of missing contact: scheduled feed and Story generators passed an empty public-contact fallback to TikTokProductContent. Both now pass the configured public ad WhatsApp value consistently. Catalogue-specific contact still takes precedence; no private/manager number is inferred. Existing creative fingerprint includes this contact, so new ads render with a new binding.
- Cadence previously waited a full interval after execution finished, accumulating rendering/upload time. The next due time now advances on the original interval grid and skips missed slots without catch-up bursts. Owner use, power, daily cap, Accessibility and circuit-breaker holds still apply and cannot promise exact publication completion every 10 minutes.
- Added regressions for 10-minute cadence with render delay/missed slots and public-contact serialization/fingerprinting for video ads. Release 0.10.22 (35) building and testing.
- Release-34 preparation was accepted through the visible control after master power On. Latest observed loop yielded to phone use and processed existing WhatsApp work; Shorts outcome not yet captured.
- Remote ADB bridge at localhost:5038 stopped responding during the next log pull; even device-list/get-state commands stalled. No service force-stop, data deletion or remote-host changes made. Requested connection status from owner; continue local validation while live work is unavailable.

- Remote bridge recovered and exposed the OPPO at **192.168.1.64:42585**; verified CPH1933 and bound/non-crashed Accessibility. Visible Settings confirms public ad WhatsApp **+256706272481**, TikTok posting enabled and Stories enabled. Subsequent large observation transfer is slow/intermittent; avoid hierarchy instrumentation during active execution.
- Release-35 full native suite: **1,016 tests, zero failures/errors/skips**. Release optimization pending. Boundary check passed across 205 Kotlin files.

## Checkpoint 16 — final fixes and mission handoff

- Owner requests built-in Accessibility recovery and a full mission folder with plan, charts, progress checker, learning record and other-agent tasks. Created `mission/amara-oppo-recovery-20260920/`, linked from parent mission. Its progress states distinguish implementation/tests/deployment/live acceptance.
- Added owner-visible Accessibility recovery popup on foreground/Amara On when permission or service connection is missing; opens Settings and checks on return. No secure-setting writes or silent permission grant. Off remains authoritative.
- Final release 35 includes bounded exact-source own-profile search for Shorts, multiline/delayed details-channel recognition and sound frame diagnostics, beyond the first successful 1,016-test release-35 build. Rebuilding those final edits before installation.
- Latest observed release-34 journal includes one verified TikTok post, a missing-window preparation stop and an uncertain upload. Preserve the latter receipt; do not replay it automatically.

## Checkpoint 17 — continuity release 37 on both devices

Newer artifacts supersede the release-35 handoff: 0.10.24 (37) was built successfully with 1,021 passing native tests and clean side-effect boundary across 206 Kotlin files. Replacement-installed on CPH1933 and TPS450M; both installed checksums match `62b09a1a8c1f5e40c167dbcfd9c9514ac0ba4b279571ad7513ed8614c88a2e92`. Both retain enabled/bound/non-crashed Accessibility, overlay, notification listener, battery exemption, foreground AgentService and scheduler jobs; storage permission remains granted. Detailed certificate process caveat and evidence are in the follow-up SESSION.

OPPO release-37 preparation passed on @sanaasanaa1774 without uploading and cleaned up the editor. The run created a fresh TikTok-owned export; ffprobe proves H.264 video 12.000 s and AAC audio 12.006 s. Thus finished-media download/full-duration soundtrack acceptance has live evidence. Actual publication still pending. Owner-selected audience, visibility, caps and soundtrack policy preserved.

TPS read-only catalogue verification returned 46 active listings for Adora Classic Wears 328:41; OPPO remains Sanaa Media 708:128. TPS Shorts is disabled and unconfigured, so OPPO authorization must not be generalized to TPS. Artifacts: `artifacts/posting-recovery-20260920/`.

## Release 38 continuation

Both OPPO CPH1933 and TPS450M now run continuity-signed 0.10.25 (38); 1,030 native tests passed. Installed SHA256 matches `9626487f87744bde75a83a17a029323e675bad97b7438661dc2666605250cef2` on both. Service certificates passed, preserved Android 11 storage permission, no recent detected app FATAL/ANR. Evidence bundle: `artifacts/tiktok-health-20260920/certificates38.json`. TPS verified one feed publication with attached soundtrack (`tps-outcome38.jsonl`); OPPO recorded readable community posts. Final OPPO post/cadence/Shorts upload acceptance remains pending; historical receipts and holds preserved. See follow-up SESSION.md for current checkpoint.
