# Amara system status

Latest scoped build/device check: [Autopilot media and catalogue repair](AUTOPILOT_MEDIA_CATALOGUE_20260905.md),
2026-09-05 approximately 11:03 UTC. Refer there for the installed checksum and
service-readiness results; business-workflow certification remains incomplete.

Newer scoped repair: [TikTok product binding and WhatsApp ingestion](TIKTOK_CONTENT_REPAIR_20260905.md).
The historical device/ledger snapshot below remains timestamped; do not treat it
as a fresh certification of the newer patch.

Last inspected: 2026-09-05, 12:59 EAT (09:59 UTC).
Scope: current device journals, transaction ledger, runtime readiness, and repository
implementation history. This is a system-wide status inventory, not a claim that
every feature was exhaustively retested today.

## Verdict

**No end-to-end business workflow is yet certified COMPLETE under the report rules.**
Amara is executing autonomous work and has verified WhatsApp sends. Reliable
unattended Soko → TikTok operation remains unfinished. No revenue is certified.

| Area | Status | Evidence and remaining gate |
| --- | --- | --- |
| Android runtime | PARTIAL | PID 28226; accessibility bound, crashed set empty; overlay allowed at latest check. Long-running/reboot survival not certified. |
| Autonomous work loop | PARTIAL | Earlier live logs demonstrated ~30-second cycles; latest learning records show inbound work outcomes. Device budget, identity, consent and prerequisites still apply. |
| WhatsApp replies | PARTIAL | Ledger has 12 VERIFIED and 4 FAILED reply transactions. Latest verified receipts at 09:28:49, 09:33:28, 09:53:52 UTC identify com.whatsapp, deliveryState=sent, confidence=0.85. This is UI-verifier evidence, not recipient confirmation or an independent message-quality review. |
| WhatsApp reliability | UNVERIFIED | Exact-chat failures, zero-send outcomes and provider HTTP 400 remain in history. Controlled 30-second takeover, owner-already-replied, OFF, context and restart tests remain. |
| Soko shop inspection | BLOCKED | Task journal 15: open_app verified correct Soko package; scan_soko_inventory failed at account phone-number sign-in. Staff PIN cannot replace account login/OTP. |
| Soko PIN storage | PARTIAL | Owner supplied PIN saved through masked Settings field; UI confirmed encrypted storage. Actual accepted account/staff login not certified. |
| TikTok publication | UNVERIFIED | Ledger: 6 FAILED, 2 UNCERTAIN, zero VERIFIED post_tiktok transactions. At 09:31:52 UTC publish tap accepted but newest profile post/caption could not be verified. Do not infer absence or republish as a test. |
| TikTok randomized cadence | PARTIAL | Durable 30-minute base with 20–42-minute randomized gap implemented/tested and observed in device preferences. Successful repeated scheduled publications not demonstrated. |
| Jiji/Jumia intelligence | BLOCKED / UNVERIFIED | Jiji no verified listings in recent scan. Jumia missing configured vision model. Dashboard/report creation is not evidence of useful market observations. |
| Commercial planning | BLOCKED | Recent cycle reports owner timezone unconfigured. Do not infer jurisdiction/timezone/consent policy from the phone clock. |
| Memory and learning | PARTIAL | Durable outcomes, errors, summaries and routine suggestions implemented. Error-history persistence tested. Not autonomous code repair or model retraining; learning usefulness needs measured repeat performance. |
| Memory backup/restore | UNVERIFIED | Existing implementation; authenticated fresh-install restoration not certified in this run. |
| Owner natural-language commands | PARTIAL | Read-only Soko instruction executed through same CommandExecutor used by chat; produced failed/incomplete journal result. Broader intent/negation ambiguity evaluation pending. |
| Work error history | PARTIAL | Redacted grouped failures/counts/last-seen UI implemented and deployed; unit/UI checks passed. No claim causes are automatically resolved. |
| WhatsApp Groups | UNVERIFIED | Dedicated groups-only contacts view exists in workspace with five targeted tests previously passed; not in installed build. Naalya E-Trade uniquely identified; commercial consent unknown. Ad window/reply trigger/daily cap not supplied, no group-ad workflow activated. |

## Installed baseline

- Device OPPO CPH1933, Android 11, serial `7aef1a4c`.
- Package `co.sanaa.agent`, version 0.10.0 (13).
- Last certified installed SHA-256:
  `21b5cb9fa5f44b1e95be4090de35a82cbd5c7c1db2ee4087059fe702c9fcf5b6`.
- Prior certificate on this build: accessibility, overlay, notification access,
  battery exemption and foreground AgentService passed; sampled fatal/ANR count zero.
  Scheduler text matches are not counts of distinct schedules.
- Last recorded owner settings: failure cooldown cap 0; daily screen allowance 1,440
  minutes. These do not disable quiet hours, secure locks, per-item backoff, consent,
  duplicate protection or battery/heat limits. Do not change them during diagnosis.
- Workspace is heavily dirty with pre-existing user/agent changes. Installed APK is
  not equivalent to all current workspace files. Do not reset/clean the worktree.

## Repairs already made

- Durable queued inbound grace; faster wake checks; scores order rather than veto work.
- Owner-configured quiet hours replace hidden TikTok/night windows.
- Exact WhatsApp target/composer checks; stage-specific navigation failures.
- Durable TikTok product/caption binding and non-repeat of uncertain transactions.
- Explicit Soko failure propagation; product scan no longer calls login-screen data
  “zero products”; credential provider used instead of empty PIN in report sections.
- More grounded reply/report instructions; owner-command failures recorded in learning.
- Owner-facing secure PIN field, local lockout recovery, configurable failure cooldowns.

See [command-led test record](../mission/amara-command-led-testing-20260905.md),
[cadence record](../mission/amara-random-cadence-20260905.md), and
[owner controls](../mission/amara-owner-controls-20260905.md) for implementation evidence.

## Latest supervised command

“Check the Soko product inventory without editing or publishing anything. Tell me
whether you can access the shop and which products have usable photos.”

Submitted through existing debug owner-command ingress, not external-app navigation.
Task 15 correctly reports incomplete. Product availability/photos are **unknown**.
Next required owner action: sign into Soko on the OPPO, including any OTP.
