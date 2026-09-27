# Baseline and evidence

## Capture

Window: **2026-09-22 21:11:35Z to 2026-09-23 21:11:35Z**. Host and both device UTC clocks agreed within a few seconds at capture. Both connected phones exported their evaluation journals through the existing diagnostic receiver; seven OPPO files and six TPS files were copied. The summaries filter by event timestamp and deduplicate identical JSON lines. No malformed lines were found. This is a mixed-build window, not a controlled build-56 experiment.

| Device | Current ADB address | Installed build | Window events | Latest world in window |
| --- | --- | --- | --- | --- |
| OPPO CPH1933 | 192.168.1.65:37845 | 56 / 0.10.43 | 56,200 | Network true, battery 64%, thermal NORMAL |
| TPS450M | 192.168.1.66:5555 | 55 / 0.10.42 | 80,210 | Network true, battery 20%, thermal NORMAL |

ADB connectivity is not proof of validated internet or app health. IP addresses are diagnostic transport addresses, never identity. TPS has no queue_inspection event inside this exact window; an empty summary is missing evidence, not an empty queue.

| Journal outcomes | OPPO | TPS450M |
| --- | --- | --- |
| YouTube Shorts | 9 DONE, 17 ESCALATED, 3 FAILED | No outcomes observed; enablement/eligibility unknown |
| TikTok publishing | 39 DONE, 23 FAILED | 27 DONE, 26 FAILED |
| WhatsApp inbound | 44 DONE, 30 FAILED, 2 ESCALATED | No inbound outcomes observed |
| WhatsApp groups | 153 FAILED, 99 SKIPPED | 2 FAILED |
| Config sync | 2 FAILED, 1 DONE | 2 FAILED, 1 DONE |

These are attempt outcomes, not unique posts/messages, success rates, or evidence that all eligible work was served. OPPO additionally records 9 `post_youtube_short/VERIFIED` transitions and 27 `reply_whatsapp/VERIFIED` transitions. Reconcile transaction IDs with actual receipts before counting unique delivery; 44 DONE replies must not be presented as 44 sends. TPS records 27 TikTok VERIFIED and 3 UNCERTAIN transitions. No fresh manual inspection of each published post was performed.

## Diagnostic findings, ordered by impact

1. OPPO group work: 151 failures say the Terminal session remains unavailable after reopening. Two more could not open Terminal. Seven skips explicitly say the group schedule is paused/not due; the other skip outcomes have no failure reason. Diagnose session recovery and eligibility before changing media sharing. Avoid repeatedly consuming screen time on the same unavailable dependency.
2. OPPO inbound: 29 failures could not verify both the originating conversation and incoming message, one detected a conversation change, and two escalations exceeded the budget. Keep recipient verification; improve origin resolution and durable draft recovery. Do not weaken matching to increase success counters.
3. OPPO Shorts: 14 escalations could not locate the verified queued TikTok ad among recent own-profile posts; three failures report unavailable Terminal session. Other events include an unverified import, a pre-dispatch external-trigger failure, and `description_not_observable`. These are operational blockers separate from Notices on videos already published.
4. Both devices: two config sync HTTP 500 failures and one DONE in the window. Server-side cause remains unproven; inspect bounded, redacted request/error correlations before changing auth or retry behavior.
5. Backend database at 21:13:56Z: 21 agent devices, 243 agent logs, 37 heartbeats total, **zero logs and zero heartbeats in the previous 24 hours**. Historical capture exists; continuous reporting is not established. Opt-in/off state and call-site coverage may contribute; do not silently enable telemetry.
6. Static route defect: `routes/api.php` assigns heartbeat latest/history to AgentController, but the methods were found on AgentHeartbeatController. The POST heartbeat is implemented on AgentController. No sender matching heartbeat/battery_level was found in Android/Flutter source search. Add route, authorization and delivery tests before considering the admin data live.
7. Existing journals are dominated by `terminal_identity_observed`: 45,457 OPPO and 70,608 TPS events. Deduplicate unchanged observations; measure CPU, storage and battery before choosing sampling limits. 1,193/2,971 session-budget deferrals are evidence to inspect fairness, not proof of starvation by themselves.
8. Capture host storage was full (387 GB filesystem). Generated `app/build/intermediates/merged_native_libs` was temporarily moved intact to memory storage to free roughly 1.7 GB, then restored to its original path after filesystem headroom became available. Source, APKs and prior evidence were not removed. Production storage growth/retention needs an explicit P0 incident, not just app fixes.

