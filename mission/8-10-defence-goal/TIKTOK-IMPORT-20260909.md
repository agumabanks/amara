# TikTok repeated import repair — 9 September 2026

## Observed failure

The durable learning log records repeated `composer_timeout` failures, including 13:54 and 14:26 UTC. Each reports `external trigger was never dispatched`. These attempts never reached the Post control; they are distinct from earlier uncertain publications, which must not be resent.

Live Android task inspection showed a task whose base intent was TikTok's `SystemShareActivity` with `ACTION_SEND` and `video/mp4`, but whose only remaining activity was TikTok's browsing `MainActivity`. Repeating the share with `NEW_TASK` brought this old task forward without importing the video. A different MIME type opened the photo share sheet. Launching the saved video with `NEW_TASK | CLEAR_TOP` opened the Video share sheet and then the editor with Next and Add sound.

## Change

Production TikTok sharing now includes `FLAG_ACTIVITY_CLEAR_TOP` so Android launches the requested share activity again. It also carries the exact bound media URI in ClipData with read permission. The existing eight-second wait for TikTok's own automatic audio remains; no music picker is opened. A composer timeout now captures local screenshot evidence and records navigation-stage counts for diagnosis.

A debug-only, owner-invoked recovery hook permits one governed retry per installed build, only after a recorded pre-dispatch composer timeout from the previous build and reuses already queued TikTok work when present. Otherwise it copies the failed listing's bound facts into a fresh work item. The regular executor revalidates catalogue facts; the hook expires only the old TikTok cooldown. Failure counters, historical outcomes, global safety checks and uncertain actions remain intact.

## Validation

Ten targeted Android tests passed: composer field checks, bound-media integrity/reuse and the side-effect boundary. APK assembly and `git diff --check` passed. The first production-fix installation passed Accessibility enabled/bound/not-crashed, overlay and recent fatal/ANR checks.

## Live result

The queued job `tiktok-due-1788964269050` imported and prepared a video for service:24, Bamboo Digital Clocks, with media SHA-256 `1072e80fb6f70fbd6780c6df2d93a711e61ee8bea6fd84839f439e3574d2d34d`. The composer evidence was recorded at 14:45:03 UTC. TikTok accepted Post, but the first immediate check could not open the newest profile post; the original transaction remains UNCERTAIN and is not resent.

At 14:46:59 UTC a separate read-only check returned `verified=true`, confidence 0.8, deliveryState=published. The newest @sanaamedia post shows Bamboo Digital Clocks, From UGX 100,000, WhatsApp +256706272481, the exact Soko service link, and #soko24 #BambooDigital #SanaaMedia #NasserRoad #Kampala. This confirms the improved video was published after the import fix, while preserving the earlier uncertain outcome honestly.

The subsequent verification change allows up to 90 seconds for asynchronous upload and profile availability, retries read navigation only, and yields if the foreground app changes. TikTok's total side-effect deadline is 180 seconds to cover import plus verification. An older profile item can be closed using its observed Back control before refreshing. Missing evidence still ends unproven without another Post tap.

Private evidence: `tiktok-import-after.tar`, `tiktok-import-final-log.txt`, `tiktok-import-read-verification.txt`, `tiktok-bamboo-caption-verified.xml`, and `tiktok-bamboo-caption-verified.png` under artifacts/defence-migration-20260909/.

## Final verification checks

The upload-wait update passed 46 tests: 3 delayed-publication/timeout/owner-switch cases, 3 publication-surface tests, 30 side-effect transaction tests and 10 capability contract tests. The earlier import change also passed 10 composer/media/boundary tests. APK assembly and repository whitespace checks passed. Live delayed-upload handling on a future scheduled post remains a separate observation from these tests and the successful read-only confirmation above.

Final APK SHA-256: `625b43bcde949dc7d91c9a4beab34ade75db7eb0387dd422238527dcdb51ae11`. Update installation succeeded; process 29436, Accessibility enabled/bound/not-crashed, overlay allowed and no recent fatal/ANR matches. The historical UNCERTAIN publication remains preserved alongside its later read-only confirmation.

Final device certificate also passed notification listener, battery exemption, running foreground service and scheduled-job checks. At 15:04:45 UTC the final installed build independently re-read the existing post and returned verified=true, confidence 0.8, deliveryState=published. Evidence: `tiktok-final-device-certificate.txt` and `tiktok-final-read-check.txt`. Inspection pause was cleared and normal scheduled work resumed.

## Owner-requested automatic-audio wait

Video imports now wait 15 seconds after the editor first exposes Next before advancing (previously 8 seconds). Photo imports retain 8 seconds. Composer preparation uses a 60-second elapsed-time deadline so the audio wait does not consume most of the earlier polling budget. TikTok chooses its own audio; Amara does not open a sound picker or assert that a soundtrack was assigned merely because time elapsed. APK assembly and whitespace checks passed.
