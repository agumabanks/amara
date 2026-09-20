# TikTok posting repair — 2026-09-19

## Confirmed diagnosis

Connected device: OPPO CPH1933, ADB `192.168.1.64:41809`; installed Amara 0.10.13 (26), TikTok 46.9.3.

The exported journals contain 49 TikTok preparation failures across retained releases: 19 `sound_selection_unconfirmed`, 11 `sound_selection_unverified`, 11 `composer_timeout`, two each of `sound_selection_timeout`, `automatic_sound_not_assigned`, and `share_foreground`, and one each of `sound_editor_skipped_unverified` and `terminal_shop_changed_or_expired`. These are historical preparation failures, not proof that Post was dispatched. Recent failures explicitly say the external trigger was never dispatched. The work loop subsequently returns to Amara, explaining the observed backing out.

Live inspection of the unfinished ad reproduced these mismatches:

- Sound sheet ID changed from `g0z` to `g1i`; its accessible description remains `Bottom sheet`.
- Sound removal ID changed from `e0m` to `e0z`; its accessible description remains `Close`.
- Tapping a track moves its title, changes the author/count layout, and inserts `Cut` and favourite controls. Neither the title nor row becomes accessibility-selected/checked.
- Dismissing the sheet leaves the chosen track plus the removal control attached to the editor. Next opens the caption screen with `Add description...` and `Post`.

Accessibility was bound with no crashed service, overlay allowed, notification listener live, battery exemption present, WorkManager jobs present, battery 80%, network available, and posting budget available. These were not the immediate failure cause. Other WhatsApp/manager/timezone issues in the diagnostic export are separate from this TikTok repair.

## Changes

- Detect the sound sheet and sound-removal control using scoped accessible descriptions while retaining the previous known IDs.
- Accept stable trim-control appearance on the same track/artist as a reason to dismiss the picker. Require two observations of the exact attached track in the composer before proceeding.
- Support picker auto-dismissal, already-attached sound, temporary missing windows, and different window IDs during navigation. Keep exact live window/node validation at action dispatch.
- Compute the dismissal point from current sheet/window bounds and avoid labelled controls. No OPPO-specific screen coordinates are used in production.
- Emit specific sound-stage failures rather than returning silently.
- Refresh the signed Terminal identity before preparation and the final action; refuse changed shops or unsuccessful authentication refresh.
- Explicitly implement `Closeable` on WorkQueue for Android versions where SQLiteOpenHelper does not implement AutoCloseable. This fixes the scheduler-test runtime cast on Android 9 without weakening its priority assertions.
- Build version 0.10.14 (27).

## Verification

Initial targeted run: 68 tests, one failure in the Android 9 WorkQueue cleanup cast. After repair: 76 targeted TikTok/scheduler tests passed. Captured-device regression tests reproduce the new IDs, shifted track title, absent selected flags, and distinct editor/picker window IDs. Additional checks cover scaled screens and picker auto-dismissal.

Full-suite, release build, and deployment results will be appended after completion. No test post has been dispatched during manual inspection. Public test-post authorization was requested separately.

Private diagnostic evidence and APK backup: `artifacts/tiktok-repair-20260919/` (git-ignored). Source regression fixtures: `app/src/test/resources/tiktok/46.9.3/`.

## Limits

The repair targets the captured TikTok layouts and the previously supported layout; scaled fixtures do not certify every Android/TikTok version or language. A soundtrack shown as attached is UI evidence, not an acoustic recording of the uploaded video. Public publication requires a separate confirmed receipt, not merely a successful composer transition. Existing unrelated workspace edits were preserved.

## Full-suite result

