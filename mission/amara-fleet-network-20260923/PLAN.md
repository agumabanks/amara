# Ordered implementation plan

Execute P0 through P8 in order. Each milestone has a narrow deliverable and evidence gate. A phase blocked on one item can prepare later designs, but cannot claim dependent rollout complete. Role owner for each phase: implementing soldier agent, with release acceptance by the project owner through the existing workflow.

| Phase | Deliverable / work | Exit milestone and evidence |
| --- | --- | --- |
| P0 | Establish reproducible baseline, exact Shorts Notices, HTTP 500 cause, host storage incident, deployment identities, configured domains and consent. Audit source and running backend routes. | M0: redacted error-to-request correlation, notice detail or explicit blocked item, backed-up state, per-device installed build/app versions, coverage report and approved canonical host decision. |
| P1 | Polish work isolation and local health. Bound session recovery with dependency cooldown; scope circuit breakers by target/channel/dependency. Preserve reply drafts and long text across restart. Add large battery/offline health panel using existing Doctor and alert records. | M1: failure-injected group/long-reply cases do not block unrelated eligible work; no wrong chat or duplicate send; battery/offline warning clears correctly; owner notification path verified. |
| P2 | Independent YouTube content and cadence. Reuse catalogue/rendering, add platform variant and rights manifest, durable YouTube opportunity queue, migration, exact channel/details/dispatch verification. | M2: same product gives distinct useful YouTube content; restart-safe interval and cap; no TikTok export prerequisite; one approved canary verified on each eligible device; legacy uncertain rows never replay. |
| P3 | Repair and strengthen backend binding, ingestion authorization and config sync. Extend AgentDevice/Pairing, resolve heartbeat route mismatch, bind registry IDs to verified shop and account membership. | M3: both phones fetch scoped config and authenticate status/heartbeat; cross-device/tenant token misuse, expired/replayed pairing, revocation and shop mismatch tests denied. HTTP 500 cause fixed with regression evidence. |
| P4 | Durable JSON telemetry and availability. Local bounded outbox; idempotent batch ingestion; reconnect replay; lifecycle/health/work receipts; server rollups and retention; repair missing heartbeat producer. | M4: all enabled work kinds traced to verified device and shop; restart/retry/reorder/outage tests reconcile without loss or double counting; privacy gates hold; 24-hour reporting canary. |
| P5 | Extend Filament Amara Devices into fleet graph + list + detail timeline; power/network/last-seen/config/queue health; daily summaries and filters. | M5: admin clicks shop → device → job → attempt → receipt; correct unknown/off distinction; tenant access tests; paginated 1,000-device fixture and response-time evidence. |
| P6 | Shared Sanaa account sign-in and QR pairing across web/mobile/worker. Reuse Passport and memberships after protocol audit; separate account, client session and worker credential. | M6: browser and mobile pair a worker, display requested scopes/shop, revoke it, and cannot reuse QR/session or cross tenant. Validate refresh/logout/lost-device flows. |
| P7 | Sanaa Chat remote commands over authenticated broker to existing WorkQueue. Allowlisted typed commands, TTL, explicit scope and actor, durable receipt, progress/cancellation, reconnect delivery. | M7: secondary paired device submits permitted command; home worker executes once while owner On, returns receipt; expired/revoked/Off/mismatched commands rejected or visibly held. |
| P8 | Cross-device learning and phased rollout. Aggregate compatible failure patterns, de-identify fixtures, version signed recipes/config/prompts, replay evals, canary then expand, rollback. | M8: one OPPO/TPS finding yields a validated improvement on another compatible device; no private memory transfer; regression/rollback demo and seven-day fleet soak report. |

## P1 details

Use typed failure evidence rather than matching human error strings to decide safe replay. Only a proven pre-dispatch failure is retryable automatically. An uncertain or possibly sent action stays review/reconciliation-bound while other tasks continue. Persist conversation identity, source message identity, draft version/hash, chunk order if needed, and effect IDs. Long replies require complete input capture and observed delivery; prefix matching of collapsed text cannot prove the entire origin message. Cancel/time-bound network calls at the actual HTTP operation, not only the surrounding coroutine. Test process death at each transition.

Group jobs need per-group progress, not restart-from-first-member retries. Retain selected media, target group ID, caption hash and dispatch evidence. A missing Terminal session should cool down catalogue-dependent work, not spin hundreds of times, and must not block independent inbound replies. Failing WhatsApp alerts must not recursively generate more WhatsApp alerts. Never mark all failed jobs successful to clear a queue.

