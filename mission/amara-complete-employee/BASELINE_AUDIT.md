# BASELINE AUDIT — corrective re-audit session 2026-08-23T12:31Z

Baseline established by fresh execution only. Existing reports treated as untrusted.

## Environment facts (observed)
- Git metadata: **ABSENT** (`git rev-parse` → "not a git repository"). Changed-file ledger with
  before/after hashes is maintained in EXECUTION_LOG.md instead.
- Device: `adb devices -l` → "List of devices attached" (empty). **No device evidence possible.**
- Java 17.0.19; Gradle 8.12; AGP 8.9.1; Kotlin 2.1.0.
- No AGENTS.md files found in repo root or parents.

## Fresh test totals (executed this session)
- Command: `./gradlew :app:testDebugUnitTest --console=plain --rerun-tasks` — exit 0,
  2026-08-23T12:31:52Z → 12:32:57Z, 66 tasks executed (not cached).
- Parsed from fresh XML: **186 tests, 0 failures, 0 errors, 0 skipped, 29 suites.**
- Flutter gates and release gate are re-run at final validation; previous exit codes are discarded.

## Claim classification of predecessor state

| # | Claim / defect | Verdict | Basis |
|---|---|---|---|
| 1 | check_traceability.sh validates evidence existence/provenance | **CONTRADICTED** | `grep -c` for evidence/file/XML validation = 0 matches in script |
| 2 | CE-C-ARTIFACTS-01 honestly states PDF/PPTX gap | **PARTIALLY SUBSTANTIATED** | row is `locally_verified` while its own note says PDF/PPTX `not_started` — aggregate-state violation |
| 3 | WorkflowSimulator graph order validated | **CONTRADICTED** | `GRAPH_ORDER` map + `planKinds` computed then never used |
| 4 | CSV output correctly escaped | **CONTRADICTED** | header line unescaped (`appendLine(spec.csvColumns.joinToString(","))`) while data cells escape quotes |
| 5 | ConnectorSpec.timeoutMs enforced | **CONTRADICTED** | declared + constructor-validated only; no call-time deadline mechanism |
| 6 | Artifact history durable | **CONTRADICTED** | `revisions = mutableListOf` in-memory only |
| 7 | Tone review present | **CONTRADICTED** | no tone dimension exists in ArtifactRubric |
| 8 | Exactly-once via production boundary for workflows | **UNVERIFIABLE** | simulation uses an in-test `ConcurrentHashMap.newKeySet`, not production SQLite occurrence keys |
| 9 | Crash-after-effect-before-checkpoint covered | **CONTRADICTED** | simulation checkpoints synchronously after act; no window test exists |
| 10 | 186-test count | **SUBSTANTIATED** | reproduced by fresh rerun above |
| 11 | Boundary checker catches bypasses | **PARTIALLY SUBSTANTIATED** | it caught real violations previously, but is regex-only; structural enforcement absent |
| 12 | Flutter consumes catalog labels from production data | **CONTRADICTED** | Android exposes `capabilityCatalog`; no Dart-side consumption/display exists |
| 13 | Schema strings enforce inputs | **CONTRADICTED** | inputSchema/outputSchema are descriptive strings, never parsed or checked |
| 14 | Runner timeout/watchdog | **CONTRADICTED** | spec.timeoutMs never consulted during execute() |
| 15 | Static-check command paths in docs | **CONTRADICTED** | prior log cites `scripts/check_side_effect_boundary.sh` without the required `mission/amara-complete-employee/` prefix |

## Blocked-by-environment (unchanged, truthful)
- Oppo device gates (O-07 exact Soko edit, live Accessibility hierarchies, WhatsApp/TikTok/Soko live
  verification, reboot/timezone drills, soak, supervised trial) — blocked on hardware/time.
- Real provider integrations — blocked on owner credentials + consent.
- Seven-day soak and 30-day trial cannot be compressed; remain not started.

---

# RE-AUDIT ADDENDUM — 2026-08-23T23:05Z → 23:20Z (independent verification session)

Predecessor claims re-tested from scratch. Two predecessor claims were **CONTRADICTED by fresh
execution** and repaired this session:

| # | Claim under test | Verdict | Basis |
|---|---|---|---|
| 1 | `BoundaryCheckEnforcementTest` passes | **CONTRADICTED then FIXED** | Fresh `--rerun-tasks` run failed `syntheticViolationIsRejectedWithFileAndLine`: the checker's annotation-drift guard fired on isolated fixture trees (no declaration site present), exiting before reporting the planted violation. Checker fixed to derive annotations only when the boundary file exists in the scanned root; fixture mode now rejects the violation with file:line; real tree stays clean (exit 0, 66 files). |
| 2 | Connector deadline "hanging provider becomes TimedOut not hang" | **CONTRADICTED then FIXED** | `RateLimitedConnector.callWithDeadline` checked elapsed time only AFTER `perform()` returned — a genuinely hung provider blocked forever. Replaced with off-thread execution + timed `Future.get`; abandoned call cancelled/interrupted. New negative test `hangingProviderIsInterruptedAtTheDeadlineInsteadOfBlockingForever` proves an infinitely-blocked provider returns TimedOut near the deadline (<5s wall). |
| 3 | CE-C-HISTORY-01 evidence citation | **CONTRADICTED then FIXED** | Evidence cell named nonexistent `InMemoryArtifactHistoryDurableTest.xml`; corrected to the real `DurableArtifactHistoryTest.xml`. |
| 4 | Artifact engine: PDF/PPTX/CSV/tone/spreadsheet rows | SUBSTANTIATED | Source + tests read directly; PDF xref/structure assertions, PPTX OPC parts parsed as XML, RFC-4180 header+cell quoting, tone dimension, totals/types/anomalies all present and passing fresh. |
| 5 | Durable workflows exactly-once on production boundary | SUBSTANTIATED (JVM scope) | `DurableWorkflowPersistenceTest` uses Robolectric production SQLite (`AmaraMemory`), real ledger sweep, CAS lease, optimistic checkpoints, 8-worker race → exactly 1 execution; crash-window test parks run AWAITING_DECISION, never replays. External effect remains stubbed — device-gated. |
| 6 | Traceability checker rejects fabricated evidence | SUBSTANTIATED | Negative-fixture mode rejects missing impl/class/method/evidence; stale-evidence rule correctly flagged every suite after a filtered test run deleted sibling XMLs — exactly the designed behavior. |
| 7 | Flutter consumes production catalog labels | SUBSTANTIATED | `agent_channel.dart` invokes `capabilityCatalog`/`activeCommitments` over the production MethodChannel; Android handler at MainActivity.kt:216; widget test renders catalog labels. |
| 8 | Schema parsing + watchdog | SUBSTANTIATED (deadline-check scope) | `TypedSchema.parse` + runner validation tests pass; watchdog enforces capability deadline with late proof kept unproven (terminal UNCERTAIN). Honest scope note: deadline is checked around act/verify against an injectable clock, not mid-gesture interruption — UI automation cannot be safely aborted mid-action. |
| 9 | Fresh totals | **239 tests / 37 suites / 0 failures / 0 errors / 0 skipped** (was 186/29 before Phase B–F additions). |

Environment unchanged: no Git metadata; `adb devices -l` empty (2026-08-23T23:18Z); device,
provider, soak, and supervised-production gates remain blocked/not started.
