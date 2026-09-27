# Acceptance matrix

Record command/test name, build/commit, fixture or device, result, artifact, timestamp and limits. Existing test suites are a starting point, not proof of these additions. Tests must exercise behavior and fault recovery rather than mirror helpers.

| Phase | Required scenarios | Pass condition |
| --- | --- | --- |
| P0 | Inspect exact affected/unaffected Shorts notice; correlate config 500; disk growth audit; inventory devices and policies | Evidence identifies cause or explicit blocker; no screenshot-based policy assumptions |
| P1 | Missing Terminal session; one group fails; long input collapsed; long draft restart; process death pre/post-send; model timeout; fairness under repeated failure | Proven safe retries only; no wrong recipient/duplicate; unrelated eligible reply begins within two configured scheduler budgets |
| P1 | 20→15→charging/recovery battery; offline/captive portal/backend-only failure; owner Off; denied notification/overlay; alert delivery failure | Correct persistent explanation, no alert storm or recursive job loop, work resumes only when actual policy permits |
| P2 | Same product across platforms; absent TikTok source; media/license manifest; interval restart/timezone/DST; daily cap; offline catch-up; channel/shop switch | YouTube independent and cadence honored; no burst/replay; price/audience/channel correct |
| P2 | Unobservable description/import; crash before/after dispatch; legacy uncertain migration; queue fairness | Fail before send when unproven; retain uncertain outcome after possible send; one unique verified canary receipt |
| P3 | Config 500 regression; heartbeat GET routes; anonymous/wrong token; tenant B reads A; expired/reused QR code; revoked token; shop transfer | Scoped authentication/authorization and accurate applied revision; no credential disclosure |
| P4 | 24h outage; server ack lost; duplicates; same ID different body; reordering; clock skew; 401/429/5xx; restart/full disk; consent revoked; malicious nested metadata | Critical events survive within declared capacity; same event counted once; historical upload never fakes current liveness; sensitive data rejected |
| P5 | Unknown vs explicit Off; no activity vs failure; delayed receipts; count reconciliation; fleet filtering/pagination; role access | Admin totals reconcile to event/receipt queries; no cross-tenant exposure; timestamps and missing coverage shown |
| P6 | Web/native login, PKCE/session/CSRF, QR expiry/race/replay, stolen/revoked paired client, lost worker, multiple shops | No password/token sharing; visible grant scopes; atomic pairing/revocation and actor audit |
| P7 | Secondary client command to home worker; offline expiry; duplicate delivery; Off; target change; cancel race; forged actor; prompt injection | One authorized effect or truthful blocked/uncertain state; command receipt reaches originating client |
| P8 | Cross-device recipe transfer; incompatible app/version; poisoned/untrusted evidence; consent exclusion; bad candidate rollback | Reviewed compatible improvements only; no private memory export; canary regression halts rollout |

Proposed canary targets: zero wrong-recipient/cross-shop actions, zero duplicate external effects during replay tests, zero lost acknowledged critical events; all scoped access tests pass. Within an online five-minute reporting policy, state visible within two heartbeat periods; reconnect backlog drains within ten minutes for the agreed 24h fixture. Report measured latency rather than promise it for all networks. Target admin initial list/detail p95 <2s on 1,000 synthetic devices with pagination; test environment specified.

P4/P5 need 24 hours on both devices. Final P8 needs seven days with device uptime/consent/eligible-work coverage stated. Missing activity is not a pass. Use test groups/owner-designated accounts for external effects and clean up only test artifacts where authorized. Existing operational history must remain intact.

## Additional mandatory cases and denominators

P1 must execute W1-01 through W1-08, W2-01 through W2-09 and C1 cases 1–10 in the linked reliability specifications, with per-device evidence and clearly recorded live versus fixture coverage. Match every OBS row to a current-build triage outcome. Audit unfinished started work, suppressed/review work and per-action PARTIAL outcomes, not only failures. Acceptance reports include eligible opportunities, distinct jobs, attempts, verified effects, bounded retry counts and observed coverage. Preserve non-regression fixtures for currently working flows.

The exact canary/soak requirements and zero-activity rule are in RELIABILITY_GATES.md. Product fixes must be rerun against their trigger before issue closure; test pass alone does not mean deployed or live verified.

## PostgreSQL storage and recovery

P0/P4: record live engine and existing column types; test invalid/oversized JSON and duplicate-ID/different-body rejection, concurrent pairing redemption and concurrent event delivery. Test additive migration/backfill against realistic existing rows, rollback compatibility and tenant-scoped query plans. Benchmark 30-day projected volume and offline replay alongside existing account/config traffic. Isolated restore must preserve binding/revocation/receipt identities and prevent any command dispatch. Record disk/retention and measured RPO/RTO; do not report targets as achieved without evidence. See DATABASE_DECISION.md.

## Fresh-trigger regressions required

Add fixtures/canaries for expired WhatsApp notification origin, exact-number unavailable, same inbound work failing repeatedly, unreadable TPS captions, unavailable OPPO profile identity, group recovery holds with distinct root reasons, long-running upload uncertainty on v70, owner-Off cancellation taxonomy, missing owner timezone, POS partial recovery, and protected order confirmation dedup. Correlate completed work to actual effect receipts, including no-op and duplicate-block cases. Preserve the 31 successful TPS feed-transition path while fixing uncertainty. Final reports are mandatory under BEFORE_LIVESTREAMING.md.
