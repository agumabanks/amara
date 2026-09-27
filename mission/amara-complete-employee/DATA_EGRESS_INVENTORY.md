# Data-Egress Inventory — Amara

Every network request the app can make. This is the authoritative disclosure document
(Complete-Employee Phase A6). "Owner setting" names the independent control in
`SecureConfig`. Defaults are the strictest safe posture; nothing here may be bypassed by
model output or untrusted content.

| # | Request | Destination | Purpose | Payload fields | Classification | Credential | Owner setting | Default | Retention | Redaction |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | `POST /api/agent/register` | Owner backend (`cards.sanaa.ug`) | One-time device registration | device_id (ANDROID_ID), agent_name, business_name, owner_phone | Device + business identity | none (bootstrap) | `configSyncEnabled` | ON (required to operate) | Backend-side; unknown | none — identity fields only |
| 2 | `GET /api/agent/config/{id}` | Owner backend | Fetch remote configuration | device id in URL; response stores endpoint/model/group lists locally | Configuration | agent token (`Authorization`) | `configSyncEnabled` | ON | Local prefs only | n/a |
| 3 | `GET /api/agent/status/{id}` | Owner backend | Poll backend status | device id in URL | Device identity | agent token | `configSyncEnabled` | ON | none persisted | n/a |
| 4 | `POST /api/agent/log` | Owner backend | Operational telemetry export | device_id, module, action, platform, summary, success, escalated, error_message, metadata | Operational metadata | agent token | **`telemetryOptIn`** | **OFF** | Backend-side | summary/error redacted+truncated via `TelemetryPolicy`; skipped entirely when OFF |
| 5 | `POST /api/agent/escalate` | Owner backend | Safety handoff to owner | device_id, agent_name, message, context, urgency, suggested_replies | Safety escalation content | agent token | always available (owner-directed control channel) | ON | Backend-side | message/context/replies redacted via `Redactor.redactForExport` |
| 6 | `POST /api/agent/generate-ad` | Owner backend | Ad creative generation | device_id, full Soko listing raw record | Business listing data | agent token | **`artifactUploadOptIn`** | **OFF** | Backend-side | none beyond opt-in gate |
| 7 | `POST api.groq.com/chat/completions` (text) | Groq (model provider) | Planning/caption/reply generation | system+user prompts (business memory context, owner task, untrusted envelopes), model name | Business context + untrusted excerpts | Groq API key (header) | implicit: any AI feature use discloses provider; key entered by owner | ON when key configured | none stored by Amara | prompts embed redacted/enveloped content only |
| 8 | `POST api.groq.com` (vision) | Groq (model provider) | Listing screenshot analysis | base64 screenshot + prompt | Screen imagery | Groq API key | **`visionConsent`** | **OFF** | none stored by Amara | prompt text only |
| 9 | `POST /api/agent/heartbeat` | Owner backend | Show current device health | device_id, app version, uptime, Accessibility bound, known pending count, known battery percent, charging | Device health metadata | agent token | **`operationalReportingEnabled`** and `configSyncEnabled` | **OFF** | Backend-side; existing heartbeat table | Allowlisted fields only; no conversations, listings or blocker text |
| 10 | `POST /api/agent/shop-observation` | Owner backend | Keep the observed device shop current after a Terminal account switch | device_id in body; short-lived signed Terminal assertion in header containing seller ID, shop ID, shop name and expiry | Device/shop identity | agent token plus signed Terminal proof | `configSyncEnabled` and owner On | ON while Amara is On | Backend device observation | No catalogue, customer or message content; server verifies signature and device credential |
| 11 | `POST /api/agent/events` | Owner backend | Replay durable work outcomes to the shop/device graph | device ID; event/installation/boot IDs; sequence; signed shop binding revision; salted job/attempt IDs; action time; work kind/status; categorical failure and duration | Work evidence metadata | agent token | **`telemetryOptIn`**, `configSyncEnabled` and owner On | **OFF** | Bounded local SQLite outbox until acknowledgement; backend retention policy pending | No customer messages, raw prompts, phone numbers or product content; server enforces allowlisted scalar facts |

## Compartmentalization rules enforced in code

- Customer conversations enter model prompts only for the selected chat, wrapped as
  UNTRUSTED DATA envelopes, truncated, and redacted (`ConversationEngine`, `FollowUpEngine`).
- Group replies use group-visible context only; private direct-message history never
  enters a group prompt.
- PINs/OTPs/tokens never enter prompts, receipts, or exports (`Redactor`, tested).
- Screenshots stay device-local unless `visionConsent` is granted (rule 8).
- Escalation is deliberately NOT gated behind telemetry consent: it is the owner's
  safety channel. Its content is still redacted.

## Retention and owner data controls

- `retentionDays` (default 90): `AmaraMemory.pruneExpiredData` deletes conversations,
  actions, products, and findings older than the window on health-worker cadence.
- Owner deletion: `deleteOwnerBusinessData()` wipes all recorded business activity while
  preserving schema (tested).
- Owner export: `exportOwnerData()` returns redacted JSON of every record class (tested).

## Known limitations

- Rules 1–6 and 9–11 go to the owner-configured backend over HTTPS; its server-side retention is
  outside this repository's control and is disclosed as "unknown".
- ANDROID_ID (rule 1) is a stable device identifier; it cannot be redacted without
  breaking registration. Disclosed rather than hidden.
- Model-provider prompts necessarily include business context needed for the task;
  scoping is enforced per call site (task-purpose binding), not globally.
