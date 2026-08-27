#!/usr/bin/env bash
set -euo pipefail

mission_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
required=(README.md MISSION.md TASKS.md PROGRESS.md SCORECARD.md ARCHITECTURE.md SAFETY_POLICY.md TEST_MATRIX.md WALKTHROUGHS.md OPPO_RECONNECT_RUNBOOK.md FIELD_REPORT_TEMPLATE.md EVIDENCE_INDEX.md RISKS.md DECISIONS.md progress.json taskboard.csv)

for file in "${required[@]}"; do
  test -s "$mission_dir/$file" || { echo "Missing or empty mission file: $file"; exit 1; }
done

python3 - "$mission_dir/progress.json" "$mission_dir/taskboard.csv" <<'PY'
import csv, json, sys
json_path, csv_path = sys.argv[1:]
with open(json_path, encoding="utf-8") as handle:
    data = json.load(handle)
tasks = data.get("tasks", [])
allowed = {"not_started", "in_progress", "code_complete", "oppo_blocked", "oppo_verified", "deferred"}
ids = [task["id"] for task in tasks]
if len(ids) != len(set(ids)):
    raise SystemExit("Duplicate task ID in progress.json")
bad = [task for task in tasks if task.get("status") not in allowed]
if bad:
    raise SystemExit(f"Invalid task status: {bad}")
calculated = {status: sum(task["status"] == status for task in tasks) for status in allowed}
counts = data.get("counts", {})
if counts.get("total") != len(tasks):
    raise SystemExit(f"Count mismatch: total={counts.get('total')} tasks={len(tasks)}")
for status in ("not_started", "in_progress", "code_complete", "oppo_blocked", "oppo_verified"):
    if counts.get(status, 0) != calculated[status]:
        raise SystemExit(f"Count mismatch for {status}: declared={counts.get(status)} actual={calculated[status]}")
with open(csv_path, newline="", encoding="utf-8") as handle:
    rows = list(csv.DictReader(handle))
csv_ids = [row["id"] for row in rows]
if ids != csv_ids:
    raise SystemExit("taskboard.csv IDs/order differ from progress.json")
for row, task in zip(rows, tasks):
    if row["status"] != task["status"]:
        raise SystemExit(f"Status differs for {task['id']}")
print(f"Mission progress valid: {len(tasks)} tasks; " + ", ".join(f"{k}={v}" for k, v in calculated.items() if v))
PY
