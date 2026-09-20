# Session record

Detailed earlier checkpoints: [parent evidence](../amara-platform-reliability-20260920/EVIDENCE.md). Private raw screenshots, journal exports, build logs and certificates: `artifacts/sound-shorts-20260920/` (gitignored).

| Release | Build/tests | Device evidence |
|---|---|---|
| 0.10.20 (33) | 1,010 native tests passed | Replacement installed; certificate passed; five verified TikTok posts, two missing-window preparation failures |
| 0.10.21 (34) | 1,014 native tests passed | Replacement installed; certificate passed; another verified TikTok post, later missing-window failure and uncertain upload |
| 0.10.22 (35) | Final build: 1,016 passed | Replacement installed and certified; live publishing acceptance pending |

## Findings and changes

- TikTok row decorations/animations changed while the track stayed identical. Selection now checks stable title/artist identity, row/control paths and bounds against a fresh frame. Composer sound and remove controls remain required for confirmation.
- Added bounded progress-aware sound waits and explicit missing-window, missing-row, rejected-tap and owner-pause outcomes. Added diagnostics distinguishing inaccessible windows, traversal limits and child retrieval errors. Intermittent missing frames remain a live validation item.
- SecretScrubber redacted timestamp digits in transaction identity fields. A deterministic legacy redaction reproduced the stored receipt key: source metadata was present, contrary to the initial missing-metadata hypothesis. Preserve identity/digest fields; resolve exact legacy keys; treat conflicting receipts as uncertain. Never fabricate historical success.
- Shorts export selected only the latest profile post, although its verified queued source could be older. Search a bounded six recent own-profile posts with exact caption verification. Keep source, shop, channel and media bindings.
- YouTube manually reached full 12-second trim, editor and Add details on the authorized channel. Automated details-channel recognition now separates combined label lines and waits for the channel to load. Handle the explicit Delete edits/Continue dialog. These observations did not establish a published Short.
- Scheduled feed and Story builders supplied an empty public WhatsApp fallback. Both now use the configured public ad number, while a listing-specific number takes precedence. Fingerprint changes prevent reuse of creative cached under a different contact.
- Cadence added an entire interval after rendering/upload finished. Now the next due advances on the original interval grid and skips missed slots without bursts. Owner activity, power, daily cap, Accessibility and breaker holds still apply.
- Added foreground Accessibility recovery prompt when Amara is On and access is not connected. It opens Android Settings, checks again on return, suppresses repeated prompts during the same outage and respects Off. No silent permission grant.
- ADB bridge became unresponsive, then recovered with OPPO endpoint changing from `192.168.1.64:37623` to `192.168.1.64:42585`. The second connected phone is TPS450M; all device commands must explicitly target verified OPPO.
- Hierarchy instrumentation during autonomous operation correlated with service interruptions. Prefer passive screenshots and read-only observation exports during live work.
- No force-stop, uninstall, application data reset, invented receipt, or blind retry of an uncertain publication was used. No YouTube upload has yet been verified.

## Evidence hashes

- Release 33: `c21a0e567ee8f5a0f7f501f22d1c0e6361bb4051f42c5f9fb6f289b948b19876`.
- Release 34: `8caba099a101b2366aa24d58e278cff41cc78054cb87bf918bd8810a98c8e976`.
- Record final release 35 checksum only after the final build and installed-byte comparison.

## Supported OPPO cleanup

Doctor's obsolete-media review offered one reclaimable 0.7 MiB bound video. Selected it and executed the existing revalidated cleanup. Doctor confirmed: one obsolete file removed, 0.7 MiB reclaimed; changed/protected items retained. Screenshot: `artifacts/sound-shorts-20260920/cleanup-result35.png`. This occurred on installed release 34 while final release 35 built. No review holds were mass-closed.

Final source native suite: 1,016 tests, zero failures/errors/skips (`final35-tests.json`). Side-effect boundary check passed across 205 Kotlin files. Release optimization still in progress at this checkpoint.

## Final release installation

Release 0.10.22 (35) final build completed in 7m16s. Replacement installation succeeded. SHA-256 `aed3f9f5da10bfdf5c580b51608571be0a7944032f8baeed68276cf5de89bb6d` matches installed bytes. Certificate35: Accessibility enabled/bound/not crashed; overlay allowed; notification listener; battery exemption; foreground AgentService; 69 scheduler matches; zero recent detected app FATAL/ANR. Master power restored On through visible Settings. No recovery popup shown while Off or while connected, as intended.