## Existing components to extend

Paths below are relative to Android checkout unless prefixed Backend (root `/var/www/cards.sanaa.ug`).

| Area | Existing implementation | Gap / consequence |
| --- | --- | --- |
| Shorts | `core/shorts/ShortsQueue.kt`, `ShortsWorkSource.kt`, `ShortsPublisher.kt`, `ShortsSettings.kt`; under `app/src/main/kotlin/co/sanaa/agent/` | SQLite queue selects newest PENDING; publisher insists on verified TikTok source and exports finished media. Separate YouTube catalogue source and migration needed. |
| Rendering | `actions/AmaraAdRenderer.kt`, `AmaraVideoEncoder.kt` | Extend with platform profile/storyboard; do not add unrelated rendering service first. |
| Safe execution | `core/work/WorkQueue.kt`, `AmaraWorkLoop.kt`, `SafetyGovernor.kt`, `core/SideEffectTransaction.kt` | Reuse budgets, leases, recipient/shop binding and uncertain-action reconciliation. |
| Health | `modules/AgentHealthMonitor.kt`, `HealthAlertDelivery.kt`, `HealthIssueHistory.kt`, `core/knowledge/ConnectivityMonitor.kt` | Existing battery 15% pause warning and 16–20% advisory; make UI prominent and persistent, add truthful remote liveness. |
| Activity export | `api/BackendSync.kt`, `core/TelemetryPolicy.kt`, `ModuleActivityStore.kt`, `EvaluationJournal.kt` | log() is opt-in, immediate HTTP, catches failure as false; no durable outbox in this method. Summary/error redaction exists; metadata passed through requires recursive allowlist. |
| Pairing | Backend `app/Services/AgentDevicePairing.php`, Android BackendSync | 24-hex code, hashed, ten-minute TTL, transaction/lock, consumed and token rotated. Extend this rather than replacing it. |
| Registry/admin | Backend `app/Models/AgentDevice.php`, `app/Filament/Pages/AmaraDevices.php`, blade view, AgentDeviceManagement | Existing list capped at 100 and latest 15 logs, config edits and change audit. Add pagination, graph and tenant-scoped drill-down. |
| Backend ingestion | Backend `app/Http/Controllers/Api/AgentController.php`, AgentLog, AgentHeartbeat | Authenticated device logs exist; success boolean and server timestamp insufficient for rich event states/dedup/offline ordering. |
| Identity | Backend composer has Laravel Passport; AppServiceProvider enables password grant; OAuth device-code migration exists | This is not proof of a complete OIDC provider or shared SSO. Audit account/shop membership and clients before choosing federation design. |
| Configuration | SecureConfig default and live app host are `cards.sanaa.ug` | `.co` requested by owner is not yet verified as this API's canonical host. Never send tokens to a guessed host. |

Full Android source prefix: `app/src/main/kotlin/co/sanaa/agent/`. Current checkout contains many prior uncommitted changes. Record git state before any implementation; do not reset them.

## YouTube screenshot interpretation

The supplied image shows YouTube Shorts and Notices, not Merchant Center listings. Official YouTube documentation says Notices cover several policy areas; some are informational with no immediate limitation. The screenshot does not expose the individual notice impact. The Google AI screenshots therefore do not establish a blanket phone-number ban or the cause here. Low view counts do not prove suppression.

P0 must inspect a representative affected video's own-channel notice details and one unaffected comparison, recording video identity, reach/monetization/feature impact, affected audio/time segment if shown, and capture date. No automatic deletion, dispute, or repost. Current assumption: create YouTube-specific, useful product demonstrations with original or explicitly licensed audio, but do not claim that this alone resolves existing notices. See SOURCES.md.

## Evidence retention

Sanitized aggregate files are committed with this mission. Raw copied journals are archived locally in ignored `artifacts/fleet-plan-20260923/device-journals.tar.gz`; file hashes are in device-summary.json. Do not commit raw customer conversations or credentials. Device journals are app-reported evidence, and may omit work outside their instrumentation. The plan does not claim a live end-to-end backend reporting test.
