# Amara Devices admin — September 13, 2026

## Implemented

Cards app launcher includes Devices, opening `/admin/amara-devices`. Admin and super-admin users can inspect permanent device accounts, search by name/business/device ID, set a label, manager WhatsApp number, shop operating brief and separate TikTok/WhatsApp channel instructions. Unconfigured channels preserve the device setting; changing a label does not clear an unconfigured manager number. Admin edits merge with encrypted configuration and retain unrelated credentials. Audit history records actor and field names, without secret values.

The page shows last reported heartbeat, last configuration fetch, current backup age, encrypted backup version history and recent reported activity. These observations are explicitly distinct from proof of app login, applied settings or successful communication. Selecting a device scrolls to its controls. No raw memory payloads or API credentials appear in the page.

Backend snapshots now retain changed versions per device under encryption, including the old current snapshot when first replaced. Consecutive identical payloads do not create duplicate versions. Writes lock the account transactionally. Existing token authentication is now required for configuration fetch, setup of an existing account and heartbeat. Public re-registration can no longer replace an existing device's credential. Default ad listing lookup now uses the requesting device's registered business instead of a global business.

Android 0.10.2 (15) consumes the channel/manager/brief settings and supplies the business brief to the conversation engine. Remote settings cannot resume the owner's local Off state; omitted channel values are left alone. Memory backup remains an existing opt-in setting.

## Validation and deployment

- Additive backend migration applied on Cards.
- 11 isolated SQLite backend tests, 33 assertions: encrypted version history, account separation, credentials, admin roles, page rendering/save and preserving device choices.
- Production frontend assets built; Blade templates compiled. Unauthenticated request to the admin page redirects to login.
- Android build/device verification results are recorded below when completed.
- Backend changes are in the parent Laravel directory, which is not a Git checkout. Initial controller/launcher copies are under `storage/app/amara-admin-backup/`.

## Boundaries still requiring implementation or owner setup

- Verified immutable Soko seller/shop binding and local shop-specific queue/memory namespaces are not implemented. Current registered business names are not proof of the Terminal login. Shop reassignment is deliberately unavailable; do not certify different-shop commercial automation based on this module.
- New account enrollment is still public; admin approval before first enrollment and full account revocation remain follow-up work. Existing-account credential recovery now uses a 10-minute, single-use, device-bound admin pairing code. Codes are stored only as hashes, issuance/redemption are audited, redemption rotates the matching account credential, and memory stays in place. No credentials are copied between devices.
- No admin backup rollback/transfer UI, retention policy or automatic cross-device memory sharing was introduced. Retained versions are available for a future audited same-account recovery workflow.
- No new Android heartbeat reporter was added. Missing reports remain unknown; successful config fetches provide a separate observation.
- TPS WhatsApp was previously at registration welcome. Owner login, manager recipient and an explicitly authorized alert test remain outstanding. TikTok access alone does not prove shop-account authorization.
- Shared improvement should use reviewed, redacted failure categories/evaluations; keep customer conversations and business memories isolated.

## Live authentication finding and recovery

Installing build 14 revealed that TPS450M's existing stored credential was rejected by Cards (HTTP 401); earlier unauthenticated config access had hidden that problem. OPPO config fetch succeeded. Added the admin pairing flow and an owner-visible Settings dialog in build 15. A Flutter widget test verifies the displayed device identity and explicit Connect submission. Backend tests verify device binding, expiration, replay rejection and memory preservation.

Build 14 passed local certificates on both devices: Accessibility enabled/bound, no crash, overlay, notification listener, battery exemption, foreground service and scheduled jobs. Latest build 15 deployment and pairing results follow below.

## Final device result

Both OPPO CPH1933 (`7aef1a4c`) and TPS450M (`192.168.1.64:41923`) now run 0.10.2 (15). Installed SHA-256 on both matches the release artifact: `cb0c069a324f7b9532476f2a790d6e77012013dea849115d8c9ccfe64ab21622`. Both final certificates pass Accessibility enabled/bound/not crashed, overlay, notification listener, battery exemption, foreground AgentService, scheduled jobs and zero recent fatal/ANR matches. Certificates are bounded observations, not a soak-test certification. TPS streamed installation stalled; file-push installation succeeded without deleting app data.

TPS Settings displayed device ID `7e4c816b191658da`, matching existing Cards account 24. An authorized operator issued a one-use pairing code through the same pairing service used by admin, entered it on that phone and redeemed it. Cards confirmed the code was consumed and configuration fetched at 09:20:20 UTC. Account 24 remained unchanged; it had no backend memory snapshot before pairing and zero versions afterward. Local app data was preserved by replace-install. No customer messages or posts were sent as tests. OPPO account 4 also fetched configuration after the update. Device labels were added as OPPO CPH1933 and TPS450M, with audit records; business assignments and channel choices were not changed.

Final checks: 11 backend tests / 33 assertions, 5 targeted Android tests, 1 Flutter pairing widget test, successful optimized APK build and frontend/Blade build. Verified admin settings application logic and real authentication/config fetch; an end-to-end live channel change was not sent.
