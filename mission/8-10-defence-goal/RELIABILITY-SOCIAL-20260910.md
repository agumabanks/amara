# Repeated TikTok profile failures and recovery accountability

Fresh device evidence showed six recent community sessions blocked by `Own TikTok profile identity unavailable`; public-comment totals remained 6 VERIFIED, 9 UNCERTAIN, 23 OBSERVED. These sessions did not reach comment generation. Posting also had independent sound-assignment and publication-verification failures; this repair does not claim those are all resolved.

Live profile inspection showed @sanaamedia with Edit, Followers and Likes, but current IDs `t2z/t0u/t1h/t1i` replaced the parser's `swb/su7/suu/suv`. Expanded comments also changed IDs: `eir/f41/z55/lcl/ej0/d1h/enj`. This was a concrete UI compatibility failure, not a Groq rate-limit finding.

Changes:

- Own-profile navigation uses visible Profile controls, a bounded readiness wait and Edit + Followers + unique handle evidence. Known display-name IDs support the observed versions. Caption/comment/editor/verification controls support both observed ID sets, retaining exact creator/post/author checks.
- Profile/feed failures are recoverable failed tasks with a reason, rather than unexplained skips. No uncertain comment is rearmed.
- Community priority checks consider ready customer/post work, excluding work deferred until later.
- Persistent recovery-health records track consecutive failures across process restarts, clear the streak after successful completion and rate-limit alerts to six hours. Alerts appear locally and can be queued to the configured manager. No manager number is invented.
- Skips and non-comment social observations do not train comment-execution success. Verified comments and failed execution have separate learning significance. Historical mixed metrics remain historical.

WhatsApp's existing per-group read-only recovery and exact-destination holds remain active. No claim of autonomous code repair, universal compatibility or seven-day reliability is made.

Validation and live evidence are appended after completion.

## Naalya E-Trade investigation

The saved group is unique, permitted, promotion enabled, interval **60 minutes**. Its schedule was paused after an uncertain photo verification; last attempt was September 9 at 14:27 EAT. Other groups working did not imply this hold was cleared.

Read-only inspection opened the exact group and found a writable composer. In-chat search located the restaurant-management ad beneath newer messages. After expanding its caption, the complete text exactly matched persisted queue item 254, the containing row was `conversation_row_image`, and that same row contained `Sent`. Private device evidence: `artifacts/defence-migration-20260909/naalya-confirmed-row.xml`.

Photo verification now falls back to search within the already opened exact chat after six unsuccessful viewport checks. Search is a locator only: full caption, photo row and outgoing delivery marker are still required. Read-more expansion also supports Android accessibility clickable spans, restricted to the exact `Read more` label, rather than touching the adjacent service URL.

Final targeted Android test run: **91 tests, 0 failures, 0 errors, 0 skipped**; debug APK assembly passed. `git diff --check` passed. APK SHA-256: `69bc7fa9bbc87f17bd68cc1ffcdcae12a83bcdf148351572dbd808134cb18838`. Live deployment/recovery verification follows below; unit tests do not prove actual scheduled sending or sustained reliability.

Initial deployment passed device readiness: matching installed APK hash, Accessibility enabled/bound/not crashed, overlay, notification listener, battery exemption and foreground agent service; 66 scheduler matches, zero recent fatal/ANR evidence. Certificate: `artifacts/defence-migration-20260909/reliability-certificate.txt`.

Live checks exposed further limitations rather than certifying premature success: Naalya search opened but did not land on the intended row; TikTok read returned no profile while an unfinished Add sound/Next editing surface remained foreground. No public comment was sent during these checks. Naalya locator was refined to the short first-three-word title phrase and bounded taps on visible Earlier/Later arrows, followed by keyboard dismissal. Rebuilt group test suite and APK successfully; superseding deployment evidence is `naalya-navigation-install.txt`.

## Busy-group follow-up

Owner requested immediate durable posting records instead of expensive history scans. The side-effect ledger already persists target/content/idempotency and verified/uncertain state. Added a bounded wait for the exact conversation after final preview Send before navigation, to preserve the newly outgoing row while its marker settles. Historical lookup remains a fallback. Fixed scrolling direction when a matched photo caption is still incomplete, and added exact Read-more character-location expansion where WhatsApp exposes neither a child button nor clickable span.

Live search refinements: semantic menu clicks could report handled while the menu remained open, so in-chat More options/Search use bounded visible-control taps and wait for the specific editable search ID. First-three-word query and result arrows now reach the old ad, but the collapsed caption remained the next failure. No old uncertain send has been retried.

TikTok read continued failing. Manual inspection after a check showed the correct own profile and observed IDs; readiness now waits for a complete unique handle plus display name (rather than stopping on Edit/Followers before the identity is ready). Home tab uses a bounded visible-control tap. Live revalidation remains required.

At **14:31:02 EAT**, Naalya E-Trade read-recovery returned `found=true deliveryConfirmed=true resumed=true`. This required operator navigation to the old matched message and expansion of its Read more caption; then the app itself verified full caption + photo row + Sent and resumed the existing 60-minute schedule. No resend of the old ad occurred. This is assisted recovery, not proof the historical fallback is autonomous for this caption shape.

Latest TikTok check did read `@sanaamedia` but Home incorrectly reported true while Inbox remained active and postReadable=false. Home now taps the visible tab and only succeeds when feed caption plus avatar evidence appears. Targeted TikTok/group tests and APK rebuild passed; deployment `social-home-install.txt` supersedes earlier builds.


## Continuation inspection, September 10, 14:54–15:00 EAT

