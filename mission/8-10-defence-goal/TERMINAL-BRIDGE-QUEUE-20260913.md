# Terminal bridge and queue clarity — September 13

This continuation preserves the existing uncommitted work in both repositories.

## Findings

The home metric previously counted every PENDING row as queued, without distinguishing future not-before times. Historical NEEDS_REVIEW WhatsApp records are a separate category and must not be conflated with that pending count. Startup compaction also discarded manager-report rows because they deliberately have no customer conversation; it now preserves them.

The fresh pre-update export under `artifacts/amara-bridge-20260913/evaluation` ends at 1789259988550. Latest world sample: network true, owner inactive, normal thermal state, 62% battery, quiet hours true. The last Soko audit/inventory budget deferrals show 7 of 90 daily screen minutes used (83 remaining); quiet hours, not exhausted daily budget, block these tasks. TikTok Story has a separate kind circuit breaker. Two VERIFIED TikTok post effects occur in this journal, but no blanket WhatsApp delivery or sales conclusion follows.

The existing authenticated, registered-business-scoped server bridge is active. Read-only Sanaa Media checks returned 47 listings, 50 services, 26 recent orders and a commerce profile. Counts are bounded query results, not totals or new commercial outcomes.

## Changes

- Queue dashboard separates due work, later scheduled work and review holds; work details expose not-before timing. Queue exports include timestamps and attempts, and the shell-only read-only export records a fresh queue inspection.
- Preserve manager reports during startup compaction and close the pending-count cursor.
- Owner alerts are concise, deduplicated, bounded to four visible issues plus a remainder count, with a next step and no delivery claim.
- Terminal publishes protocol v1 discovery at `content://com.soko24.soko_seller_terminal.amara/context`. Amara consumes it in its dashboard and operating guidance. The provider exposes only public contract metadata and rejects writes, arbitrary resources and SQL selection/sorting.
- Terminal owns its private offline `seller_terminal.db`; its Drift/sync write path is unchanged. Authenticated business reads use the existing backend; media uses the owner-selected document tree. This is a discovery foundation, not direct access to unsynced inventory or bidirectional commercial writes.
- Listing review no longer calls the shop's own catalogue competitor evidence. The schema supports `unknown`; absent external market evidence the decision is set to unknown and does not trigger a pricing-decision WhatsApp alert.

Terminal contract documentation: `/var/www/soko/app/soko_seller_terminal/docs/amara-bridge.md`.

## Validation

49 targeted autonomous queue integration tests passed, including manager report survival, due/scheduled/review separation, exact wake timing and bounded owner warning text. Flutter home and work screen analysis passed. Further build and device results are appended below. Existing unrelated Terminal `catalog_screen.dart` whitespace was observed and left intact; changed-file whitespace checks passed.

Final Amara validation: 19 ModelSchemas tests also passed (68 targeted Android tests total). Final release assembly succeeded, including the guard suppressing pricing-decision alerts when price position is unknown. These are targeted tests, not a full-suite or sustained-delivery certification.

## Installed live state

Amara release 0.10.0 (13), SHA-256 `77dcf0309d21d3f01d8d5c48c13c661a15356f8daf62837fa16b8b8646e4dde5`, was replace-installed with data preserved. Installed hash matches the build. PID 9515; Accessibility enabled/bound/not crashed, overlay allowed, notification listener, battery exemption, foreground AgentService and scheduled-job presence passed. Recent certificate sample has zero fatal/ANR matches. Scheduler text matches are not an exact job count.

At 2026-09-13 00:55:21 UTC, fresh inspection proves ownerOn=true, loop running=true and WAITING for quiet hours. There are exactly **7 PENDING** tasks and **72 separate NEEDS_REVIEW WhatsApp records**; the historical notes describing seven unresolved replies were not the current queue state. No review records were mass-replayed or erased.

| Pending kind | Count |
|---|---:|
| JIJI_SCRAPE | 1 |
| SOKO_AUDIT | 1 |
| SOKO_INVENTORY_CHECK | 1 |
| WA_REPLY_INBOUND | 1 |
| TIKTOK_STORY_PUBLISH | 2 |
| TIKTOK_COMMENT_REPLY | 1 |

