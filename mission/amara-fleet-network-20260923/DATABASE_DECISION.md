# Database decision: keep PostgreSQL; extend the existing backend

Decision date: 2026-09-23. Planning decision, no database migration or deployment performed.

## Verified baseline

A read-only Laravel runtime check at 21:51:21Z returned default driver `pgsql`, PostgreSQL `16.15 (Ubuntu 16.15-0ubuntu0.24.04.1)`, database-backed queue and database-backed cache. Composer declares Laravel ^11.31. Config also defines a separate Soko SELECT-only MySQL connection; that is not the Cards default database and must not receive migrations. Filesystem now shows approximately 53 GB free of 387 GB (87% used), compared with the earlier full-disk incident. Recovery of free space is not proof that retention, backup or growth controls are solved.

## Choice and rationale

Keep PostgreSQL as the source of truth. Use relational columns for identity, access control, devices, bindings, jobs, commands, receipts and common event filters; use bounded, validated JSONB for flexible event facts. PostgreSQL supports JSONB and indexing, so JSON logging does not require MongoDB. [PostgreSQL 16 JSON types](https://www.postgresql.org/docs/16/datatype-json.html)

MongoDB is capable, but replacing working relational models, migrations, admin queries and transaction logic would add migration risk. Its multi-document transactions also have deployment requirements; a standalone MongoDB instance is not equivalent to the existing transactional backend. Reconsider only for a demonstrated workload need with a benchmark and migration/operating-cost case. [MongoDB production transaction considerations](https://www.mongodb.com/docs/manual/core/transactions-production-consideration/)

Firebase-like functionality consists of several capabilities: database, identity, API authorization, real-time notifications, offline synchronization, file storage and operational tooling. Installing MongoDB alone would not implement those application capabilities. Build the subset Amara needs on the existing Laravel stack. No Firebase or new MongoDB dependency for this mission. Self-hosting still costs server capacity, backups, monitoring and maintenance; no unmeasured savings claim.

## Storage layout

| Data | Target | Rules |
| --- | --- | --- |
| Accounts, shop membership, registry, installation/binding history, pairing grants | Existing PostgreSQL models extended | Foreign keys, explicit authorization, transaction-bound redemption/revocation |
| Work attempts, commands and external-effect receipts | PostgreSQL normalized rows | Stable IDs, unique idempotency constraints, immutable outcome history, atomic state changes |
| Operational event facts | PostgreSQL JSONB plus indexed typed envelope columns | One event per row; schema version and payload limit; no ever-growing per-device JSON array |
| Heartbeats/current device projection | PostgreSQL history plus bounded latest-state projection | Late events cannot overwrite newer liveness; separate event and ingestion time |
| Daily/per-platform summaries | Rebuildable PostgreSQL rollups | Compute from unique receipts/events, never sum repeated heartbeat totals |
| Android offline jobs and outbox | Existing on-device SQLite storage | Durable bounded retry and acknowledgements; cloud database choice does not solve offline sending |
| Videos, screenshots and large diagnostic archives | Protected existing file/object storage behind authorization | Database holds metadata/hash/reference only; consent, retention, quotas and separate backup |
| Queues and cache | Existing database drivers initially | Benchmark contention; separate queues/worker budgets for telemetry and commands; add another service only with measured need |
| Admin live refresh | Authenticated polling first | Optional Laravel Reverb later; socket event is a refresh hint, never a durable command or audit receipt |

Laravel Reverb provides a self-hostable WebSocket option; it is not required for the first reliable fleet dashboard. Check deployment compatibility and private-channel authorization before adopting it. [Laravel 11 Reverb](https://laravel.com/docs/11.x/reverb)

## Implementation and migration rules

P0: record database/table sizes, existing types/indexes, queue/cache contention, backup jobs, last successful restore and disk growth. Do not expose connection credentials. Verify restore procedures in an isolated environment. A running database alone is not a backup strategy.

P3: add/extend identity and binding constraints on the Cards connection only. Audit existing nullable references and deletion rules so removing a registry entry cannot silently destroy required audit provenance. Prefer revocation/tombstones with explicit privacy retention policy. Use additive migrations and old-client compatibility.

P4: extend AgentLog or migrate to a canonical event table with a compatibility adapter, not competing totals. Typed fields: tenant, device, installation, event ID, job/attempt/effect ID, type/status/reason, occurred_at, received_at, schema/build versions. JSONB holds allowlisted variable facts. Inspect existing JSON/text columns before conversion; backfill in batches with row counts and validation. Plan lock duration and rollback on production-sized fixtures.

Create unique `(device_id, installation_id, event_id)` constraints with the actual registry reference naming. Start B-tree indexes for observed access patterns such as tenant/device/time and job/attempt. Add targeted JSONB indexes only for measured queries. Avoid indexing every diagnostic field. Store a defined canonical payload digest for same-ID/different-body detection; JSONB reserialization must not accidentally change the hashing contract.

Dashboard projections/rollups update from committed events, with idempotent replay/rebuild. Queue retries are at-least-once; database dedupe does not guarantee exactly-once external posting. Keep the existing uncertain-effect reconciliation rule. Partitioning and analytics stores are later optimizations: benchmark first, and preserve dedup uniqueness if changing partition keys.

## Cost, capacity and recovery gates

Measure events/device/day after coalescing, average stored bytes including indexes, retained days, WAL, backups, media and peak reconnect bursts. Example only: 1,000 devices × 500 events/day × 2 KB × 30 days is about 30 GB of raw event payload, before database/index/WAL/backup overhead. This is not an estimate of current usage. Budget snapshots and media separately; avoid uploading tens of thousands of unchanged identity observations per phone daily.

Before expanding telemetry beyond canaries, run representative ingestion/reconnect and admin queries against a projected 30-day dataset on isolated infrastructure. Record p95 latency, throughput, queue lag and disk growth; verify existing account/config operations do not regress. Add disk-capacity and backup-failure alerts and an accountable operator. Apply retention in bounded batches; do not let diagnostic cleanup remove unresolved effect evidence.

Proposed recovery targets for owner/operator confirmation: at most 15 minutes of server-side data loss (RPO) and service restoration within four hours (RTO). Document measured feasibility and backup cost before claiming these targets. Use encrypted backups off the application host and restore tests; add base backups plus WAL archiving for point-in-time recovery if required by the target. Replication is not a substitute for backups. [PostgreSQL recovery documentation](https://www.postgresql.org/docs/16/continuous-archiving.html)

Test restore into an isolated instance: accounts/device bindings/receipts reconcile; revoked credentials remain revoked; no queued command or message auto-executes during restore testing. After a real restore, reconcile device receipts and dedupe state before resuming external commands; expired or uncertain commands stay held. Record actual RPO/RTO and unresolved gaps.

## Readiness assessment

Yes: the existing Android queues/receipts, Laravel backend, PostgreSQL, pairing service and Filament page are a sound starting point for implementation. They reduce the need for replacements. No: this does not certify production fleet readiness. Current blockers are observability gaps, current-build reliability verification, authenticated integration, durable sync and proven operational recovery. Start with P0/P1 and the reporting foundation; defer broad remote control and learning until their evidence gates pass.
