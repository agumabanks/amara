# Defence implementation review — 9 September 2026

This document preserves earlier checkpoints. The later authorised clean migration is recorded in [OLD-APP-REVIEW-20260909.md](OLD-APP-REVIEW-20260909.md), and the installed motion-ad, shop-location and comment-notification work is recorded in [MOTION-ADS-20260909.md](MOTION-ADS-20260909.md). Earlier disconnected-device, signature and missing-contact findings below are historical.

Scope: inspect unfinished mission tasks against current source and implement independently actionable repairs. No phone was connected (`adb devices -l` returned an empty device list). No customer messages, posts or listing edits were sent during this work.

## Changes

- TT-01: phone-local 1080×1920 Amara artwork. Full catalogue photo is fitted without cropping, with two-word headline, formatted price, branded header and high-contrast WhatsApp CTA. Critical text is inset from top, bottom and right controls. Long text fits or visibly ellipsizes; full title remains in caption. Missing prices request a quote; service base prices say From unless explicitly fixed. Both scheduled publications and owner-command drafts/posts use the renderer. JPEGs are frozen with existing integrity-protected media binding. Fingerprints include contact, brand, layout version and price text; changed content cannot reuse a bound publication unnoticed.
- Public ad contact: item `whatsapp_number` takes precedence over the new Settings → TikTok → Public ad WhatsApp setting. The existing server projection does not currently expose item WhatsApp numbers, so configure the public fallback for current catalogue rows. The private owner phone is never silently published. Until configured, artwork shows Soko and the caption contains the exact item URL. No WhatsApp number was supplied in this conversation.
- GR-02: editable per-identity purpose, participation rules, offer topics and reply topics. Offer topics filter catalogue selection; reply topics filter inbound work and are checked again before generation. Purpose/rules enter that group's reply context. Settings do not grant permissions or merge same-name groups. Blank topics retain existing allowed behaviour. Topic filters are literal whole phrases, not semantic classification. Previously bound promotions are checked against updated topics before dispatch.
- Group pricing: missing prices no longer advertise UGX 0; service base prices preserve their starting-price qualification.
- SO-01: market reviews now identify missing service inclusions, options, price basis, turnaround, customer inputs, examples and booking route even when the description is long. Missing structured evidence is an owner review request; the checker does not assert that facts cannot exist in free text or update public listings.
- OBS-01: evaluation journal supports up to seven days, retains the five-hour default, and labels this build `defence-ad-v7`. Receiver accepts `duration_ms`. Collector exposes build-segment counts, largest phone-event gaps and largest collection gaps. This adds observation capability; it does not run or accept a week-long pilot.

## Review of remaining mission work

| Tasks | Current finding / outstanding work |
|---|---|
| BUS-01 | BusinessOperatingBrief and CommercialPolicy exist. D1–D6 still require actual service, commercial promises, targets and acceptance decisions. No invented defaults were applied. |
| WA-01, WA-03, GR-01 | Identity protections and visible review/holds exist. Historical recipient/delivery reconciliation and destination recovery require the device and exact retained evidence. No blind resend. |
| WA-02, FU-01 | Conversation stages, knowledge, reply drafts and follow-up identity protections exist. Useful handoffs and complete follow-up acceptance require approved service facts and live cases. |
| GR-02 | Profile implementation added; actual group rules and useful participation still need owner configuration and live review. |
| GR-03 | Explicit directory/origin duplicate linking is still unimplemented. Same-name ambiguity remains held; no automatic identity or permission merge was introduced. |
| TT-01 | Artwork implemented; audience-tested editorial series and qualified-enquiry evidence remain open. |
| TT-02 | Current TikTokSocialCycle already performs bounded read-only uncertain-comment reconciliation. TikTokSocialStore retains claims and does not re-arm them. The five historical outcomes still require live evidence. |
| SO-01 | Listing checklist added; five real offering audits and three authorised before/after improvements remain unproven. |
| SO-02 | RevenueIngestion, RevenueMetricEngine and RevenueDashboard already exist. Traceable paid orders, costs/refunds and attribution require actual evidence and owner reconciliation. |
| LE-01 | MarketGrowthReview already produces grounded proposals. Three approved change/outcome chains, including a positive measured result, remain unproven. |
| RP-01 | RevenueDashboard already distinguishes unknown costs and business metrics. Seven useful daily reports and owner usefulness review remain unproven. |
| OBS-01, RUN-01, DEF-01 | Week-long collection capability added. Device, frozen scope, sufficient traffic, clean coverage and owner defence review remain outstanding. |

The mission remains NOT DEFENSIBLE. Engineering implementation is separate from deployment, observation and acceptance.

## Verification

- 21 targeted Android tests passed: renderer (native Canvas), catalogue/fingerprint/contact rules, immutable media, truthful group prices, identity-scoped group settings, service checklist and bounded journal.
- 3 Python collector tests passed, including unknown delivery and build/event-gap reporting.
- Flutter analysis of both modified settings screens: no issues.
- Mission validator: 18 tasks / 9 gates valid; NOT DEFENSIBLE retained. HTML, PNG and PDF dashboards regenerated.
- Inspected the native-rendered fixture at `app/build/reports/ad-preview/amara-ad.jpg`. The red rectangle is a synthetic image-fit test fixture; its number is test data, not configured business contact or a public-ready advertisement.
- APK assembly passed; final build identity follows below. No connected phone: installation, permissions certification and actual TikTok composition remain unverified.

Final APK: `app/build/outputs/apk/debug/app-debug.apk`; package `co.sanaa.agent`; version `0.10.0` / code `13`; journal segment `defence-ad-v7`; 96707588 bytes; SHA-256 `0267e6461ca85e94a7d8342416b00b1fe84b1bbe89f853e7ff3c6585b8fc6cc0`. Final assembly passed after the settings validation change. Not installed.

## Connected-phone deployment attempt — 9 September 2026

Phone: OPPO CPH1933, Android 11, wireless serial `192.168.1.65:43037`, reached through the owner-machine SSH-forwarded ADB server.

`adb install -r` rejected the new APK with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. The installed certificate SHA-256 is `5d09d2b12e8f1108bf392fa806efd0bad1071abfbcf4025081969a1662bd18c4`; the new APK certificate is `83a1760330649b8de634583dde0a3ec853751fcdddea41fbf2cf026315960729`. The installed app uses an Android Debug certificate. Local debug keys and the project's release key were checked; none matches. The original build machine's matching debug keystore is required for a data-preserving update. No uninstall, data clearing or permission bypass was performed.

The existing installation passed the repository certificate: version 0.10.0/code 13; process running; Accessibility enabled/bound and not crashed; overlay allowed; notification listener enabled; battery exemption enabled; AgentService present and foreground; 58 jobscheduler text matches (not a count of unique jobs); zero recent FATAL/ANR matches in the bounded log sample. Installed APK SHA-256: `9f25fbaa2b7b51d3f354d23aeb33f4809d06c41aea002284e447fdd2cba22962`.

These results certify the existing installation's sampled readiness, not installation of defence-ad-v7, full customer handling, or week-long reliability. New artwork and other changes remain built/tested, not deployed. The public ad WhatsApp number is also still required.
