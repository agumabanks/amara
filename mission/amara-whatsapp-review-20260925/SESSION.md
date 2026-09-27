# WhatsApp command-led review, 25 September 2026

Owner authorized live group advertising tests, including Naalya E-Trade, and WhatsApp reliability improvements. Existing workspace changes were retained. Raw phone artifacts remain under ignored `artifacts/whatsapp-review-20260925/`.

## Live evidence

- OPPO CPH1933 at `192.168.1.64:40763`, initially 0.10.69 (82); TPS450M at `192.168.1.66:5555` inspected read-only. Pulled both current evaluation journals. TPS's sampled journal contains no WhatsApp outcome events; this is not proof its WhatsApp works.
- OPPO's sampled journal has 73 group attempts held for prior possible dispatch, 18 editable-search-field failures, 11 exact-result failures, and two photo picker-search failures. Counts are attempts, not distinct groups. See LOG_SUMMARY.txt. Existing uncertain receipts were preserved.
- Historical named alerts include Business Centre Uganda (picker_next), PSALMS CHOIR FANS CENTER and UGANDA AUTHORS AND ASPIRING GROUP (picker_search), and Naalya E-Trade/Naalya Community Neighborhood (Terminal session access). Alerts alone do not establish each group's current state.
- Submitted two real owner-chat commands: a current-catalogue Naalya E-Trade ad request, then “Share one Soko Studio ad to Naalya E-Trade.” Both returned “I couldn’t analyze the task safely just now,” with zero verified steps. No successful ad delivery claimed; neither command reached a verified sending step.
- During each new command the progress panel displayed a stale Derick clarification. Confirmed in UI XML and screenshot.
- Current WhatsApp Autopilot switch is off; group setting remains on. Latest reply outcomes say replies disabled. Left settings intact. The manager number is configured (ending 9481); missing-manager alerts are historical.

## Changes

- A present picker search editor with null text is now an empty editor, rather than an absent editor. This allows the bounded search helper to type instead of repeatedly opening Search. Exact result and readback checks remain.
- Verified manager commands receive a durable four-turn conversation context, isolated by manager and freshly verified shop, expiring after 24 hours. Stored command/result credentials are redacted. History is supplied as historical data, not fresh authorization. Existing exact-target checks and no-replay receipts remain; ambiguous pronoun destinations still require clarification.
- Chat progress prefers the active owner command over stale global status, and each command initializes its own progress text.
- Release candidate 0.10.70 (83).

## Remaining gates

Live ad delivery remains blocked at model planning and WhatsApp Autopilot configuration. The exact model transport/schema failure was not established from the sampled logs. A manager-origin end-to-end “ask a contact, wait for their answer, report back” conversation is not certified. Requested a test contact and exact question from the owner; no invented customer message was sent. The new history supports conversational context but is not a complete durable delegated-conversation workflow.

Initial focused regression run: 16 tests, zero failures/errors/skips. Final expanded tests and device deployment are recorded below when complete.

## TikTok investigation requested during this review

- OPPO: last verified feed receipt in sampled journal: 2026-09-25T16:57:41.318000+03:00.
- TPS: last verified feed receipt in sampled journal: 2026-09-25T20:26:39.777000+03:00.
- Latest OPPO feed preparation failure at 22:33 EAT: no acceptable two/three-word headline; breaker deferral at 22:49. TPS also has repeated identical headline failures at 21:08, 21:39, 22:09, 22:40 EAT.
- Code root cause: AdHeadline.fallback throws for unfamiliar titles longer than three words when model generation/validation fails. The outer executor classifies this as UNKNOWN/recoverable, feeding the posting breaker. GrowthStore.bind happens after headline generation, so those rejected preparation attempts do not enter product rotation history.
- Earlier OPPO failures include uncertain foreground-change verification and composer_timeout. Uncertain receipts must remain protected against replay. Posting-time budget still has room.
- Headline/rotation repair is not included in release 83; no claim TikTok posting is restored.

## Final WhatsApp update validation

Expanded Android regression suite: 50 tests, zero failures/errors/skips. Release build succeeded; side-effect boundary clean across 231 Kotlin files. Installed OPPO release 0.10.70 (83) in place using the device-recovery skill. APK SHA-256 `61274429c1e3de3dd27a22a5ca757bfb959fab75fbef546ee11178daeb438312`. Deployment helper reports PID 14556, Accessibility enabled/bound/not crashed, overlay PASS, zero recent fatal/ANR. TPS was not updated in this session. Inspection pause released.

Repository device certificate also passed: installed APK hash matches the built APK; notification listener enabled, battery exemption present, AgentService foreground, 72 scheduler matches, zero recent fatal/ANR.

