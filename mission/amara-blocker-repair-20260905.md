# OPPO blocker repair — 2026-09-05

## Evidence and causes

Device: OPPO CPH1933, serial 7aef1a4c. Accessibility initially bound, no crashed service. Private raw diagnostic copies are in `/tmp/amara-audit`; chat content is not committed.

The device learning log contained 50 WhatsApp search-surface failures, 10 exact-result failures, and earlier generic navigation failures. Group notification titles included `(N messages)`, so exact permission lookup used the wrong group identity. The phone's main Chats screen had no accessible Search control, while New chat exposed Search and returned the exact Naalya E-Trade group.

TikTok's live photo composer exposes separate title and description EditTexts. The implementation required exactly one visible EditText and returned before Post. Share import also took longer than fixed waits. Failed transactions are terminal in the side-effect ledger, but the work queue retried them, retaining the same product and returning the same failure without another action. Previous uncertain publications remain uncertain and are not retried.

Jiji category rows can be clipped at the bottom of the screen; Printers & Scanners was visible as a 13-pixel strip. Category loading was tested before listings appeared. Scraping also counted repeated cards across scrolls as new listings.

Naalya E-Trade already had SEND permission. Group work existed only as part of a morning module proposed during one hour; the regular work sources had no catch-up group promotion work. TikTok selection read only products, and the service bridge omitted media and slugs.

## Changes

- WhatsApp: fallback to New chat > Search, preserving exact result and conversation-composer checks. Strip notification message-count decorations; honor MessagingStyle's explicit group flag. Stable notification timestamps distinguish new events with identical words. Reconnected listeners recover active notifications. Reply keys include the inbound work identity; re-check destination after model generation and stop a burst after unverified delivery.
- TikTok: select the description field independently of the title; send a concrete JPEG MIME type; wait through observed share chooser/editor stages. Expose preparation-stage diagnostics. Treat terminal ledger outcomes as nonretryable work, leaving subsequent cadence to select new content.
- Catalogue: service bridge supplies canonical cover-image URLs and slugs, preserving seller scope. Product/service identities and `/product/` versus `/service/` routes remain distinct. Scheduled TikTok and group promotion select randomly from eligible offerings.
- Groups: daily durable work per SEND-authorized group during active hours, including catch-up after the morning. Bind chosen content before sending; recheck unique identity and SEND permission at dispatch. Use the existing group transaction and target-bound verifier. No permissions are broadened.
- Jiji: wait for initial/category content, scroll clipped category rows into view before clicking, deduplicate cards across pages, and cap returned unique listings. Add a shell-protected read-only queue diagnostic.

The existing 30-second owner takeover window remains in force for inbound replies. Successful unit checks alone do not prove external delivery.

## Validation

Backend service projection executed successfully against the read-only shop database: 50 returned services had media. Backend repository tests: 2 passed, 5 assertions. Android targeted regression results and final device checks are recorded below after deployment.

## Remaining configuration evidence

The historical log also contains rejected/missing Soko Terminal PIN and signed-out account failures. Those are separate from the authenticated catalogue bridge. No credentials were guessed or overwritten. Jumia's missing vision configuration is outside the requested app flows and remains unchanged.

## Live follow-up

- First installed build passed device certificate: Accessibility enabled/bound/not crashed, overlay allowed, notification listener enabled, battery exemption, foreground service, and no sampled fatal/ANR.
- TikTok: read-only profile inspection on @sanaamedia confirmed Booklet Printing (Saddle Stitch), UGX 10000, the exact `/service/booklet-printing-uganda` link, and both hashtags. Publication occurred; the ledger had classified it uncertain because the profile grid selector changed from `eyc` to `exx` and captions are collapsed. Updated grid selection excludes drafts and expands the post description. No blind retry of uncertain publications.
- Jiji: the installed runtime completed a governed scrape and recorded 7 listings.
- WhatsApp: live navigation exposed an existing Meta AI search being mistaken for home. Home recovery now checks the New chat node and prefers contact-picker search. The installed code subsequently opened the exact Naalya E-Trade group and read its information successfully.
- Shell-protected debug inspection pause automatically expires after at most five minutes and lets an in-flight action finish; it is unavailable in release builds.

- Naalya E-Trade: the governed group executor selected Logo Stamps and dispatched the promotion. Read-only hierarchy inspection showed the full correct price/link and a Sent marker in that exact group. A legacy post-dispatch snapshot check wrongly returned false, causing an incorrect FAILED ledger classification. Removed that check: once the send trigger is accepted, delivery is determined exclusively by the target-bound verifier. The existing canary was not resent.
- Updated TikTok verifier executed read-only on the phone and returned `verified=true`, `deliveryState=published`, with no blocker for the exact Booklet Printing caption.

- Final WhatsApp read-only verifier returned `verified=true`, `deliveryState=Sent`, no blocker for the Naalya promotion. Subsequent live task history contained three successful inbound reply runs and a successful TikTok publication run.
- Additional model guard: provider error code is now retained without raw generated text; `json_validate_failed` gets bounded JSON repair while generic HTTP 400 and authentication errors remain terminal. JSON calls allow 1200 completion tokens and GPT-OSS uses low reasoning effort. The historical generic HTTP 400 cannot be conclusively diagnosed because its original error code was discarded. Reference: https://console.groq.com/docs/structured-outputs .

## Regression results

All final targeted gates passed: 60 workflow/parser/content/golden tests, 58 side-effect/golden tests after correcting the dispatch boundary, and 43 model-gateway/notification tests after adding provider JSON-repair handling. These are overlapping targeted runs, not a claim of 161 distinct tests or of the full repository suite. Backend tests: 2 passed. `git diff --check` passed.

Read-only rechecks of two pending direct-message targets both returned exact-chat navigation verified. No target alias was guessed or permission broadened. The 30-second owner takeover window is intentional; model/network latency and real UI ambiguity can still delay or hold a reply.

Final installed APK SHA-256: `0399b5513ab3f728a7acc085d87cda7e989e9fbb26e1b2df4bc8aab0fd0b4353`. Installed with `adb install -r`, preserving app data. Inspection pause was explicitly cleared and the normal governed work loop was woken.

Settled device certificate passed: installed checksum matches local APK; PID 25613; Accessibility enabled/bound/not crashed; overlay allowed; notification listener enabled; battery exemption; foreground AgentService; scheduler present (68 text matches, not distinct job count); sampled recent fatal/ANR count zero.

Scope limits: normal production operation has been resumed, but this is not a 24-hour soak certification or a promise that every future UI/model/network failure is eliminated. Soko Terminal account/PIN setup still needs owner resolution; authenticated catalogue reads work independently. Historical uncertain/incorrectly failed receipts were not silently rewritten or resent.