Visible YouTube owner settings at final-install check: authorized channel, audio cleared, Public, made-for-kids On, two-hour interval, daily cap 11. Preserve these current selections. The made-for-kids setting differs from the earlier manual check. Preparation-only work was accepted; initial loop yielded to recent owner/UI activity. No final-release publication is claimed at this checkpoint.

## Final-release Shorts preparation passed

Preparation-only work key hash `0a46cb0d396503d120543f8cde981382d5266726ea8b6133f379ef681571d10c` completed DONE at device time 1789917843622 on build segment `amara-release-observation:1789917567219`. Visible Settings confirms “Preparation verified for @sanaasanaa1774; no upload dispatched.” Evidence: `preparation35-result.png`, `recent35c.jsonl`. Exact channel/details preparation and cleanup passed; this is not publication evidence. Ten historical sources remain held. The earlier profile screenshot belonged to queued TikTok comment work, not the Shorts test; the journal disambiguated it.

Doctor “Check & repair now” ran on release 35 at device-local 18:25:28. It retained genuine outstanding WhatsApp group workflow holds, including a picker-next failure and Terminal-session recovery failure. It did not claim every module healthy. Evidence: `doctor35-summary.png`. These group blockers are separate from the restored public ad contact and are recorded for follow-up rather than erased.

## Final-release TikTok and public contact verified

Queued opportunity hash `539b43dc6877b9739db9752b2c41a5a43bc6ca5422cd91f1127c4dc9ae2dc561` started at 1789918070939, 452 ms after its not-before deadline. Sound stages progressed through PICKER_VISIBLE, ROWS_AVAILABLE, TRACK_TAPPED, ROW_SELECTION_CONFIRMED and SELECTED; “Bonfire of the Moon” attached and selection_verified=true. All frames reported service_connected=true with no frame failure. `external_effect` recorded VERIFIED `post_tiktok` at 1789918197314. Screenshot `tiktok35-progress.png` shows the newly published Self-Inking Numbering product video, price UGX 95,000 and public WhatsApp +256706272481. Evidence journal: `tiktok35-outcome.jsonl`.

This verifies the final build's sound and public-contact path on one real post. It does not prove every future TikTok UI or two-cycle cadence acceptance. Existing failure receipts remain preserved.

## Export follow-up and TPS handoff

Owner clarified Shorts must use the finished TikTok video with its added soundtrack, automatically saved or explicitly downloaded from the exact post. Owner authorized the next agent to update TPS450M 192.168.1.67:5555 and restore Accessibility and required permissions.

A second release-35 TikTok post verified at 1789918478664 with soundtrack “Dans Met My”; its video also shows the public WhatsApp line. Actual Shorts work stopped before upload at 1789918604851 because the download control was unavailable. Manual paused-device inspection proves an enabled, clickable Download button in TikTok's Send to sheet (export-share.png and export-panel.xml). This establishes availability, not the exact reason the earlier check failed.

Replaced the 700 ms one-shot check in SOURCE with TikTokExportControlWait: 24 bounded polls with 500 ms intervals, owner/service/package guards, and return after first accepted tap. Four regressions cover delayed controls, foreign package, owner cancellation and missing window. Targeted suite: 12 tests, zero failures/errors/skips; BUILD SUCCESSFUL. Boundary check passed across 206 Kotlin files; git diff --check passed. Log: artifacts/sound-shorts-20260920/export-wait-tests.log.

This follow-up has NOT been built into a new release or installed. OPPO remains on certified 35/0.10.22. TPS has not yet been modified. NEXT_AGENT_PROMPT.md contains the precise remaining scope, preserved fixes and deployment authorization. Actual Shorts publication and live export-wait acceptance remain incomplete.

Handoff power state: OPPO master power restored On through visible Settings; screenshot handoff-restored.png confirms On at device-local 18:49. No YouTube upload was manually dispatched during the inspection. The new wait source change remains uninstalled; do not confuse installed release35 behavior with the tested follow-up.

## Continuation: newer release state reconciled

The prior handoff was stale. Initial live inspection found **0.10.23 (36) on both OPPO and TPS450M**, with Accessibility bound on both. A completed **0.10.24 (37)** build was already present (`artifacts/posting-recovery-20260920/release37-build.log`). Its full native results contain **1,021 tests, zero failures/errors/skips**. The boundary log reports 206 clean Kotlin files. Release 37 adds captured TikTok 47.0.3 TPS composer/remove-sound control support; the bounded export wait is also present. APK SHA-256: `62b09a1a8c1f5e40c167dbcfd9c9514ac0ba4b279571ad7513ed8614c88a2e92`. Both installed release-36 APKs and release 37 have matching signer certificate SHA-256 `83a1760330649b8de634583dde0a3ec853751fcdddea41fbf2cf026315960729`.

