# Ordered Task Plan

Task IDs are stable and mirrored in `progress.json` and `taskboard.csv`.

## M0 — Mission control

- `M0-01` Create mission files, progress checker, evidence rules, and field template.
- `M0-02` Baseline existing capabilities and classify them as local or Oppo verified.
- `M0-03` Keep automated build/test commands green.

## M1 — Access and health

- `M1-01` Persist the encrypted Soko staff PIN without displaying it.
- `M1-02` Separate permission enabled, Accessibility bound, installed apps, AI configured, and session/authentication health.
- `M1-03` Detect Terminal account-sign-in, staff-PIN, home, and unexpected states distinctly.
- `M1-04` Restore authenticated Terminal session and pass booking discovery three times after cold starts. **Oppo gate.**

## M2 — Phone-control kernel

- `M2-01` Define typed observations, selectors, actions, verification, risks, and receipts.
- `M2-02` Add semantic element lookup with learned selector ranking and coordinate fallback only when explicitly calibrated.
- `M2-03` Add adaptive human pacing and screen-stability waits.
- `M2-04` Add interruption, keyboard, dialog, wrong-app, timeout, and backtracking recovery.
- `M2-05` Add screenshot capture capability reporting and evidence storage where Android supports it.
- `M2-06` Pass 50-action Oppo reliability suite with zero false completion claims. **Oppo gate.**

## M3 — Soko intelligence

- `M3-01` Read and deduplicate Terminal products with coverage.
- `M3-02` Read Terminal services and grounded text-quality issues.
- `M3-03` Read Terminal bookings, alerts, orders, stock, Studio, customers, and quotations.
- `M3-04` Read Buyer search, categories, products, services, sellers, and buyer-visible listing details.
- `M3-05` Cross-check Terminal listings against Buyer presentation.
- `M3-06` Produce structured shop-health findings with evidence, severity, confidence, and recommended action.
- `M3-07` Pass representative natural-language question suite. **Oppo gate.**

## M4 — Visual intelligence

- `M4-01` Capture listing screenshots or report unsupported capture precisely.
- `M4-02` Add a pluggable visual analyzer for image/title mismatch, missing imagery, duplicates, crops, and text legibility.
- `M4-03` Require visual evidence and confidence; never infer image content from text labels.
- `M4-04` Pass a labelled good/bad listing set. **Oppo gate.**

## M5 — Approval and Soko edits

- `M5-01` Add persistent approval requests with before/after fields, risk level, expiry, and owner decision.
- `M5-02` Add chat approve/reject/follow-up handling.
- `M5-03` Edit the exact listing, save, reopen, compare fields, and issue a receipt.
- `M5-04` Require fresh approval for price, image, publish-state, cancellation, deletion, and financial changes.
- `M5-05` Pass approved text and image edit tests without wrong-listing changes. **Oppo gate.**

## M6 — WhatsApp

- `M6-01` Complete direct/group/contact discovery and participant context.
- `M6-02` Complete inbound monitoring, sender attribution, full visible context, deduplication, and intelligent replies.
- `M6-03` Complete attachments and text/media Status with explicit publish policy.
- `M6-04` Add delivery/read state, missed-call handling, no-response follow-ups, and recovery.
- `M6-05` Pass direct, group, attachment, Status, inbound, and recovery suites. **Oppo gate.**

## M7 — Scheduling and proactive work

- `M7-01` Parse one-time and recurring owner instructions into deterministic schedules.
- `M7-02` Store schedules durably with quiet hours, idempotency key, retry budget, and enabled state.
- `M7-03` Add phone-idle/user-active guard and exclusive device queue.
- `M7-04` Add periodic read-only shop audits and prioritized owner alerts.
- `M7-05` Allow low-risk automatic actions only under explicit standing policy.
- `M7-06` Pass hourly, daily, reboot, and 24-hour soak tests. **Oppo gate.**

## M8 — TikTok

- `M8-01` Map installed TikTok version and creation screens.
- `M8-02` Add media selection, grounded caption, draft, publish approval, and post verification.
- `M8-03` Add visible comment monitoring and suggested replies.
- `M8-04` Pass draft, publish, duplicate-prevention, and changed-screen recovery. **Oppo gate.**

## M9 — General phone manager

- `M9-01` Add reusable per-app skill definitions and capability discovery.
- `M9-02` Add credential-vault references, risk policy, stop control, and audit receipts to every skill.
- `M9-03` Add unsupported/protected-screen classification and human handoff.
- `M9-04` Pass representative third-party app and system-setting tasks. **Oppo gate.**

## M10 — Release certification

- `M10-01` Run cold start, reboot, lock/unlock, slow/offline network, incoming interruption, expired login, and layout-change tests.
- `M10-02` Run repeated Soko → ad → WhatsApp/TikTok end-to-end tests.
- `M10-03` Run 24-hour soak and audit duplicates, crashes, false claims, and missed work.
- `M10-04` Produce signed APK, operational walkthrough, limitations, recovery playbook, and final one-page field report.