Large alert: prominent readable panel in Amara, persistent notification where permitted, actionable “Connect charger” / “Restore internet”; use existing permission-supported presentation when backgrounded. Do not assume Android permits arbitrary full-screen takeover. Pause affected work at the existing battery policy, show charging and actual pause state, add hysteresis before resuming. Separate no internet, captive/limited network, backend down, authentication expired, and owner Off. Show queued-work count and last useful action. An offline phone cannot immediately send WhatsApp; queue deduplicated alerts and summarize after recovery.

## P2 details

YouTube profile: product benefit hook → concrete usage/demo or narrated explanation from verified facts → concise brand/contact direction suited to the surface. Reuse product assets and pricing truth, not identical template output. Product-only catalogue images can use motion/text/voice with no fabricated claims; no fake testimonials. Variant manifest includes product revision, storyboard/template version, language, media digest, asset origins and audio rights/attribution. Original/no-music fallback beats relying on TikTok soundtrack clearance. Preview before the initial canary.

Keep enable flag, channel, interval, timezone, quiet hours, daily cap, visibility and audience settings independent. Proposal: interval is minimum between dispatched publishing opportunities (uncertain dispatch consumes the opportunity); only one pending opportunity per channel; no burst catch-up after offline/Off; honor quiet hours and DST. Pre-dispatch failure gets bounded retry/backoff, not a new publication identity. Show next due time and reason deferred. Validate this semantic with existing UI before migration, preserve chosen interval.

Introduce source_kind `catalogue_variant` alongside legacy `tiktok_export`. Migrate schema without rewriting old receipt/source identifiers. Catalogue change invalidates only uncommitted stale media. Shop/channel change quarantines mismatched queued jobs. Fair FIFO/eligible ordering avoids newest-only starvation. Check actual title/description and account before dispatch; verify unique media/title/time evidence, retain unknown outcome if ambiguous. Backend API upload can be separately assessed later; do not change publishing transport just to bypass current guards.

## Cross-phase rollout

Use feature flags defaulting off for new remote control/learning capabilities. Expand database schema before rolling out clients; keep old log/config contracts through a migration window. OPPO canary first for fixes tied to its evidence, then TPS with its own proof; never infer compatibility from OPPO alone. Record app/package versions, build hash, device cohort and rollback route. Pilot settings are restored after tests. Production messaging/posting tests require the existing owner-approved destination/content scope; otherwise use prepare-only and state the missing live gate.

## P1 workstream order and expanded coverage

1. Q1: characterize current successes and establish ledger-safe recovery/fairness fixtures.
2. W1: validate and polish inbound replies, long drafts and follow-ups using WHATSAPP_ACCEPTANCE.md.
3. W2: validate group scheduling, shop dependency, media send and per-group recovery.
4. C1: repair existing community engagement paths using COMMUNITY_GROWTH.md, including partial outcomes and duplicate-safe restart.
5. H1: finish actionable battery/network/manager alerts and prove recovery.

Each track has its own evidence and progress entry. Investigate current TikTok publishing/Stories, Soko audit/inventory and Jiji failures from the issue register; preserve verified working behavior and classify expected policy holds. Match each fix to its original trigger and comparable successful trace. P1's parent gate requires all five tracks; JSON event and admin phases must expose these per-action outcomes rather than collapsing them into one success boolean.

P0 is an evidence/triage gate, not a promise to resolve all external account issues. If exact YouTube notice access is missing, record its explicit blocker and isolate that dependent task; independent queue repair may proceed once P0's baseline audit is verified. The YouTube notice resolution row remains open and blocks full mission completion. Do not make a missing notice screenshot stop unrelated WhatsApp repairs.

## Storage and operational delivery requirements

Follow DATABASE_DECISION.md. P0 records engine/schema/capacity and backup-restore readiness. P3 extends PostgreSQL identity constraints without touching Soko read-only storage. P4 delivers canonical JSONB events with typed indexes, bounded retention and benchmarked queue/ingestion load. P4 fleet expansion requires an isolated backup restore, receipt reconciliation and measured recovery targets; P5 proves dashboard queries against projected retained volume. Do not introduce MongoDB, Firebase or another database as a shortcut for missing sync/auth logic.
