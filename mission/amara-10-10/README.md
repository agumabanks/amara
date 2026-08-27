# Amara 10/10 Phone Manager Mission

This folder is the single source of truth for finishing Amara as a dependable phone-based employee. Start with [MISSION.md](MISSION.md), use [TASKS.md](TASKS.md) for execution order, and update [progress.json](progress.json) whenever a task changes state.

## Status vocabulary

- `not_started`: no implementation exists.
- `in_progress`: implementation is actively incomplete.
- `code_complete`: implementation and automated tests are complete, but no Oppo test has passed.
- `oppo_blocked`: code is ready but a named device condition prevents the acceptance test.
- `oppo_verified`: the required real-device test passed with evidence.
- `deferred`: intentionally outside the current release, with a recorded reason.

No task is “done” merely because it compiled. Device-facing tasks finish only at `oppo_verified`.

## Mission files

| File | Purpose |
|---|---|
| [MISSION.md](MISSION.md) | Objective, scope, sequence, and completion definition |
| [TASKS.md](TASKS.md) | Ordered implementation ledger and acceptance gates |
| [PROGRESS.md](PROGRESS.md) | Human-readable progress dashboard |
| [progress.json](progress.json) | Machine-readable mission state |
| [SCORECARD.md](SCORECARD.md) | 10/10 release criteria |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Target architecture and state flow |
| [SAFETY_POLICY.md](SAFETY_POLICY.md) | Autonomy, approval, privacy, and side-effect rules |
| [TEST_MATRIX.md](TEST_MATRIX.md) | Local and Oppo acceptance matrix |
| [WALKTHROUGHS.md](WALKTHROUGHS.md) | Operator and test walkthroughs |
| [OPPO_RECONNECT_RUNBOOK.md](OPPO_RECONNECT_RUNBOOK.md) | Exact reconnection and device-test sequence |
| [FIELD_REPORT_TEMPLATE.md](FIELD_REPORT_TEMPLATE.md) | Per-test field report template |
| [EVIDENCE_INDEX.md](EVIDENCE_INDEX.md) | Evidence naming and current artifacts |
| [RISKS.md](RISKS.md) | Known risks, triggers, mitigations, and owners |
| [DECISIONS.md](DECISIONS.md) | Durable architectural and product decisions |
| [taskboard.csv](taskboard.csv) | Spreadsheet-friendly task board |
| [scripts/check_progress.sh](scripts/check_progress.sh) | Mission consistency checker |
| [scripts/run_local_gates.sh](scripts/run_local_gates.sh) | Reproducible Android, Flutter, mission, and release gates |

Run the checker from the Sanaa-Agent root:

```bash
bash mission/amara-10-10/scripts/check_progress.sh
```

Run every device-independent release gate:

```bash
bash mission/amara-10-10/scripts/run_local_gates.sh
```
