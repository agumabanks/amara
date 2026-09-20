#!/usr/bin/env bash
set -euo pipefail
serial="${1:-7aef1a4c}"
out="${2:-artifacts/amara-24h}"
mkdir -p "$out"
# Compare timestamped completion records; an existing export file is not freshness proof.
completion() {
  /root/bin/adb -s "$serial" logcat -d -v epoch -s SanaaAgentPOC:I '*:S' 2>/dev/null |
    rg 'Observation export=' | tail -n 1 || true
}
previous=$(completion)
/root/bin/adb -s "$serial" shell am broadcast -n co.sanaa.agent/.receivers.ProofOfConceptReceiver -a co.sanaa.agent.action.EXPORT_OBSERVATION
for attempt in $(seq 1 20); do
  current=$(completion)
  if [[ -n "$current" && "$current" != "$previous" ]]; then
    if [[ "$current" == *"Observation export=unavailable"* ]]; then
      echo 'Amara has no observation to export' >&2
      exit 1
    fi
    /root/bin/adb -s "$serial" pull /sdcard/Android/data/co.sanaa.agent/files/evaluation/ "$out/"
    python3 scripts/observation/summarize.py "$out/evaluation"
    exit 0
  fi
  sleep 1
done
echo 'Fresh observation export was not confirmed' >&2
exit 1
