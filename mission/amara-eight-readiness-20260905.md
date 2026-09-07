# Work toward 8/10 readiness — 2026-09-05

An 8/10 rating is an operational assessment, not a feature count. This pass targets observed reliability gaps. It does not redefine a sent post as revenue or label unit tests as live certification.

## Baseline evidence

On-device growth records since the preceding deployment contained 3 completed inbound outcomes with mean notification-to-completion latency 657 seconds. The inbound queue had 72 completed records and no pending inbound records at snapshot time. Queue completion includes dropped/escalated work; it is not a 72-reply success count.

## Repairs

- Due inbound replies retry pre-dispatch failures after 5 and 20 seconds, then request owner attention. The generic 15-minute retry schedule no longer applies to them.
- Successful work no longer trips a circuit solely because historical failure rates were high. The inbound circuit's effective pause is capped at two minutes, retaining the configurable smaller cap and all device/recipient controls.
- WhatsApp bundled notifications ingest all distinct messages in timestamp order. Notification summaries are excluded. Android's messaging-person field represents the phone owner and is no longer used as an inbound sender fallback.
- Immutable SQLite drafts bind generated replies to the inbound event. Restart/retry cannot regenerate different pending text. Responses are sent as one complete message, avoiding premature success after only part of a burst. Unverified dispatch is escalated, not automatically resent. Conversation summaries advance only after verified delivery.
- Coroutine cancellation propagates out of the conversation engine.
- SQL candidates order inbound events oldest first, regardless of their monetary scoring metadata.
- Settings now expose the vision model ID. The guarded Jumia repair hook requires existing screenshot consent and fills only an absent model setting; it queues governed read-only research.

Groq's current [vision documentation](https://console.groq.com/docs/vision) lists `qwen/qwen3.6-27b`; account availability still requires a real request. The repair does not enable screenshot consent or overwrite an existing chosen model.

## Evidence needed before claiming 8/10

- Representative live inbound replies complete promptly while the phone/network/model are available; report measured latency and failed/escalated outcomes.
- Research succeeds on both Jiji and Jumia and persists usable evidence.
- Soko Terminal authenticates and a reviewed factual change is saved and read back.
- TikTok and group publications remain verified, varied and deduplicated.
- Service binding survives deployment/restart and sustained operation; no pending customer messages disappear silently.

## Final validation

Android targeted run: 72 tests passed, zero failures/errors (33 autonomous integration, 28 side-effect transactions, 4 notification parser, 4 privacy, 3 bundled-notification/durable-draft checks). Settings credential test passed; settings-screen Flutter analysis clean. Final APK rebuilt after formatting-only cleanup.

The three baseline reply latencies were 975, 40 and 955 seconds. The two slow results are consistent with the former 900-second retry delay; this is supporting evidence, not proof of every delay's cause.

APK SHA-256: `8446e091d5f0575a858a9492bfcff62acf5212a60bd78cefcd9543ae7be833f8`.

Deployment/live diagnostics pending below.

## Live follow-up and remaining evidence

The deployed reliability build passed the full OPPO service certificate. Live post-update completed reply outcomes included 60, 86, 85 and 53 seconds. Failed chat navigation attempts also occurred; these remain failures, not hidden successes. Read-only navigation on an inspected target returned verified=true.

Terminal credential health reports configured=true, locked=true, consecutiveValidationFailures=3. The owner was asked to enter a corrected PIN through the secure settings screen; no PIN was released, guessed or reset by this audit.

Jumia vision consent was already true. The missing model was configured, clearing the configuration blocker. Actual requests then exposed 429 rate limiting and malformed model JSON. The follow-up vision request now disables Qwen reasoning and reserves 1600 completion tokens for extracted offers, based on Groq's [reasoning documentation](https://console.groq.com/docs/reasoning). A mocked request regression verifies these exact wire parameters; it does not prove live provider success.

Search-result matching previously applied literal substring filtering before full-number normalization. It now walks visible labels, matches full names/numbers, deduplicates identical clickable rows and rejects distinct ambiguous rows. Target header/composer verification remains mandatory.

Latest follow-up: completed reply outcomes included 39, 46, 48 and 70 seconds. Failed outcomes included 57, 92 and 5352 seconds. The very old failed item means aggregate averages must not be presented as a universal service guarantee.

Final model/matching gate: 40 model gateway + 3 full-identity matcher tests passed. A JUnit initialization error in the new test was corrected before this successful gate (expression-body test had inferred Boolean return). Final source whitespace check passed.

## Settled deployment — 2026-09-06

Final installed APK SHA-256: `d3e695b1bea399eda48731b0c03b7985784a66772b6d5659bfa8e6de851339f6`. PID 16755. Accessibility enabled/bound/not crashed; overlay allowed; notification listener enabled; battery exemption; foreground AgentService; scheduler present; sampled fatal/ANR zero. Installed checksum matches the local APK. Updated with `adb install -r`; no force-stop/uninstall or credential guessing.

Final bounded Jumia request reached the provider but returned HTTP 429. It remains subject to governed retry; no claim of successful Jumia extraction. Database readback still contains 16 Jiji observations and no Jumia observations. Missing vision configuration is repaired; provider capacity is now the explicit external blocker. Avoid repeated manual provider requests while rate-limited.

Soko credential remains locked and owner correction is still pending. No Terminal listing was written or falsely reported as published. Existing improvement drafts remain available.

Readiness: improved supervised operation, approximately 7/10, not certified 8/10. Remaining gaps are Terminal write/readback, successful Jumia research, failed/aged inbound exceptions, and sustained unattended verification. Reply latency gains are measured for completed events, not a guarantee that all messages were answered.

Inspection pause cleared and normal governed loop woken. Private chat/database evidence remains outside the repository under `/tmp/amara-audit/`.
