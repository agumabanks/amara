# WhatsApp recovery hardening — 10 September 2026

The owner requested stronger WhatsApp reliability after the group scheduling and delivery repairs. This follow-up addresses two observed failure paths without replaying uncertain sends.

## Changes

- Groq now tries the distinct configured fallback once when the primary returns HTTP 401, as well as on rate limiting. A fallback-only configuration also works. Two invalid credentials stop without unbounded retries. HTTP 403 and unrelated client errors do not trigger credential fallback.
- Lost conversation routes can be rebuilt from active WhatsApp notifications after a service restart. Only WhatsApp-owned PendingIntents are accepted; opening the route still requires the exact conversation and incoming message before replying.
- A disconnected notification listener requests Android rebinding. Inbound route readiness gets a bounded 12-second window.

The server credential probe found an invalid primary (401), while its fallback succeeded (200). This does not establish that the phone has the same primary credential: its observed Groq request succeeded.

## Evidence and limits

Pre-update database backup: `artifacts/defence-migration-20260909/whatsapp-hardening-before.tar`. Its queue contains 72 completed inbound replies, 43 completed broadcasts, one pending broadcast and seven inbound items requiring review. Queue completion counts are not independent proof of delivery to every recipient.

Notifications already dismissed cannot supply a recoverable route. Uncertain sends and unresolved destination holds remain held; this update does not automatically replay them. Existing per-group schedules, quiet hours, explicit destination checks and persisted send verification remain in effect.

## Validation and installation

- Gradle targeted suite and debug APK build passed: 95 tests, zero failures, errors or skips. Coverage includes model fallback, WhatsApp parsing, durability, destination identity, group cadence, follow-up isolation, group breaker isolation and side-effect transactions. Notification reconnection recovery itself still needs a sustained live interruption run.
- Installed in place on OPPO CPH1933, serial `7aef1a4c`, version `0.10.0` (13). APK SHA-256: `2f844b823f64208252324c2f5c70e3fc5af4cd000638d638911a3b11ab83073b`; the installed APK checksum matches.
- Process `4569`; Accessibility enabled/bound/not crashed; overlay, notification permission, battery exemption and foreground service passed. Notification system dump also lists a live Amara listener binder. Scheduler dump contains 70 package matches; sampled recent fatal/ANR count is zero.
- Evidence: `whatsapp-hardening-install.txt`, `whatsapp-hardening-certificate.txt`, and `whatsapp-hardening-notification-binding.txt` under `artifacts/defence-migration-20260909/`. Inspection pause cleared and work loop wake requested after installation. No external message was sent merely to certify deployment.

This repair is not seven-day acceptance evidence and does not change the mission's NOT DEFENSIBLE decision.
