# Progress board

Planning package: ready. Implementation: **in progress**. P0 has a read-only audit checkpoint, but its live evidence gate is blocked. No phase is live verified.

| Phase | Milestone | Status |
| --- | --- | --- |
| P0 | M0: proven baseline and exact blockers | blocked: 24-hour coverage, exact Notices, historical request correlation, content/return-switch canary and off-host restore evidence |
| P1 | M1: isolated failures and visible health | planned |
| P2 | M2: independent YouTube variants/cadence | planned |
| P3 | M3: authenticated device/shop reporting path | planned |
| P4 | M4: durable event sync and availability | planned |
| P5 | M5: fleet graph and drill-down | planned |
| P6 | M6: shared account and QR grants | planned |
| P7 | M7: authorized remote command receipts | planned |
| P8 | M8: evaluated learning and rollout | planned |

Machine source of truth is progress.json; keep this table aligned. For each status advance attach evidence/<phase>-report.md and referenced artifacts. Fields `checks` indicate completion of implementation/tests/deployment/live gates. Blocked status requires an explicit blocker. Checker validates structure, dependency order and evidence presence; humans must assess evidence quality.

Current checkpoint: [P0 report](evidence/P0-report.md), [build-segment summary](evidence/P0-current-build-summary.json), [database inventory](evidence/P0-database-inventory.json), [isolated restore rehearsal](evidence/P0-restore-rehearsal.json), [storage follow-up](evidence/P0-storage-follow-up.md), and [config request correlation](evidence/P0-config-correlation-checkpoint.md). No public posts, WhatsApp messages, APK installs or production schema changes were performed for the P0 audit. Continue independent source fixes while collecting P0's remaining live evidence.

The [02:47 device recheck](evidence/P0-device-connectivity-checkpoint.md) found TPS attached and OPPO still refusing its old ADB endpoint, with no mDNS service. This prevents OPPO installation and Notices inspection until phone-side access returns.

## P1 tracks and issue closure

W1 inbound/long replies, W2 group ads, C1 community engagement, Q1 recovery/fairness and H1 health alerts are all planned. Track their evidence in progress.json. The 45 issue-register rows, including a newly observed TPS shop-binding change and the central backup-coverage incident, have a first partial-window classification and explicit next actions; none is closed. Historical failures are not assumed fixed. Full completion requires every row to have supported closure; blocked rows remain unresolved.

Independent source checkpoints while P0 is blocked: [W1 full-origin guard](evidence/P1-W1-origin-checkpoint.md), [W2 group recovery visibility](evidence/P1-W2-group-recovery-checkpoint.md), [Q1 identity journal coalescing](evidence/P1-Q1-identity-journal-checkpoint.md), [C1 community outcome classification](evidence/P1-C1-outcome-checkpoint.md), and [H1 battery/internet panel](evidence/P1-H1-health-panel-checkpoint.md). Each passed focused tests; none has an installed/live gate.

The [P1 source-build checkpoint](evidence/P1-build-checkpoint.md) records 1,096 passing native unit tests, 8 passing H1 Flutter widgets, a successful continuity-signed local release build and its APK hash. It has not been installed or accepted live; version 56 is reused by this uninstalled build, so the hash identifies the artifact.

Independent [P3 heartbeat read-route repair](evidence/P3-heartbeat-read-checkpoint.md): the live PHP source now implements authenticated latest/history reads, with a saved rollback copy and 12 passing isolated backend tests. This does not advance P3 past `planned` while P0–P2 and the rest of P3 remain open. The H1 battery hysteresis source now pauses at 15% and resumes at 20%; 70 focused Android tests and 10 Flutter widget tests passed. The earlier APK predates that edit.

The [config correlation checkpoint](evidence/P0-config-correlation-checkpoint.md) adds a per-request UUID to Android Cards calls and backend config 5xx logs. A forced failure and a live unauthenticated route check passed; the historical 500 cause still needs a fresh correlated occurrence or stronger server evidence. The [storage follow-up](evidence/P0-storage-follow-up.md) records the host at 94% used with fresh shared-tenant raw dumps under the normal cleanup schedule; off-host Cards backup remains unproven.

Latest source validation: **1,098 Android tests passed** and a continuity-signed **0.10.44 (57)** release APK built (SHA-256 `c4858edbc079a3878fa998f4db36983fc23a37d88e0531de31485f84c2a0ce48`); see [build checkpoint](evidence/P1-build-checkpoint.md). It is not installed. The storage follow-up now also records that the shared MySQL backup directory became empty before the documented cleanup, with 54 GB disk free but unverified backup coverage. P0 remains blocked.