Initial master state: OPPO On, TPS Off. OPPO paused visibly for replacement installation; TPS pause preserved during deployment. Newer evidence reports OPPO Terminal-session failures, but a subsequent read-only check at device-local 20:24 verified shop 708:128 and 47 listings. Recheck current state; do not infer ongoing authentication failure from older journal blockers. Shorts queue has 15 held sources; latest recorded export failure was exact-source lookup before dispatch. No publication claim follows from these observations. Release-37 installation/certification is in progress.

## Release 37 installed; OPPO preparation passed

Both replacement installations completed. OPPO certificate reports enabled/bound/non-crashed Accessibility, overlay, notification listener, battery exemption, foreground AgentService, 78 scheduler matches, zero detected recent app FATAL/ANR. TPS reports the same services with 96 scheduler matches; its certificate process timed out during the final repeated Accessibility query after printing all checks, so a separate `tps-access37.txt` confirms bound and empty crashed set. Both installed-byte SHA-256 values match release 37. Android 11 storage read permission remains granted on both.

OPPO master On restored visibly. Existing YouTube settings preserved: @sanaasanaa1774, clearance On, Public, made-for-kids On, two-hour cadence, daily cap 11. Preparation-only key `e48eca5adc3496cac0ec4c9031c9c25856812d7b9726b0fbc18e272192aef723` was queued through visible Settings and the resulting UI confirms preparation verified with no upload dispatched (`oppo-prep37-current.png`). This is not publication. Historical 15 held sources remain intact.

TPS Settings shows Shorts disabled, no destination, soundtrack clearance Off. Preserved this device-specific configuration. Its read-only catalogue check verified **Adora Classic Wears, shop 328:41, 46 active listings** (`tps-verify37.png`), distinct from OPPO Sanaa Media 708:128. TPS master was Off at continuation start and remains paused during reversible UI inspection.

Preparation key completed DONE at device epoch 1789926154785 on release37. A fresh TikTok-owned download appeared during that exact preparation window (MediaStore 282123, creation epoch 1789926104), unlike older automatic saves. Read-only pull and ffprobe confirm H.264 video **12.000 s**, AAC audio **12.006 s**; soundtrack is present and full video duration is retained. Evidence: `oppo-prep37-result.jsonl`, `oppo-media37.txt`, `oppo-export37-tracks.json`. This establishes the explicit Download path worked in the new release, without proving slow sheet readiness was the earlier cause.

## TikTok posting and community follow-up (release 38 in progress)

Fresh journals from both phones found repeated `NO_RELEVANT_POST` / observed=0 community cycles marked DONE, and recent pre-dispatch `share_foreground` posting failures. TPS also retains an uncertain upload; do not replay it. Both devices initially had master On and bound/non-crashed Accessibility. Owner confirmed both phones available for uninterrupted inspection. Both were paused through their visible Amara Settings switches.

OPPO TikTok 46.9.3 captured expanded-caption controls use `ej4` (caption), `lej` (comment entry), and `z9p` (close); comment rows use `f4d`, editable field `ejc`, and timestamp/status `enw`. Existing aliases did not recognize these. Source now retains legacy IDs and adds observed variants. Captured sanitized fixture and regression assert caption/creator/entry/close recognition. A zero-readable-post scan now reports a blocker instead of successful empty discovery; genuine irrelevant-post scans and priority yields remain distinct.

Posting previously required nonempty accessible text within ten seconds just to accept the TikTok foreground. A dedicated bounded import wait now recognizes a TikTok-owned loading window and lets the existing composer readiness checks handle loading, respecting owner Off, service availability and foreign-app transitions. No publication gate was removed. Added four import regressions. This is a candidate repair, not yet live certification of the historical cause. Failure diagnostics now include service and surface observations.

Artifacts: `artifacts/tiktok-health-20260920/`. Baseline summary: `baseline.json`. Full native suite passed: 1,030 tests, zero failures/errors/skips (`tests38.json`). Boundary check passed across 209 Kotlin files. Continuity-signed 0.10.25 (38) build is in progress. Do not claim installed/live verification until the next checkpoint.

## Release 38 deployed on both phones

