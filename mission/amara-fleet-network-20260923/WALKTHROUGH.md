# Walkthrough and operational handoff

## Before implementation

Read all mission documents and dirty git state in Android and backend. Confirm applicable local instructions. Re-discover ADB serials; do not assume yesterday's port. Capture device build, platform app versions, owner state, paired shop, enabled modules and consent without copying secrets. Preserve databases/receipts before schema migration through supported backup/export. See the installed `amara-apk-device-recovery` skill before any future device deployment/recovery. Keep the same signing continuity and app data.

## Milestone demonstration

1. **P0/P1:** Open owner health screen. Demonstrate low battery and offline fixture/state, then recovery. Show a failed group job's typed dependency and bounded retry while another eligible task proceeds. Inspect a long reply's persisted draft and delivery evidence after restart. Show manager-alert status separately from issue status.
2. **P2:** Select a product and YouTube interval. Preview a YouTube-specific video and rights manifest. Confirm destination/channel/visibility. Run an authorized canary and drill into the verified receipt; if dispatch is uncertain, show the review hold. Restart and prove next due time survives without catch-up burst. Inspect legacy queue migration.
3. **P3/P4:** Pair or refresh a test worker using existing workflow. Show config desired/applied revisions. In a controlled fixture disconnect telemetry, perform local safe work, reconnect and show exactly one server event per event ID. Show an explicit owner Off event separately from an unexpected last-seen gap.
4. **P5:** Cards admin → Amara Devices → shop/device graph → selected device → work timeline → job → attempt → receipt. Filter failures and uncertain actions, then battery/network history. Export an authorized redacted JSON summary. A shop-scoped admin cannot traverse another tenant's edge.
5. **P6/P7:** Sign into Sanaa from a second client, scan/approve expiring QR, inspect grants. Submit a status or prepare-content command to home worker. Show queued/acknowledged/running/completed states and originating-client result. Repeat with worker Off, expiry and revoked client; prove no dispatch.
6. **P8:** Compare compatible-device failure evidence, propose a recipe, run fixtures and shadow test, canary on one device then the other. Show learning version on the map, rollback and resulting event. Do not label candidate generation as successful learning before observed improvement.

## Rollback and incidents

Stop new capability through feature flag; preserve queued/uncertain receipts. Restore previous recipe/config revision and verify reported applied revision. Database changes use expand/contract; do not drop new evidence to roll back a client. APK downgrade may be unsupported: prepare same-signature compatible forward rollback build, never uninstall to bypass it. Restore runtime permissions only via supported owner-authorized flow.

Auth issue: revoke affected credential/pairing, retain audit, re-pair through challenge. Backend outage: local permitted work continues with bounded spool; show stale admin state. Full disk: protect receipts and report spool/storage pressure, use reviewed retention of reproducible or expired data. Power loss: backend shows unknown/unreachable, recovery sends historical gap and boot evidence. Publication uncertainty: reconcile own-channel/recipient evidence before any new effect.

## Progress procedure

After each phase update progress.json, append an evidence report and run check_progress.py. Reports must separate implementation, tests, deployed version and live proof. Record blocked dependencies and next safe step. Never use document creation as an implementation milestone.