The previously running final certificate completed successfully: installed SHA-256 `d31963b72a6e7a289ceb737f657b72d13dec0d4a9aebd262a0fd4fe7b71ab582`, Accessibility enabled/bound/not crashed, overlay allowed, notification listener and battery exemption present, foreground AgentService, 60 job matches, zero recent fatal/ANR evidence. This certifies device services only.

Naalya E-Trade is unpaused and promotion-enabled at 60 minutes. Its `nextDue=1789039862229` is September 10 at 14:31:02 EAT. Queued work remains pending. The historical transaction remains UNCERTAIN in the side-effect ledger: the earlier assisted read-recovery resumed scheduling but did not rewrite that transaction. Do not mistake the resume for a new delivery receipt.

The earlier TEST_TIKTOK_SOCIAL request had no persisted owner-check work item in the inspected snapshot. At 14:55:54 EAT a new broadcast was received and the governed owner social item was ACCEPTED. Social totals remain 6 VERIFIED / 9 UNCERTAIN / 23 OBSERVED; no new comment completion is established.

Live evaluation at 14:56:57 EAT showed 47 cycles and zero executed items since installation. World evidence showed battery 19%, owner_active=false, network=true, thermal=NORMAL. PhoneTimeBudgeter grants zero session time at battery <=20%, explaining the pending work despite bound Accessibility. USB charging is connected. The battery policy was not bypassed. A fresh evaluation window records this continuation in `files/evaluation-1789041403485.jsonl`; local inspection evidence is under `artifacts/reliability-social-20260910/`.

Follow-up patch holds the viewport through the first six photo verification checks instead of scrolling away immediately, allowing caption expansion and the outgoing marker to settle before historical search. Exact caption + photo row + outgoing marker remain required; confirmation still persists through the existing SideEffectRunner ledger before returning. Added `session_budget_deferred` evaluation events with work kind, battery and thermal state. Updated the Flutter test to the existing “Resume ads” label; all 20 Flutter tests pass. Android validation and deployment results follow.

The next scheduled Naalya delivery and TikTok discovery/comment/verified-completion scenarios remain pending live execution. The whole mission is not complete.

Full Android debug suite: **840 tests, 3 failures**. Failures are `BoundaryCheckEnforcementTest.realTreeIsClean` (static matches on commerce `.reply` and TikTok `postTikTok`), `ArtifactDeliveryTest.latestRevisionDeliversThroughTheShareableCacheWithMatchingDigest` (FileProvider root), and `AutonomousBypassStructureTest.settingsExposeOwnerKillSwitchesMemoryAndCadence`. These involve files already modified before this continuation; they have not been hidden or relabelled as passing. Full failure summaries: `artifacts/reliability-social-20260910/full-test-failures.json`. The broad Gradle command stopped before APK assembly; a focused debug validation/assembly follows.

Focused Android validation: **116 tests, 0 failures/errors/skips**; debug assembly passed. `git diff --check` passed. Replace-install and readiness certificate passed at approximately 15:04 EAT. Superseding installed APK SHA-256: `490308c317afecc14e41c5f577135f4f693bab722250a7fdd30225cbfb21d0aa`. Evidence: `artifacts/reliability-social-20260910/install-certificate.txt`. Accessibility enabled/bound/not crashed, overlay allowed, notification listener and battery exemption present, AgentService foreground, 59 scheduler matches, zero recent fatal/ANR entries. The three full-suite failures above remain unresolved.

At 15:04:56 EAT the governed social hook was received again after installation (`enabled=true; queue=ACCEPTED`); inspection pause was explicitly cleared. The new build persisted `session_budget_deferred` with battery=19 and thermal=NORMAL. No battery spoofing, force-stop, secure-settings writes, uncertain replay, or direct public comment was performed. Owner was asked to connect a stronger charger; USB charging remained at 19% across the inspection. Live scheduled delivery and community execution are blocked by that unchanged device condition, not certified complete. Once battery permits a session, inspect the existing queue and ledger before requesting additional work.


## Owner correction: 15% battery cutoff, ADB USB

Owner explicitly changed the battery policy from 20% to 15%. The OPPO is connected through **ADB USB**, not wireless; live `adb devices -l` reports USB transport, and `dumpsys battery` reports USB powered=true, Wireless powered=false. Both session admission and the active-session health stop now use the same 15% cutoff: execution is allowed above 15%, and pauses at 15% or lower. Reduced session duration through 50% and thermal/owner-presence protections remain in place. Boundary tests cover 15%, 16%, and the observed 19%. This supersedes the stronger-charger request and 20% blocker described above.

15% policy deployment passed: **68 targeted tests, zero failures/errors/skips**, debug assembly and `git diff --check` passed. APK SHA-256 `f046f12cb6eae4a3d448ac7589f35c8f279c1b3fe0bfd27ae18a7d9479d9e9e8` matched the installed APK. Certificate `artifacts/reliability-social-20260910/battery15-certificate.txt`: Accessibility bound/not crashed, overlay/listener/battery exemption/foreground service pass, 61 scheduler matches, zero recent fatal/ANR entries. At 15:08:39 EAT the live loop recorded battery=19, thermal=NORMAL, owner_active=false and started queued work, confirming the former battery block is removed. Starting work is not delivery or comment-completion evidence.


Superseding owner-requested observation release: see [release and 24-hour observation](RELEASE-24H-20260910.md). Short group copy and durable release logging are installed; APK SHA-256 `50942f962c3d233abd85cd71c014cae8408698c9d5ccc55808b7c882622ebb53`. Observation ends September 11 at 15:51:45 EAT. Initial export proves logger operation, and also records existing posting circuit-breaker deferrals; it does not prove scheduled delivery or TikTok commenting completion.