## Release 84 repair, continued at owner request

Owner requested all remaining fixes and TikTok restored, then asked to continue. Release 0.10.71 (84) built successfully. Expanded targeted suite: 109 tests, zero failures/errors/skips. Side-effect boundary clean across 233 Kotlin files.

- Known catalogue product phrases use deterministic grounded headlines before calling the model.
- Bounded automatic ad preparation tries up to three offerings if headline generation is unavailable. Explicitly pinned/bound offerings never substitute another product. Rejected copy enters audience-scoped rotation history as HEADLINE_REJECTED; no publication success is recorded. Exhausted copy preparation escalates rather than incrementing the posting failure breaker. Applies to TikTok feed and group ads.
- Exact saved group commands such as `Post one ad to Naalya E-Trade` queue the existing governed group workflow without model planning. Owner command may advance schedule timing but cannot bypass permission or uncertain-dispatch recovery.
- Read-only observation export includes model failure stage, disposition, terminal state and attempts without message content or credentials.
- OPPO WhatsApp Autopilot was enabled through Settings to restore the requested workflows; contact/group permissions unchanged.
- APK SHA-256: `14868942ee22961eaf956bbe7ba780556226b3f08a5c866d7495471efc500c06`. In-place deployment to both phones in progress.

## Live release 84 outcomes and importer repair (release 85)

Both release 84 installations passed bound/not-crashed Accessibility and overlay checks. TPS verified a TikTok publication at 2026-09-25 20:31:13 UTC (23:31 Uganda), effect key `0337e63ff901a499baf9d0553c4f0a8749e01922ae907713413751337f8a91db`, followed by DONE. A later TPS preparation hit Soko HTTP 429; continuous posting is not yet certified. OPPO failed before dispatch at composer_timeout; its screen remained the TikTok feed.

Release 85 (0.10.72) opens ACTION_SEND in a fresh TikTok importer task with MULTIPLE_TASK, preserving URI grants and ClipData, without clearing tasks/drafts. Eleven focused tests passed; release build successful; side-effect boundary clean across 234 Kotlin files. APK SHA-256 `45ebb159cdfa13fce168ed0fd10e9ce3261a8a745a13f29e7d9bda07bf8c84d2`. OPPO in-place installation started; live verification pending. TPS remains release 84.

The exact owner command `Post one ad to Naalya E-Trade` was accepted by the Amara chat UI at 23:58 Uganda. UI explicitly said queued, not delivered. No verified send established yet. Other group records still show uncertain historical effects and exact-result search failures; these holds were not cleared.

The ADB bridge temporarily stopped responding during this continuation, then recovered. No force-stop, direct accessibility setting write, or uncertain-effect replay was performed.

Separate source change under test: typed Soko HTTP 429 carries a bounded Retry-After interval (seconds or HTTP date, minimum 60 seconds). Work execution defers read throttling as SKIPPED with a timed requeue, so it does not feed the publication circuit breaker. This change is NOT in release 85.

Release 85 OPPO installation completed: PID 11986, Accessibility enabled/bound/not crashed, overlay PASS, recent fatal/ANR 0. Owner TikTok command at 00:29 Uganda was accepted (key `f2b9c84aafb762666fc3d66b1201552b3c0e673bff271a2ab88f70c2f4b2dbd0`) but never started: WorkQueue's SINGLE_PENDING compaction deleted it when the periodic source offered a new job. This is a confirmed additional root cause, not a successful live importer test.

The replacement routine post checked three offerings and escalated before dispatch because no acceptable headline was available. A read-only scoped catalogue lookup identified one rejected title as `Self-Inking Stamp - CONFIDENTIAL - Red Ink`. Added grounded `self-inking stamp` recognition; no invented claims or broad truncation fallback.

Release 86 source now also preserves explicit owner requests, prepared listing payloads, and retries during periodic queue compaction. Untouched routine buckets still compact. Owner comment commands are separated from routine comment compaction. Added durable queue/restart regression and stamp headline validation regression. The first rate-limit-only release build was superseded before deployment; final combined build/tests run in `build86-final.log`. Earlier rate-limit and shop-switch suite: 5 passed, no failures/errors/skips.

Additional confirmed load source: Flutter AppShell keeps MarketScreen mounted in IndexedStack, and its 30-second timer fetched the full paginated catalogue while hidden and while the app was backgrounded. MarketScreen now receives active-tab state, skips hidden/background refreshes, and refreshes when visible/resumed. Dart analysis passed. Both market widget tests passed, including no hidden/background requests. Final release 86 build supersedes earlier intermediate builds and is logged in `build86-combined.log`.