983 tests ran: 972 passed and 11 failed. All 76 tests in TikTok/media/scheduler-isolation classes passed, including the additional screen-scaling and auto-dismissal regressions. Remaining failures are recorded in `artifacts/tiktok-repair-20260919/full-suite-failures.json`: boundary scanner (existing wrapper/name matching), artifact share-root configuration, connector timeout, three WhatsApp production-path tests, two broadcast reporting tests, Soko fallback, and two structural worker/settings checks. The scheduled posting path calls its primitive inside the transaction runner's `act` lambda; its sound failure is not caused by the static scanner. This repair does not claim the whole suite is green.

The read-only observation export now includes `tiktok_sound_surface`, reporting whether the live native accessibility adapter recognizes the editor, attached sound, and picker rows. It performs no navigation or publication.

Owner authorized proceeding with the single live catalogue-post check. Read-only profile inspection found another changed obfuscated ID (`f16`) on video tiles. Latest-post navigation now recognizes clickable direct children of the observed GridView with a play-count node, still excluding drafts; prior known IDs remain supported. This avoids tying publication verification to one TikTok build's tile ID.

Final targeted rerun after diagnostics/profile navigation changes: **79 tests, zero failures/errors/skips**. The 983-test full-suite run and its 11 failures are retained separately; the additional three tests were included in this final targeted run.

## Deployment

Release build passed. Replace-install succeeded on OPPO CPH1933 / Android 11 at its new wireless endpoint `192.168.1.64:38425`. Installed version **0.10.14 (27)**. APK SHA-256: `c321e23d4b275e582cdb07b86afe9d88569b17fbe7ad7908d8ccfa681ff9ac2d`; installed checksum matches. Existing signing identity retained; no uninstall/data reset.

The recovery helper briefly observed an unbound service and opened Settings; accessibility rebound without a settings toggle. Subsequent repository device certificate passed: enabled/bound accessibility, empty crashed set, overlay allowed, notification listener enabled, battery exemption, foreground AgentService, scheduled jobs, and zero recent Amara FATAL/ANR evidence. See `certificate.txt` and `deploy.txt` under the artifact directory.

Amara's original On state was restored after inspection and installation. One live posting attempt is under observation; publication is not yet claimed.

## Extended live observation and second hardening pass

The 0.10.14 journal later recorded 10 TikTok dispatch attempts: seven VERIFIED receipts, two pre-dispatch sound failures, and one UNCERTAIN upload. A later Terminal-session failure occurred before dispatch. The latest own-profile ad was read independently: Self-Inking Stamp — RECEIVED — Red Ink, UGX 35,000, full shopping caption, and attached sound “Soyayya Har Abada”. This establishes real live posts; it does not establish universal reliability.

The follow-up patch normalizes encoded sound titles; safely dismisses the picker to inspect composer evidence even when selection flags/trim controls are absent; introduces progress-based idle/hard deadlines; removes the unrelated Terminal-home/PIN requirement from authenticated session recovery; refreshes the same shop again after rendering; recognizes an already-open own profile; and requires exact read-only caption plus post controls after own-profile navigation for publication verification. Caption text in an editor and matching truncated prefixes no longer suffice. No uncertain upload is automatically replayed.

YouTube Shorts is the next requested feature, gated behind TikTok stabilization. Its audio policy must distinguish rights to reuse TikTok-added music from use of the original ad with platform-appropriate audio. The owner has been asked for that preference.

## September 20 follow-up

Owner chose original ad with music added in YouTube, rather than reusing TikTok-added audio. YouTube currently shows Sanaa 24 (@sanaasanaa1774); channel selection was asked separately and remains configurable.

Installed 0.10.15 (28), SHA-256 `78a63c507f28d823a0f24b7fccad626c83142ad812f9a2534a2a3c10f6c2cf9c`. Device certificate passed all service checks with zero recent Amara FATAL/ANR evidence. Overnight work was legitimately held at 10% battery; device is now charging. A later master-Off state also held scheduled work.

