# Repository audit — 2026-09-27

Baseline: `4099881` on `main`. Requested scope: audit the repository before
committing and pushing all pending changes. Verdict: the pending work can be
preserved in Git, but the repository does **not** pass release readiness.

## Scope and method

Inventoried all 1,035 tracked files and the incoming PHP staging files. The
inventory includes 435 Kotlin files (~62,618 lines), 41 Dart files (~13,026 lines),
native C++, Android resources, CI, scripts, tests, mission documentation, and
committed evidence/binaries. Applied repository-wide source/secret-pattern scans
and executable checks, with manual review concentrated on authentication,
credentials, approvals, side-effect transactions, remote controls, storage,
backup, service lifecycle, UI bridge, and every incoming staging file.

This is a risk-focused repository-wide audit, not a claim that every line or
binary was manually verified. Historical Git objects and binary assets were not
secret-scanned. No device test, release installation, external provider call,
production migration, or production database test was performed.

## Findings still open

1. **High — advertised Android compatibility fails static verification.**
   `app/build.gradle` declares `minSdk 24`, but fresh `:app:lintDebug` reports
   **286 errors and 196 warnings**: 283 `NewApi` errors and three `WrongConstant`
   errors. Examples include notification-channel use in `AgentService.kt`,
   `java.time` use in `AgentRuntime.kt` without core-library desugaring, and
   accessibility calls in `AccessibilityActions.kt`. These can fail on supported
   older OS versions; a successful test on the Android 11 Oppo would not prove
   API 24 compatibility. Decide the actual minimum OS, then add guards/desugaring
   or raise the declared minimum. Review flag handling separately, particularly
   `ShortsAudioNormalizer.kt:114`, which forwards extractor flags to codec buffer
   metadata. Lint diagnostics are findings to triage, not 286 independently
   reproduced runtime crashes.

2. **High — remote system lockout does not guard every execution path.**
   `core/work/AmaraWorkLoop.kt:111` and `:331` check the remote controls between
   work items. `core/AutonomyController.kt:27` checks only owner power, and
   `core/AgentRuntime.kt:84` wires the side-effect runner's permission callback
   only to `OwnerPower.isOn()`. A direct owner command or an already-running item
   can therefore reach the transaction boundary without checking remote lockout.
   Add an independent remote-control guard at dispatch and test direct commands,
   scheduled work, and a lockout arriving during preflight. Keep configuration
   fetch available so remote unlock remains possible.

3. **High — concurrent memory backup can overwrite remote command state.**
   `amara-intel-staging/app/Http/Controllers/Api/AgentController.php:329-333`
   reads and replaces the complete configuration JSON without joining the
   device-row locking protocol used by `AmaraRemoteCommands::issue`. A backup
   reading old configuration before an admin command can subsequently write it
   back, losing the revision/lockout. Serialize all configuration writers using
   the same device lock, then reread under that lock; add a concurrency test.
   The sequential SQLite feature suite does not cover this race.

4. **Medium — fleet UI presents desired lockout as observed system state.**
   `AmaraDeviceIntelligence.php:60` derives `systemLockout` from configuration;
   `public/js/amara-fleet-graph.js` renders it as “System access: LOCKED”. An
   offline device that has never fetched the command can appear locked.
   Expose requested revision, acknowledged revision and observation freshness,
   and label pending configuration separately from observed enforcement.

5. **Medium — boot recovery suppresses subsequent clock-change scheduling.**
   `receivers/BootReceiver.kt:34` sets a process-lifetime `scheduleEnqueued`
   flag, reset only on failure or by tests. After one successful broadcast,
   timezone/time-change broadcasts in the same process no longer call
   `scheduleAll`, contrary to the receiver's stated contract. Use a bounded
   in-flight guard or allow clock-change rescheduling explicitly; test multiple
   broadcasts in one process.

6. **Medium — read endpoint bypasses shop-binding history.**
   `AgentController.php:360` changes observed seller/shop through `sokoData`
   without the revision/history transaction used by `observeShop`. Reading a
   different signed shop can leave the fleet's current shop inconsistent with
   its binding revision and event attribution. Consolidate both paths through
   the binding service or remove the read endpoint's identity mutation.

7. **Medium — CI does not cover the failing checks or staged backend.**
   `.github/workflows/ci.yml` runs Android unit tests but not Android lint. The
   PHP directory lacks a standalone Composer/bootstrap/routes environment and
   has no CI integration. The full traceability job explicitly ignores checker
   failure. Consequently green CI does not establish Android compatibility,
   backend integration, or complete mission evidence. Add explicit gates once
   their existing failures and backend dependency contract are addressed.

## Corrections made before push

- Traceability negative-fixture baseline XML used filenames that could not
  match `*.ClassName.xml` and overwrote earlier methods in a class. The initial
  harness rejected only 20/23 cases for the intended reason. Generate qualified
  filenames with all cited methods, preserve temporary-main overrides, and cache
  source/token indexes to avoid repeated whole-tree reads. Both completed reruns
  rejected all 23 intended defects. Synthetic evidence stays in temporary
  fixture directories and is not production test evidence.
- Added a new forward migration for nullable `agent_heartbeats.system_lockout`;
  the incoming controller/model used it but neither incoming migration created
  it. Updated the feature-test migration list.
- Require both reported control states, as well as the exact revision, before
  acknowledging a remote command. Previously a revision alone was sufficient.
  Added a regression test covering missing state, mismatched state, wrong
  revision, and successful acknowledgement; updated the UI explanation.
- Documented the staging directory's external dependencies and validation scope.

## Validation

| Check | Result |
| --- | --- |
| Android unit tests | Gradle `:app:testDebugUnitTest` UP-TO-DATE; existing results: 197 suites, 1,193 tests, zero failures/errors/skips. Tests were not forced to rerun. |
| Fresh Android lint | FAIL: 286 errors, 196 warnings; combined Gradle command exits nonzero at `:app:lintDebug`. |
| Flutter analyze | PASS: no issues. |
| Flutter tests | PASS: 44 tests. |
| Dart formatting | PASS: 41 files, zero changes. |
| Side-effect boundary | PASS: 15 annotated primitives, 236 production Kotlin files. |
| Evidence redaction gate | PASS. |
| Traceability negative fixtures | PASS: 23/23 intended rejections after correction. |
| Full traceability audit | FAIL: 12 rows flag test-only method references (`conversationHistory`, `pollSoko`), plus a stale release APK. These heuristic findings need review; no production/device completion is claimed. |
| PHP integration | PASS: 21 tests, 117 assertions, isolated Cards copy with staging overlaid and SQLite in memory. |
| PHP and JavaScript syntax | PASS: PHP files linted; fleet JavaScript checked with `node --check`. |
| Python collector | PASS: four unit tests. |
| Secret-pattern scan | No matches for private-key headers, common GitHub/Groq/OpenAI token prefixes, or AWS access-key IDs in tracked/incoming readable text. Not a credential-leak guarantee. |

The backend harness used the host's installed Laravel/Filament dependencies,
overrode application/test autoloading to a temporary copy, used a temporary
bootstrap/cache/storage, and did not copy production `.env` or cached config.
It does not prove PostgreSQL locking, real fleet traffic, or standalone checkout
reproducibility. Android/Flutter tests do not exercise actual Accessibility,
notification delivery, third-party app layouts, or unattended device operation.

Review also confirmed scoped FileProvider paths, disabled Android backup,
permission-protected exported control services, encrypted credential storage,
transaction approval and uncertainty checks, backend token checks, and escaped
fleet graph text. These controls reduce risk but do not negate the open findings.
