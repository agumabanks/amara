# WhatsApp, recovery, and catalogue market intelligence follow-up

The broad goal is not yet certified complete. This follow-up distinguishes source repairs from live delivery proof.

## Live findings

- Both devices were connected: OPPO CPH1933 at `192.168.1.64:43385`, TPS450M at `192.168.1.66:5555`.
- OPPO retained 67 inbound review jobs. Most were navigation/identity failures before dispatch; one explicitly recorded uncertain delivery. Other holds include disabled replies and ambiguous manager responses.
- The current TPS export contained ten blockers, not eleven queue review records: headline generation, missing manager number, community timeout, missing business timezone, HTTP 413, uncertain Story, inventory navigation, audit cooldown, Story cooldown, and audit navigation. The exported queue contained no NEEDS_REVIEW rows. Counts vary over time and refer to different things.
- Live OPPO group search exposed the full group name in `contact_photo` accessibility description, while `conversations_row_contact_name` was ellipsized. The opened chat header contained the full exact name. Evidence: `oppo-group-search-results.xml` and `oppo-group-header.xml` under `artifacts/employee-audit-20260926/`. No test message was sent.
- The TPS Market UI still described a printer-only default. Both devices reported missing owner timezone, and TPS reported missing manager number. The user has been asked for the intended values; these have not been guessed.

## Repairs

- Group navigation uses chat search, supports the observed new search-bar resource, and can locate a unique row from its exact full group-icon label. An ellipsized label alone never authorizes a send; the opened chat still needs its exact title and composer.
- Phone verification accepts an exact full phone-number chat header, uses consistent national/international normalization, and allows bounded loading/scrolling of recognized contact-information fields.
- A queued draft that cannot open its destination now gets recoverable failure treatment before any send. Health reconciliation can requeue at most three recent explicit drafts per check, only once each and only with no existing send receipt. Uncertain, already-claimed, disabled, and ambiguous inbound work is not bulk replayed.
- Scheduled Jiji research selects from the signed shop's products and services instead of a hardcoded printer category. Explicit user searches remain supported. UI copy now describes catalogue-based research.
- Market reviews contain our prices, recent comparable asking-price counts/ranges/averages, insufficient-evidence labels, and a manager decision prompt. Reports enter the existing manager outbox, scoped to the shop, with daily deduplication. Price research never applies a price edit by itself.
- Verified manager commands receive recent same-shop market context. Explicit approve/reject/set/change/adjust commands no longer get intercepted by unrelated pending customer consultations. Ambiguous approval target hints no longer select the first matching listing. The existing exact approved Soko edit path remains responsible for saving and verifying changes.
- The server's memory endpoint caps snapshots at 2,000,000 bytes. Versioned gzip/base64 backup encoding preserves the full snapshot and retained action claims; restore accepts both old version-one snapshots and the new envelope. Compression and expansion are bounded. Server encryption-at-rest remains unchanged.

## Remaining evidence and configuration

Passing unit tests and an installed APK do not prove that a customer received a reply, that an uncertain Story did not publish, or that a Soko edit saved. Existing uncertain outcomes still require receipt reconciliation. Live manager command execution and manager-approved price saving remain unverified. The intended timezone and TPS manager destination remain required configuration.

## Build validation

Final release 88 / 0.10.75 build succeeded. Final targeted run: 36 tests, zero failures/errors/skips. Side-effect boundary check passed across 236 Kotlin files; `git diff --check` passed. Log: `artifacts/employee-audit-20260926/release88-group-final.log`.

APK SHA-256: `190bb84af88094cd0d17a05b45444e09f2621bf5fd2e81c58c1abbab9c1d6930`. Signing-certificate SHA-256: `83a1760330649b8de634583dde0a3ec853751fcdddea41fbf2cf026315960729`, matching the established device continuity signer.

