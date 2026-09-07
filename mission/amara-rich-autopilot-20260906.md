# Amara rich promotions, browsing and memory — 2026-09-06

The earlier 7/10 assessment was provisional, not a measured reliability score. This pass follows the owner's specific instructions for a durable Terminal credential, rich group promotions, real Jumia browsing and better conversation continuity.

## Credential

The owner-supplied staff PIN was entered through Settings → Soko Terminal PIN, using the existing Android Keystore-backed credential vault. Live status confirmed configured=true, locked=false, consecutiveValidationFailures=0. The secret is not placed in this report, source code, model prompts or general customer memory. The vault retains it across process restarts and APK updates; no expiry or autonomous rotation was introduced. Future account/PIN changes use owner credential entry.

## Changes

- Group promotions select a product or service with an original catalogue photo. Rich captions preserve its own description, price, exact shopping/service link, and request the details needed to confirm availability or scope.
- Freeze the photo bytes under the durable work key and include the photo digest in the side-effect binding. Target permissions are rechecked before dispatch. Retrying cannot silently substitute another image or caption.
- Attachment delivery now waits for the media caption composer and explicitly dispatches the final preview. Verification requires the full caption, a photo marker and a delivery state within the same message row; an unrelated Sent tick cannot prove a photo arrived.
- Jumia browsing precedes model extraction. Navigate Home → Categories → a rotating business category → All Products. Capture bounded pages before any model request. Select the vertical catalogue rather than nested horizontal carousels, with a package-checked swipe fallback for missing scroll nodes.
- Use visible Accessibility product cards where available; require one unambiguous positive UGX price and a title in the same card. Multiple visible prices remain for visual review. Vision is fallback and still requires consent.
- Preserve per-chat customer-reported preferences with exact current-message evidence and bounded categories. Corrections replace older preferences; another customer's chat cannot retrieve them. Reject credentials, policy instructions and invented inferences.
- Load owner business/WhatsApp context into replies; treat customer messages, prior summaries and screen text as untrusted data. Replies should match language/tone, avoid repeated discovery and unsupported claims, and be honest when asked about automation.

## Live evidence and tests

Deployment build passed 69 targeted tests: 31 credential lifecycle, 28 side-effect transactions, 4 frozen-media, 3 rich-caption, 2 customer-knowledge and 1 strict-card parsing tests; zero failures/errors. Earlier relevant autonomous/privacy gates also passed. Live deployment results follow. During investigation, Jumia exposed useful Accessibility labels, including full product titles and prices inside clickable cards. It also exposed multiple horizontal RecyclerViews inside the vertical `rv_catalog_fragment`, confirming the scroll-selection bug.

Private raw phone evidence remains under `/tmp/amara-audit/`; no PIN or chat dump is committed.

## First live pass

The build installed successfully, Accessibility stayed bound, overlay recovered, and no sampled fatal/ANR was found. Jumia recorded four browsed pages across four sections; screenshots prove it entered a category and scrolled vertically. The first capture raced the network and recorded product placeholders. Added a bounded product-card readiness wait before extraction.

The group-photo attempt failed before the external trigger was dispatched. It is not recorded as a successful post. Added exact preparation-stage diagnostics and caption accessibility-description matching for follow-up.

The owner PIN is saved/unlocked, but Terminal also displayed the independent seller account sign-in screen. The owner was asked to sign into the intended account; staff PIN persistence does not substitute for account authentication. No account phone number was guessed or OTP requested.

## Confirmed browsing follow-up

The live Jumia action at 2026-09-06 02:34 UTC succeeded: four pages across four sections, nine grounded offers extracted through Accessibility. The private market database confirms nine JUMIA and sixteen JIJI records. This path avoids dependence on the model provider, which returned HTTP 429 on an earlier vision fallback. Catalogue extraction now anchors each visible price to a single product title within the same card.

WhatsApp picker inspection identified exact-name recipient rows exposed as non-clickable RadioButtons. Added a bounds-checked tap fallback restricted to the unique exact-name WhatsApp picker label. Photo row verification now recognizes the observed main_layout/media structure. Seven focused caption/parser/target tests passed after the picker changes. Final composer validation and deployment evidence follow below.

## Dispatch-boundary correction

The latest captured WhatsApp screen contradicted the failed action record: this WhatsApp build sends ACTION_SEND photo/caption content directly from the recipient picker's Send button. Waiting for a later caption preview therefore incorrectly reported an already-dispatched promotion as failed. Two inspected messages were the selected Mobile App Development service and Self-Inking Dater Stamp product. No further promotion was requested after discovering this; follow-up is read-only. Earlier `external trigger was never dispatched` action records for the later attempts must not be treated as proof of no delivery.

The corrected path returns dispatched immediately when picker Send is accepted, then runs verification. Only a distinct Next transition proceeds to a caption preview. Long-message verification expands the matching caption's Read more control and accumulates photo/full-caption/delivery observations for that exact message across scrolling. It never uses a different row's delivery tick. Existing work keys remain completed and are not resent for verification.

## Final verified photo and build

At 2026-09-06 02:44:02 UTC, read-only verification of the existing bound Naalya E-Trade Self-Inking Dater Stamp post returned verified=true, confidence=0.9, package=com.whatsapp, deliveryState=sent. Exact chat navigation was also verified. Evidence required its catalogue photo, complete expanded caption and outgoing delivery marker. No additional message was sent during this check. “Sent” does not establish that group members read it or purchased anything.

Final APK SHA-256: `e1531a01dee09b2843003665f471bd1dd1bf1b721d66663829fdb06a059e1d89`. Installation succeeded with process 11558, Accessibility enabled/bound/not crashed, overlay allowed, and zero sampled fatal/ANR. Final build plus 34 targeted transaction/caption/target tests passed with zero failures/errors. Earlier PIN/media/context/parser test results remain recorded above. Soko seller-account sign-in remains required before live listing writes can be verified; this pass does not certify unattended 8/10 reliability or sales growth.

The final repository device certificate passed: installed APK hash matched the built artifact, v0.10.0/code13, live foreground AgentService, Accessibility bound/not crashed, overlay and notification access enabled, battery exemption present, 62 scheduler matches, and zero sampled fatal/ANR. Private certificate: `/tmp/amara-audit/rich-final-certificate.log`. Inspection pause is cleared after certification to resume the normal loop.