A final timing audit found the capability transaction allowed only 180 seconds for preparation plus verification, although each stage could need 180 seconds. Set TikTok transaction ceiling to 360 seconds and work-item ceiling to 480 seconds to leave time for rendering/session recovery. Progress-based idle limits still end stalled observations promptly. Added a regression asserting the nested deadlines fit. **82 targeted tests passed**, zero failures/errors. Release 0.10.16 (29) includes this timing correction; build/deployment result pending.

The Story audit also found mismatched content generation: feed used catalogue contact while Stories applied a separate public-contact override, invalidating fingerprints. Stories now derive the same content, require a VERIFIED source feed transaction from the same shop, refresh that shop after rendering, and supply scope/target/content to the transaction policy. The narrow channel policy admits scoped TikTok Stories and still rejects missing or changed scopes. Regression coverage includes Story admission.

## Live external blocker and final validation

On the September 20 OPPO check, the first new feed-post attempt failed before entering the TikTok editor: `Unable to resolve host "cards.sanaa.ug"`. The phone's ping and this workspace resolver both failed. Google Public DNS returned status 3 (NXDOMAIN) for both `sanaa.ug` and `cards.sanaa.ug`, with authority at the `.ug` zone. Other domains (including `soko24.co`) resolved. This does not establish the registrar-side cause; domain/DNS restoration or a verified replacement backend is required. No phone private-DNS override was configured or changed. Evidence: `public-dns.json`. Preparation DNS/connect/timeout exceptions are now classified as TRANSIENT_NETWORK rather than UNKNOWN; transaction uncertainty remains non-replayable.

Final targeted validation: **84 tests passed**, zero failures/errors/skips. `git diff --check` passed. Full-suite limitations recorded above remain.

YouTube investigation reached native video trim and the Shorts editor from an existing original ad preview through ACTION_SEND. Captured editor exposes semantic `Add sound`, `Next`, `Exit editor`, and `Volume` labels. No YouTube upload was dispatched. The screen returned to the account page during the sound-picker inspection; that transition was not explained by the captured logcat. Concurrent phone use was queried. Shorts implementation and live certification remain pending, respecting the requested TikTok-first sequence; original-ad plus YouTube-native audio is the selected policy.

The final dispatch boundary now also checks the composer preparation deadline before either Post or Your Story. This prevents a slow sound-picker substep from consuming the upload-verification budget. Story transaction/work ceilings are 240/420 seconds, including its 45-second confirmation window and rendering allowance; feed ceilings remain 360/480 seconds. Idle limits remain independent of these ceilings. The YouTube implementation handoff is documented in `youtube-shorts-design.md`; it is explicitly not a completed feature.

## Final installed TikTok release

**0.10.16 (29)** installed by replace-install. APK and installed SHA-256: `ce5090129fdb5913e59ff55ab24d081722964b3a27d29ec54e35de808cfbe6a4`. Release build passed; final TikTok-focused suite **85 tests, zero failures/errors**. Device certificate passed enabled/bound/non-crashed Accessibility, overlay, notification listener, battery exemption, foreground service, 65 job matches, and zero recent Amara FATAL/ANR evidence. Full-suite limitations remain as recorded above. DNS still prevents the fresh catalogue live-post acceptance test.

Owner subsequently changed audio preference: Shorts must reuse the finished TikTok ad with its TikTok-added sound. This supersedes the earlier original-ad/YouTube-music preference. ShortsMediaPolicy and regression tests begin enforcing source verification, same-shop/exact-caption binding, audio presence, cross-platform soundtrack permission, channel matching and file digest. These checks are not yet wired to a Shorts upload module and do not constitute a shipped publisher.

Finished TikTok export inspection confirmed 720x1280 H.264 video with an MP3 audio track. YouTube imports it as 10.2 seconds versus 12 seconds of source video; preserve-duration normalization remains required. Five Shorts admission/media-inspection tests passed. The nonpublishing test editor was discarded, and Amara's master switch was restored On. The original test preview file was removed; the owner's downloaded finished TikTok ad remains available in the phone gallery. No Shorts upload was dispatched.