Both installations and certificates passed: version 0.10.75 / 88, matching APK bytes, Accessibility enabled/bound/not crashed, overlay, notification access, battery exemption, foreground AgentService. OPPO had 76 scheduler matches; TPS had 107. Neither sampled log contained a recent fatal/ANR match. Certificates: `artifacts/employee-audit-20260926/oppo-release88-certificate.log` and `tps-release88-certificate.log`.

## Resumed runtime audit

Fresh release-88 OPPO evidence shows two `notify_owner_whatsapp` receipts reaching VERIFIED and 18 unsent drafts requeued by health recovery. This proves those two outbound updates, not incoming manager command execution. Both loops remained running. TPS recorded a completed MARKET_ANALYSIS task; its manager destination remains missing. Both Doctor diagnostics report `owner_timezone_missing`.

Recovery requeueing could exceed the ordinary WhatsApp admission cap. A source repair now limits recovered work to available room below 20 active WhatsApp tasks, reserving room for new messages. Its targeted reliability tests passed.

The expanded release-89 test run initially caught an unintended relaxation of the daily allowance floor. That floor was restored separately from the reduced-session minimum. The corrected run passed all 69 targeted tests with zero failures/errors; mechanical side-effect and diff checks also passed. Final packaging log: `artifacts/employee-audit-20260926/release89-final.log`.

Release 89 / 0.10.76 built successfully; APK SHA-256 `0e7c6eb072bbe55987be876c61a34657f504abafe91eb1e670270561bde1890e`. OPPO in-place installation and all certificate checks passed, with matching installed bytes, bound/non-crashed Accessibility, foreground service, 80 scheduler matches, and zero sampled fatal/ANR matches. TPS installation has started. This supersedes the earlier build-in-progress status below.

TPS also passed the release-89 certificate: matching APK, Accessibility bound/not crashed, overlay, notification listener, battery exemption, foreground service, 109 scheduler matches and zero recent sampled fatal/ANR matches.

Post-install OPPO journal `oppo-release89-current.jsonl` records 12 VERIFIED `notify_owner_whatsapp` receipts and one VERIFIED `post_tiktok` receipt in build segment `amara-release-observation:1790446973768`. These are autonomous runtime observations, not synthetic test sends. Group search still failed its input readback. The captured WhatsApp `search_input` value starts with U+200B, which strict equality rejected. Search readback now strips formatting characters only, retaining exact query comparison. All 12 targeted search tests passed; release 90 / 0.10.77 packaging is running (`release90-build.log`).

Release 90 packaging subsequently succeeded. APK SHA-256: `6f333009baab81cfd22153a72c27ae232cec5da3f9926dad449c025df616216d`. OPPO installed and passed all certificate checks with matching bytes, version 0.10.77 / 90, PID 22829, Accessibility bound/not crashed, 74 scheduler matches, and zero sampled fatal/ANR matches. TPS deployment is running. No release-90 group delivery claim has been made.

TPS deployment subsequently passed all checks, matching the same APK, PID 26742, 107 scheduler matches, zero sampled fatal/ANR matches. Both release-90 journals show running loops. OPPO recorded another completed TikTok publication and eight inbound observations. Community model unavailability deferred work with no comment dispatched. No group execution occurred in the sampled release-90 window, so the search fix remains unit-tested and installed but not yet live-certified through a group task. Doctor still reports `owner_timezone_missing` on both phones. Evidence: `oppo-release90-current.jsonl`, `tps-release90-current.jsonl`, and both `*-release90-certificate.log` files in the audit artifact directory.

OPPO session-deferment evidence recorded WARM, 46% battery, and 72,960 daily seconds remaining. The combined multipliers yielded 120-second sessions rejected by a fixed 180-second minimum, starving even 45-second replies. A source repair now accepts a session that fits the estimated short task (30-second minimum, 180-second ceiling), preserving thermal, owner-presence, and battery limits. Regression checks cover short versus long work, hot conditions, and owner activity. Release 89 / 0.10.76 test/build validation is running in `artifacts/employee-audit-20260926/release89-validation.log`; it is not yet installed.