Continuation confirmed the completed release38 build (5m21s), 1,030 passing native tests and clean boundary check across 209 Kotlin files. Continuity signer matches prior releases. Both devices replacement-installed **0.10.25 (38)**, APK SHA-256 `9626487f87744bde75a83a17a029323e675bad97b7438661dc2666605250cef2`; both installed-byte hashes match. Certificates: `artifacts/tiktok-health-20260920/oppo-certificate38.txt` and `tps-certificate38.txt`. Both report enabled/bound/non-crashed Accessibility, overlay, notification listener, battery exemption, foreground AgentService, and zero recent detected app FATAL/ANR. Scheduler matches: OPPO 72, TPS 105. Android 11 storage read grant preserved. No permission recovery was needed.

OPPO endpoint is now `192.168.1.64:41903`; TPS remains `192.168.1.67:5555`. Master power restored On through visible Settings on both after installation, consistent with intended normal-operation handoff. OPPO Shorts settings currently show authorized channel, audio clearance On, Public, made-for-kids On, **one-hour** interval and cap 11; current settings preserved. Queue retains 15 held sources. Release37 preparation success does not imply upload or release38 live acceptance. Fresh observation export shows OPPO feed opportunity held by existing circuit-breaker until 19:12:47 UTC; TPS has started its pending feed opportunity. No holds or receipts reset.

Release38 TPS feed opportunity `1bd63f74cea26ba2551f3ff465e399088eeac1956d8565d4945b06d8b0509cde` reached VERIFIED at epoch 1789931212661 with attached track “Shk Numan Muhammad Mbogo - original sound”; DONE outcome follows. Evidence: `tps-outcome38.jsonl`. OPPO `oppo-outcome38.jsonl` records real community `post_read` and relevance decisions on release38; final community result still being collected. The first attempted preparation-button tap overlapped autonomous navigation and did not prove a queued check; no success inferred from that tap.

Release38 OPPO preparation-only key `cc1731d9393c6fa40e5318dbcb4c64fcb6692ee9cb7f19bbab206aafb0460ac7` completed DONE at 1789931324528 (`oppo-check38.jsonl`), after the visible queue confirmation in `oppo-prep-request38.png`. Cleanup completed; this is not an upload. OPPO community completed with two readable posts, both irrelevant (`NO_RELEVANT_POST`, observed=2), so it is now evidence-backed discovery rather than the former zero-read false success.

TPS community remains unreadable: observed=0 now correctly produces FAILED/UI_MISMATCH with the explicit caption blocker. Its subsequent Story stopped before dispatch with `sound_selection_unconfirmed`; diagnostics show service_connected=true and no frame_failure throughout. Track tap reached CHECKING_COMPOSER but attached_sound_confirmed remained false through COMPOSER_STALLED. Evidence: `tps-check38.jsonl`. Do not claim service disconnection as this Story's cause or replay its feed receipt.

OPPO release38 scheduled feed opportunity `ea954c5baa98fe048aed761fdf1365092bb1977e3510b6005b893bb077b9c031` started at 1789931567465, 16 ms after its not-before 1789931567449. ACTING at 1789931614405, attached “King Cobra” confirmed at 1789931632473, VERIFIED at 1789931659527, DONE at 1789931659700. Due-to-verification: 92.078s; pre-ACTING preparation: 46.940s (includes rendering, not an isolated renderer measurement); ACTING-to-verification: 45.122s. Evidence `oppo-publish38.jsonl`. Both phones now have release38 verified feed outcomes. Two-cycle cadence and actual YouTube upload remain outstanding.

## Expanded photo reader source-navigation repair (release39 candidate)

After release38 feed verification, actual Shorts attempt escalated before dispatch: exact source lookup failed; queue now 16 held. Paused OPPO through visible Settings and inspected retained TikTok state (`export-inspect38.xml/png`). It was the expanded photo reader: caption `skr`, share `sk7` labelled Share, comment input `sk0`, no Profile tab and no labelled Back. Existing own-profile navigation only handled Profile or Share video, so this captured state cannot progress. A single manual Android Back returned to a regular feed with a visible Profile tab (`export-search38.xml`). This establishes a supported navigation gap; it does not independently prove every historical lookup failure had this cause.

Source candidate adds TikTokProfileReturn recognition for this exact captured layout, allows at most two bounded Back recoveries, checks owner/service on every profile-navigation poll, and refuses comment drafts/foreign apps/incomplete layouts. Exact source caption and publication checks remain unchanged. Sanitized captured fixture and regressions added. Candidate version 0.10.26 (39); full native suite running. Boundary check passed across 210 files. OPPO power restored On after inspection. Release38 remains installed on both until build39 is explicitly installed.

## Continuation: release39 tests and fresh release38 evidence

