# Proposed contracts, version 1

These are implementation targets, not deployed endpoints. Keep current `/api/agent` endpoints compatible and choose exact new routes during P3/P4. All times RFC3339 UTC, durations milliseconds, byte counts integers. Use JSON Schema validation, explicit enums, payload limits and unknown-version rejection. Do not trust arbitrary metadata from the existing log endpoint.

## Event envelope

```json
{
  "schema_version": 1,
  "event_id": "uuid",
  "installation_id": "uuid",
  "boot_id": "uuid",
  "sequence": 218,
  "occurred_at": "2026-09-23T21:11:00Z",
  "monotonic_ms": 503400,
  "event_type": "work.outcome",
  "binding_revision": 4,
  "job_id": "uuid",
  "attempt_id": "uuid",
  "effect_id": "uuid-or-null",
  "command_id": null,
  "platform": "youtube",
  "operation": "publish_short",
  "status": "verified",
  "reason_code": null,
  "duration_ms": 85000,
  "app_build": 56,
  "recipe_version": "youtube-v1",
  "facts": {"media_digest": "sha256", "verification_method": "own_channel_receipt"}
}
```

Server attaches authenticated agent_device_id, tenant_id, shop_id and received_at. Scope claims must match binding revision or be explicitly handled as historical uploads. Require event_id/installation_id/sequence/type/time/build; work events require job and attempt IDs. Accepted duplicate event gets duplicate ack, not another row. Same ID/different body is conflict; quarantine, never overwrite. Batch response has accepted IDs, duplicates and per-ID permanent/transient rejection codes. HTTP acceptance is not a publication receipt.

Event families: work proposed/started/deferred/outcome; effect claimed/dispatched/verified/uncertain/reconciled; owner power changed; process started/stopping; health changed/recovered; heartbeat; config applied/rejected; pairing issued/redeemed/revoked; command status; learning candidate/applied/rolled_back. Separate prepared, dispatched and verified. Keep skipped/deferred out of failure counts.

Allowlisted facts only: categorical failure/stage, app/package version, hashed media/product references, counters, timings, notice category and reach/earnings/features impact. No phone numbers, message bodies, contact lists, access tokens, passwords, raw prompts, exact location or screenshots by default. Product content/artifacts and memory backup retain separate consent. Redact nested objects/arrays at both client and server. A device token must never authorize bulk private memory access to other devices.

## Heartbeat

Fields: installation/boot IDs, measured_at, uptime_ms, owner_state, worker_state, accessibility_bound, network_validated, network_transport, backend_reachable, battery_percent nullable, charging nullable, thermal_state, storage_free_bytes, queue_counts_by_state, oldest_pending_age_ms, spool_depth/oldest_age, app_build, platform_versions, desired_config_revision/applied_config_revision. Unknown is null with reason, not false/zero. No heartbeat while completely powered off is possible; show freshness.

## Pairing and sign-in

Extend existing hashed ten-minute one-time pairing challenge. QR contains only an approved-origin URL with opaque challenge ID/nonce, never reusable bearer token or account password. Authenticated account session displays worker label, shop and requested scopes before approving; worker proves possession of its pending challenge. Atomically consume, rate-limit issue/redeem, bind intended device and account, log actor, rotate credential and revoke prior scope where appropriate. Deny expired/reused/wrong-device challenges. Revocation stops config/command access and closes paired sessions without erasing receipts.

Shared account auth: audit Passport clients/grants, existing Cards/mobile/Soko membership and Sanaa Chat integration. Prefer standards-based authorization code + PKCE for native clients; browser session/CSRF for web. Passport is OAuth infrastructure, not automatically OIDC identity federation. If OIDC is needed, decide on a supported provider after discovery; do not invent JWT login or share passwords/tokens across apps. Card identity is an account identifier, not proof of possession by itself.

## Command envelope

```json
{
  "schema_version": 1,
  "command_id": "uuid",
  "device_registration_id": 123,
  "binding_revision": 4,
  "type": "prepare_product_short",
  "arguments": {"product_id": "catalogue-reference", "platform": "youtube"},
  "issued_at": "2026-09-23T21:00:00Z",
  "expires_at": "2026-09-23T22:00:00Z",
  "idempotency_key": "client-generated-uuid",
  "expected_config_revision": 12
}
```

Server supplies authenticated actor/client/tenant/shop/scopes, not trusted client values. MVP commands: request status, prepare product content, request bounded sync, pause a module; enable external publishing only with explicit publishing scope and existing local consent. Reject unknown arguments/commands and unsupported capabilities. State machine: submitted → authorized → queued → acknowledged → running → verified/completed/failed/uncertain; also rejected/expired/cancelled/held_owner_off. “Delivered” never means executed. Restart preserves command/effect IDs. Cancel before dispatch; after dispatch return cannot-cancel and reconcile. Recheck authorization, binding, TTL and owner state immediately before irreversible action. Offline cached commands require a defined revocation freshness limit; default no new external dispatch without fresh authorization.
