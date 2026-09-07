# Business autopilot follow-up — 2026-09-05

Readiness remains approximately 6/10 for supervised operation. This is an engineering assessment, not a measured reliability score. Earlier live evidence confirmed TikTok publication, a Naalya E-Trade service promotion, inbound replies and Jiji browsing; it did not establish continuous unattended reliability or attributable revenue.

## Changes

- Persist group promotion history by audience, listing identity and offering type. Randomly choose eligible unseen offerings, prefer alternating products/services and recycle older offerings before immediately repeating. Bind each task's content before dispatch and retain side-effect deduplication.
- Prioritize due inbound WhatsApp work before background promotion/research, including the SQL candidate limit. Keep the 30-second owner takeover window, recipient permissions, and delivery verification. The daily promotional message cap no longer stops inbound customer replies; device budgets and failure breakers still apply.
- Record reply outcome/latency and promotion outcome separately from revenue. A send or post is not a sale.
- Convert successful research into follow-up catalogue reviews. Daily review catches up throughout business hours. Persist specific listing issues, factual description cleanup drafts and category sourcing briefs; show these on Market Intelligence.
- Compare recent offers conservatively by title/model/condition, requiring at least three comparable priced observations before price positioning. Remove unsupported claims that listing counts prove demand, competition or profit. Asking prices are not realized sales prices.
- Jumia uses at most three visible pages, deduplicates offers and stops when further scrolling yields no new products. Its configured vision model and existing screenshot consent are prerequisites.
- A shell-protected debug hook runs the catalogue review without sending messages, posting, or editing Terminal listings. It creates local review drafts only.

## Limits

Terminal account/PIN failures previously observed still need resolution before writes can be certified. Catalogue bridge reads work separately. No new inventory, supplier capacity, original product media, cost or margin is invented from competitor listings. Sourcing briefs identify the business facts needed to create legitimate new listings.

The existing exact-edit workflow requires matching reviewed fields before applying a listing change. The drafts are concrete preparation, not a claim that Terminal changes have been published. Jumia's vision configuration remains a separate setup dependency. End-to-end Jumia capture and sustained unattended operation are not yet proven.

## Validation

Android debug APK built successfully. Targeted unit tests: 84 passed, zero failures/errors (39 model gateway, 31 autonomous integration, 5 growth policy, 5 product content, 4 notification parser). Flutter analysis of the changed market screen: no issues. `git diff --check` passed.

New tests cover due replies surviving 60 higher-value background candidates, customer replies remaining available after the promotional daily cap, product/service alternation, unseen-before-recycle selection, exclusion of unavailable/media-less offerings and conservative model/condition matching.

APK SHA-256: `5c41f3e7b2c86a98dd87a0ba8f79034988df3d31b5961591496675362e4a4997`.

Installed with `adb install -r`, preserving app data. Settled certificate: OPPO CPH1933 / Android 11, PID 32550, Accessibility enabled/bound/not crashed, overlay allowed, notification listener enabled, battery exemption, foreground AgentService, scheduled jobs present, zero sampled fatal/ANR. Installed SHA matches the APK above.

Live on-device review succeeded against 55 products and services. SQLite readback confirmed 55 saved listing improvement plans, 2 exact text drafts and 1 sourcing brief. These are persisted local review artifacts, not published Terminal edits or sales. Inspection pause cleared and the normal governed loop woken.

Private database/log evidence remains under `/tmp/amara-audit/`; no private chat dumps were added to the repository.