Release39 native suite completed: **1,033 tests, zero failures/errors/skips** (`tests39.json`); boundary checker passed across 210 Kotlin files. Release build started. Both phones initially remained release38 with bound/non-crashed Accessibility. OPPO endpoint is now **192.168.1.66:38615**. A temporary bridge interruption recovered after targeted reconnect; no phone permission changes were needed. Both phones paused through visible Amara Settings for deployment.

Fresh OPPO journal (`oppo-recent39.jsonl`) shows two natural feed opportunities: key 569f4530… started 1789937096164, ACTING 1789937174896, VERIFIED 1789937212479; key 0ca2b65a… started 1789937714660, ACTING 1789937769755, VERIFIED 1789937816479. Starts were 618.496 seconds apart. Preparation before ACTING was 78.732 / 55.095 seconds, and ACTING-to-verification 37.583 / 46.724 seconds. These are two successful natural cycles, but eligible_at=0 does not establish the exact original grid deadlines. No isolated renderer duration is inferred.

A newer release38 Shorts attempt reached final_details then ACTING at 1789937328651 and FAILED at 1789937329485: external trigger was never dispatched. Later source-lookup failures persist. Queue now reports **20 held · 1 pending**. Preserve all holds. OPPO visible settings remain @sanaasanaa1774, soundtrack cleared, Public, made-for-kids On, one-hour interval, cap 11. TPS recent journal records a community timeout and a feed rejection from owner Off during deployment pause; neither is a verified new post.

## Release39 installed and certified

Version **0.10.26 (39)** replacement-installed on OPPO CPH1933 `192.168.1.66:38615` and TPS450M `192.168.1.67:5555`. SHA256 **ce4453cf204fb9636187d15fa0a2b12b912848220eb42a867345df4687ba34a3** matches installed bytes on both. Signer SHA256 remains 83a1760330649b8de634583dde0a3ec853751fcdddea41fbf2cf026315960729. Build command: `./gradlew :app:assembleRelease -PdeviceContinuitySigning`; initial packaging omitted this flag and failed for missing distribution-key password, then continuity packaging succeeded in 43s. No key or permission changes.

Both certificate39 files report enabled/bound/non-crashed Accessibility, overlay, notification listener, battery exemption, AgentService foreground, zero recent detected FATAL/ANR. Scheduler matches: OPPO 78, TPS 89. Android 11 READ_EXTERNAL_STORAGE remains granted on both. Master On restored through visible Settings on both. Live release39 source-navigation/upload acceptance still pending.

Release39 preparation-only task `43789c2c49831bfbcd79b1b3319955c627d9df4eccbfdc5770442f2c3b0b08c1` completed DONE at 1789938987779, with details/channel/title/description/audience/visibility/final_details stages and editor cleanup. Fresh export MediaStore 282764 appeared at 1789938952 during this task; screen shows full 12.0-second Bamboo Flasks video and public contact. Evidence `oppo-prep-result39.jsonl`, `media39.txt`, `oppo-prep-running39.png`. This is preparation, not publication.


## Continuation: current power discrepancy and fresh read-only evidence

At phone-local 2026-09-21 00:51, OPPO `192.168.1.66:38615` visibly shows Amara **Off** on the YouTube Sanaa 24 channel page. This supersedes the earlier recorded On state; the reason for the pause is unknown. Requested owner clarification before resuming autonomous work. No taps, uploads, setting changes, installs, hold resets or receipt changes were performed in this continuation. No upload editor is visible in the captured state.

Both OPPO and TPS `192.168.1.67:5555` freshly report Accessibility bound and empty crashed sets. This is a partial current health check, not a new full certificate. Fresh observation export completed; large historical pull was interrupted because of slow transfer, and first screenshot timed out. Successfully collected a small recent journal tail (`artifacts/continuation39-oppo/latest.jsonl`) and complete second screenshot (`current2.png`), plus paused-device hierarchy (`current.xml`). Source diagnostics retain 20 held and 1 pending source, exact key `0ca2b65aa859931037dcca417407a675429b1b4317268459425e748d7ac97ae7`. Channel thumbnails do not establish an exact-source automated publication. Actual Shorts publication remains unverified. No source-code change or new release is justified from this evidence alone.

Owner then explicitly authorized: “Resume Amara and continue the test.” Resumed via the visible Settings power control; `artifacts/continuation39-oppo/resumed.png` confirms On and enabled Shorts with @sanaasanaa1774. Fresh `active.jsonl` confirms ownerOn=true and scheduler running; publication work key `54135783a90ca7164558d137bb9c1f8f078b601b3ad99de2754965617b342a46` is queued. Both current package reads confirm 0.10.26 (39). No additional permission question is needed for this authorized test.