The [Cards HTTP cancellation checkpoint](evidence/P1-network-cancellation-checkpoint.md) subsequently replaced blocking `execute()` with cancellable OkHttp calls and a total call timeout. A held-response regression passed; the full Android suite is now **1,099/1,099**. A fresh release rebuild is required before installation. The scheduled 02:00 cleanup completed and disk headroom reached 79 GB, but it did not explain the earlier central backup-directory deletion.

Independent P2 preparation: [Shorts queue migration](evidence/P2-queue-migration-checkpoint.md) preserves version 1 TikTok and uncertain rows, adds a source type for future catalogue variants, and changes legacy pending selection to FIFO. Focused migration and receipt tests passed; independent YouTube content, rights manifest, cadence and canaries remain open. The latest APK predates this queue change.

The [Cards local backup checkpoint](evidence/P0-cards-local-backup.md) records a newly installed daily custom PostgreSQL dump job and a successful isolated restore of its first 542.9 MB archive with matching device/log/heartbeat/memory-version counts. Its first scheduled run, off-host copy/PITR and the separate central MySQL archive incident remain open P0 checks.

At 03:30 local on 24 September, the first scheduled Cards dump ran successfully and produced a verified 550.6 MB archive with a matching checksum; see the same checkpoint. Off-host copy/PITR and the central MySQL incident remain open.

Independent P4 source checkpoint: [consented heartbeat producer](evidence/P4-heartbeat-producer-checkpoint.md) now posts bounded health facts from the existing sweep about every five minutes after separate owner opt-in. A network-boundary test passed. The installed phones have not enabled the new consent; durable offline event sync and server availability semantics remain open.

The [availability checkpoint](evidence/P4-availability-checkpoint.md) removes the backend's unconditional `active: true`: authenticated status and latest heartbeat now expose unknown, online, healthy, degraded or stale using a 15-minute freshness window. A backed-up additive production migration records which fields new heartbeats actually observed; old rows expose their defaulted values as unknown. The Cards backend suite passed **17 tests/80 assertions**. The client has no live heartbeat evidence.

Latest release build after the queue, HTTP cancellation and heartbeat-producer source: Android unit suite **1,102 tests across 172 suites**, Flutter widget suite **10/10**, and `:app:assembleRelease -PdeviceContinuitySigning` succeeded. Local APK `app/build/outputs/apk/release/app-release.apk` is 34,141,548 bytes, package `co.sanaa.agent` version **0.10.44 (57)**, SHA-256 `61b1c9401cb1def34cfa7887505f66a17c103e01299eca20f7ae896a32d2258d`; `aapt` and `apksigner verify` confirmed metadata and continuity signer SHA-256 `83a1760330649b8de634583dde0a3ec853751fcdddea41fbf2cf026315960729`. This candidate is **uninstalled**. Earlier same-version APK hashes in the build checkpoint identify superseded local builds.

The [signed Terminal shop switch checkpoint](evidence/P3-shop-switch-checkpoint.md) records OPPO's live signed Cards observation moving from `708:128` Sanaa Media to `319:37` Osa Gadgets and back on release 59, while TPS stayed `254:24`. OPPO Terminal's account screen matched Osa Gadgets, and a TikTok editor showed Osa Gadgets product creative. A Settings payload omission was found during the return check and fixed. Current **0.10.48 (61)**, SHA-256 `98f4b91d279275370f6c00dc76ee9bf690e0377bea9b1add782f5cdbc102e253`, is installed and certified on **both OPPO and TPS**; OPPO Settings visibly shows “Verified: Sanaa Media,” and read-only inspection returned 47 listings for signed scope `708:128`. Old-shop job quarantine on-device, automatic Settings refresh during another switch and verified publication canaries remain open, so P3 and the full mission are not live verified.

The owner clarified that the shop currently signed in to Soko Terminal is the shop Amara should use, even when one person owns multiple shops. This resolves the earlier uncertainty about TPS's observed `254:24` as a policy choice. OPPO's signed switch and return are now observed; external side-effect scope still needs canary evidence.

The [P2 cadence checkpoint](evidence/P2-cadence-checkpoint.md) adds receipt-derived, restart-safe YouTube interval and daily cap handling, distinguishes uncertain uploads from proven pre-dispatch failures, and prevents unresolved duplicates from being marked verified. The full Android suite passed **1,108 tests across 174 suites**. The change is included in release 61 on both devices. Independent catalogue videos, rights manifests, owner timezone controls and approved Shorts canaries remain P2 work.