Two Story rows have cooldown not-before times beyond their publication deadlines. They cannot be validly published at those later times; ordinary stale-work expiry must discard the opportunities. This observation does not establish on-time delivery. Historical WhatsApp review resolution, reliable comments/Stories and sustained sales attribution remain outstanding.

## Morning continuation and current blocker

The 00:55 UTC sample is historical. A new USB sample at 06:31 UTC (09:31 Uganda) shows 23 pending tasks, 72 review holds, quiet_hours=false, network available, normal temperature and an alive supervisor. In the preceding hour 1,257 device_unavailable events report NONSECURE_KEYGUARD; six internal work outcomes completed and no external effect was recorded. This is the morning execution blocker, rather than quiet hours. Android window policy reports showing=false but inputRestricted=true and secure=false; the old wake path used a deprecated disableKeyguard token. A normal ADB dismissal alone did not clear that inconsistent state.

The follow-up replaces the deprecated wake-time disableKeyguard path with a private transient activity requesting Android's normal nonsecure-keyguard dismissal. Requests are spaced at least 30 seconds apart; secure locks and recent owner activity are excluded, cancellation/error does not grant availability, and the guard observes unlock twice before allowing work. The foreground loop retains the actual device blocker in its waiting summary.

Waiting channel work now refreshes the current WhatsApp/TikTok always-on owner settings without changing cooldown timestamps, attempts, review holds, campaign permissions or explicit owner-command authority. The Work screen adds an expandable review-reasons section. The follow-up queue tests passed 50/50; final lock-guard build/device results follow below.

Terminal packaging needed the canonical Flutter release command to regenerate a stale integration-test plugin registration, followed by `--no-tree-shake-icons` because the existing ad renderer creates dynamic IconData. Initial failed builds are retained as evidence and are not counted as successful validation.

Final lock-repair regression results: 87 targeted Android tests pass (50 autonomous work integration, 13 device-availability guard, 5 device activity, 19 model schema); zero failures/errors. Secure keyguards do not request dismissal, rejected nonsecure dismissal remains blocked, and observed nonsecure unlock permits work. Final release/device validation follows. The Terminal v40→v41 migration test passes and preserves existing products/wholesale ranges. Its test run regenerated a development-only Android plugin registrant during packaging; the failed concurrent packaging attempt is retained, and the release-only registrant was corrected before the sequential release retry. No production plugin registration was removed.

## Final release installation and live checks

September 13, approximately 06:47–06:50 UTC: both final release builds succeeded and were installed with `adb install -r` on OPPO CPH1933, serial `7aef1a4c`, preserving app data. Installed APK hashes match their release artifacts:

- Amara 0.10.0 (13): `04875fb07ef98c2e360dbffc714c3108cbb816860b33b0a83fd0e41466007b55`.
- Seller Terminal: `5ddeba1f067de2f145ca750731c0652dc44d943959e506b8193c352c7e31003f`.

Amara PID 20829 passed Accessibility enabled/bound/not-crashed, overlay, notification-listener authorization, battery exemption and foreground AgentService checks. Scheduled jobs are present (58 text matches, not an exact job count); the recent certificate log sample contains zero fatal/ANR matches. No protected Settings recovery was needed. Evidence is in `artifacts/amara-bridge-20260913/unlock-install.txt` and `unlock-device-certificate.txt`.

The installed Terminal provider returns protocol v1, `seller_terminal.db`, private database access, authenticated seller-scoped backend business access and owner-selected document-tree media exchange. This confirms live discovery, not access to unsynced business rows. See `terminal-live-contract.txt` and `terminal-installed-hash.txt` in the same artifact directory.

The fresh 06:48:21 UTC queue inspection shows ownerOn=true, running=true, state=EXECUTING, 23 PENDING, one IN_FLIGHT and the same 72 NEEDS_REVIEW records. Android window policy now reports showing=false, inputRestricted=false and secure=false. No device_unavailable event appears in this short new-build segment. Installation and foreground launch also occurred, so this sample does not independently prove unattended lock recovery across future lock cycles.

The current segment includes a WhatsApp reply failure because the originating conversation could not be verified; no name-based fallback was used. No external effect is recorded in this new-build sample. Historical review holds, reliable customer delivery and sustained mission acceptance remain open. Fresh observation evidence is under `unlock-installed/evaluation`; no review records were manually replayed or erased.
