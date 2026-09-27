# OPPO posting and community reliability, 2026-09-22

Owner requested investigation of 14 queued tasks, working TikTok posting/commenting, and review of existing features. Posting and contextual public commenting are authorized for this task. Other outbound channels were inspected, not used for test messages.

## Initial live findings

OPPO CPH1933 `192.168.1.64:37207` runs 0.10.36 (49). Accessibility bound, empty crashed set, overlay allowed, master On, active scheduler. The other connected device TPS450M is outside this change's deployment scope.

Queue at first inspection: 11 WhatsApp broadcasts, two TikTok Stories, one TikTok feed post. Feed deferred until its breaker cooldown; Story breaker and future WhatsApp timing account for remaining scheduled work. The UI conflates future and due tasks in its top-level queued total despite native due/scheduled fields already existing.

Fresh journal records feed VERIFIED at 2026-09-21 20:58:49 UTC and 21:35:03 UTC, then an UNCERTAIN feed upload at 21:44:42 UTC with no observable upload progress. Preserve this uncertain transaction; do not repost it or fabricate success. YouTube Short VERIFIED at 22:03:15 UTC. Community cycles run but mostly read irrelevant posts (score zero); occasional profile/caption failures are recorded. Missing manager number blocks manager reports. Historical WhatsApp exact-conversation verification and group workflow failures remain evidence of limitations, not evidence all modules work.

## Captured defects and changes

- Home can show a captionless video. Requiring a description to recognize Home prevents search. Recognize the selected Home tab and observed global search control instead.
- Two visible Search labels exist: contextual search near the video caption and global search `k9z`. First-label selection can open contextual search. Bind to observed global search control.
- Owner profile display name uses `t4g` on captured TikTok UI. Add this to observed aliases so comment verification can match the owner's display name rather than defaulting to its different handle.
- Work UI now exposes due/scheduled counts, next scheduled time, and timing on collapsed queue cards.
- Structural fixtures strip biography/feed content and replace owner identity with examples. Regression checks bind global search and display-name extraction to the captured controls.

Manual read-only navigation using the global search produced relevant printing results and a complete expanded post. An older Sanaa media comment dated September 8 was visible; that is historical evidence only, not a comment sent in this session.

## Validation / deployment

Focused TikTok suite passed (111 tests), Flutter suite passed (37), Flutter analysis has 13 existing informational lints, boundary checker clean across 215 source files. Full Android suite and build 0.10.37 (50) in progress. No release50 deployment or final-release external action claimed yet.

Private raw evidence: `artifacts/oppo-queue-20260922/`.

## Additional live root cause

After resume, feed task `5f3b356adaf95370a43cf04b2da45f49f1515817770c5723f989a8f66799d1f3` reached VERIFIED at epoch 1790029742582, before the new release. A subsequent community model decision scored 0.8 but returned FAILED without entering the transaction ledger. Code inspection establishes that `BusinessChannelPolicy` rejected all public comments: the call lacked `shop_scope`, and the channel was absent from the admission list.

The final release50 candidate now obtains a fresh Terminal scope before discovery, binds scope at dispatch, rechecks it in preflight, and admits the registered comment capability only under the shared exact-scope guard. Community learning supplied to the model is filtered to the captured shop; old reservations remain conservative and cannot replay. Added integration coverage for wrong-shop rejection, successful scoped comment, and duplicate rejection, plus scoped-learning reservation coverage. Dispatch failure reasons are now journaled explicitly. Initial build was stopped before installation to include these necessary repairs; `build50-final.log` supersedes the first build log.

## TPS450M added by owner

Owner explicitly requested latest Amara on `192.168.1.65:5555`. Verified TPS450M, installed 49, first installation 2026-09-13 retained. Only this device currently connected; old OPPO address refuses connection, and mDNS advertises only TPS. Asked owner for current OPPO endpoint while continuing TPS work.

TPS has bound/non-crashed Accessibility and allowed overlay. Latest feed failure was COMPOSER_SOUND_MISMATCH then COMPOSER_STALLED for an original-sound row; no external publish trigger dispatched. Community caption-read failure and posting cooldown are current blockers. Paused through visible Settings for inspection and deployment. Captured TPS Home has selected `omq` Home tab, unlabeled clickable `k_8` global search (manually verified opens Search), and profile display name `t7l` / handle `t9q`. Added observed variants and captionless-feed navigation; final candidate needs a rebuild after these changes. The prior successful 50 APK has not been installed and is superseded by source refinements.

## OPPO WhatsApp repair, 2026-09-23

OPPO CPH1933 reconnected at `192.168.1.66:38989` on release 0.10.37 (50). Baseline certificate passed: Accessibility enabled and bound with an empty crashed set, overlay allowed, foreground agent process running. Seven redacted evaluation journals were pulled before deployment.

The journals distinguish working replies from failures. Recent successful replies reached `reply_whatsapp` CLAIMED → ACTING → VERIFICATION_PENDING → VERIFIED and completed. The recurring failures were exact-route/navigation mismatches, a redundant phone-info verification after a notification route was already proven, and 90-second outer job timeouts. Three failures could open one kind-wide reply breaker and defer unrelated conversations. Scheduled group ads repeatedly stopped before dispatch at `picker_search`; the picker treated a successful Search tap as proof that its editable field was already ready.

Release 0.10.43 (56) polishes the existing paths:

- notification-routed chats are read in place after exact header plus originating-message proof, including a conservative long-message `Read more` prefix check;
- reply model generation has a 25-second budget and a factual immutable fallback draft, while the outer reply budget now leaves room for the existing 90-second delivery verifier;
- inbound reply breakers are scoped to the durable conversation identity, so one bad chat cannot pause other customers;
- a newer message auto-closes only older same-conversation review holds whose reason proves dispatch never began; timeout and uncertain holds remain for review;
- the group media picker waits for the actual editable search field before typing; group jobs have enough outer time for catalogue/media preparation plus exact delivery verification.

Full Android validation: 1,087 tests, zero failures/errors/skips. Release build passed with the established `-PdeviceContinuitySigning` path. APK SHA-256 `d84fc1adc2e0a6ea4fcf53143b1fee6959bf12a0d115e43f9a176578080282af`, signer SHA-256 `83a1760330649b8de634583dde0a3ec853751fcdddea41fbf2cf026315960729`. In-place install succeeded and the pulled installed APK matched byte-for-byte. Post-install certificate: version 0.10.43 (56), PID 27334, Accessibility bound/not crashed, overlay allowed, notification listener bound, battery whitelist and scheduled WorkManager jobs present, foreground AgentService running, zero recent Amara FATAL/ANR entries.

Post-install queue inspection has two scheduled WhatsApp group promotions due in about 14 hours and seven retained reply review holds. Those seven are not runnable jobs and do not block unrelated work. Their timeout/uncertain records were deliberately preserved. No fresh customer or group message was fabricated for testing, so the new picker and fallback have automated coverage plus installed readiness but still require the next authorized scheduled/inbound event for live delivery evidence. Private redacted artifacts: `artifacts/oppo-whatsapp-20260923/`.