The [P5 fleet page checkpoint](evidence/P5-fleet-page-checkpoint.md) adds a shop → device map with paginated activity and heartbeat drilldown, unknown-aware health labels and a 1,001-device pagination fixture. Backend tests passed 20/104; unauthenticated live route redirects to admin login. Full job → attempt → receipt navigation remains dependent on P4 events, so P5 stays planned.

The [owner chat canary](evidence/P1-owner-chat-canary.md) exposed a contradictory final report: Amara had verified 14 TPS shop products but answered as if none existed after a later Dashboard navigation failure. Failed-task reporting now retains verified observations and names the blocker. Model product-memory feeds are being made signed-shop scoped, with legacy unscoped rows withheld. Focused tests and a replacement APK are pending; this canary did not produce the requested three-action sales review.

The replacement [owner-chat evidence](evidence/P1-owner-chat-canary.md) records version 63's honest TPS replay and version 64's source-level three-action sales review. Version 64 passed 1,114 Android tests and was installed on both phones, but OPPO's delayed certificate found repeated Android 11 crashes from `LocalDate.ofInstant` in the Shorts cadence path; the initial install checks had missed them. Version 65 replaces that call with an Android 11-compatible path and is building. OPPO is not considered ready until a delayed certificate passes; the three-action replay is still open.

Version **0.10.52 (65)**, SHA-256 `245de85f99e5a40f664b60b75f119d1e60fac7d855bee852b70176718d3f2246`, passed 1,114/1,114 Android tests and is installed on both phones. Both full certificates passed, and OPPO's same PID/empty crashed set held through the delayed recheck. The TPS [owner chat canary](evidence/P1-owner-chat-canary.md) delivered three grounded read-only sales actions for signed Free Line Stationery, with no external post or message. Full P0–P8 completion still requires the remaining phase gates, including YouTube Notice detail, external publication canaries, durable P4 events, P5 receipt links, pairing/chat/learning, backup coverage and the soak.

The [P3 binding-history checkpoint](evidence/P3-binding-history-checkpoint.md) adds live signed shop revisions and a Filament Shop history tab. A fresh local PostgreSQL archive was validated before migration; 22 backend tests/117 assertions passed, and the production backfill found two signed devices with two history rows and no unmatched revision. Earlier switches cannot be reconstructed, and a post-migration live switch remains to be verified; P3 stays open.

The [P4 event-sync checkpoint](evidence/P4-event-sync-checkpoint.md) records a bounded authenticated Cards event endpoint, revision-scoped work outcomes, a durable Android SQLite outbox, and a Work evidence admin tab. Before migration a verified local 588 MB PostgreSQL archive was made. The full Android suite for v66 passed **1,115 tests/177 suites**, Flutter v67 passed **42/42**, and the latest Cards suite passed **27 tests/150 assertions**. Continuity-signed **0.10.54 (67)**, SHA-256 `9cca22a737d3ccf597cf928b72cc68afa30ca54acd0efd72a24ecf67692fa38d`, is installed on OPPO and TPS with matching hashes and immediate full certificates. Both phones now have Cards work and health reporting enabled through the visible owner Settings controls. Canonical event ingestion, delayed v67 certificates, full effect receipts, retention/scale and recovery gates are still open; P4 and P5 remain planned in the phase checklist.

Follow-up: both phones submitted device-originated work events to Cards under their signed shops. TPS v68 also submitted a fresh TikTok failure classified `media_dimensions_invalid`. A live catalogue audit found an AVIF primary image mislabelled `.jpg` for TPS product 951, with supported same-product gallery alternatives. Version **0.10.56 (69)** uses a decodable image from that gallery before publication; 1,121 Android tests passed. It is installed on both phones with matching hash and immediate full certificates. TPS's pre-update posting breaker was released; a delayed certificate and verified new post are still required. P0–P8 remain incomplete.

TPS's first scheduled v69 feed attempt used the “Spring file” gallery fallback, selected a sound and reached `post_tiktok VERIFIED` at 12:28 UTC; Cards received its completed work row. The next feed attempt and a Story reached sound selection but ended `UNCERTAIN` and were not replayed. Delayed certificates and intermittent upload/verification investigation remain; the TikTok reliability issue is only partly closed. P0–P8 remain incomplete.

## 24 September 20:49Z evidence refresh

Both phones reconnected and exported journals: OPPO v69, TPS v70. See evidence/REFRESH_20260924.md and JSON for per-installation counts. TPS has 31 VERIFIED feed transitions but three uncertain uploads and 15 unreadable-caption community failures. OPPO reply failures recur across four keys; group recovery holds and Shorts source failures remain. Five focused tracking rows were added, bringing the register to 50. No phase or issue was closed by this audit. Final readiness before livestreaming requires BEFORE_LIVESTREAMING.md reports and all existing mission gates.
