#!/usr/bin/env bash
# gate_capture.sh — fast evidence capture for a gate (no chat input)
# usage: gate_capture.sh <gate_id> <pre_action_sleep> [label]
# Captures: before/after screenshots, UI dumps, window manager dump, logcat slice
set -uo pipefail
EVID=mission/amara-10-10/evidence-device-AMARA-REL-20260826-C
GATE=$1
SLEEP_PRE=${2:-0}
LABEL=${3:-capture}
mkdir -p "$EVID/$GATE"
DEV="adb -s 192.168.1.65:41001"
sleep $SLEEP_PRE
$DEV exec-out screencap -p > "$EVID/$GATE/${LABEL}_target_surface.png"
$DEV shell uiautomator dump /sdcard/c.xml >/dev/null 2>&1
$DEV pull /sdcard/c.xml "$EVID/$GATE/${LABEL}_target_surface.xml" >/dev/null 2>&1
$DEV shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp" > "$EVID/$GATE/${LABEL}_focus.txt"
$DEV shell dumpsys SurfaceFlinger --window-manager > "$EVID/$GATE/${LABEL}_window_manager_dump.txt"
$DEV logcat -d -t 400 2>&1 | grep -E "sanaa|Agent|Amara|Groq|Tasker|Soko|co.sanaa" | head -200 > "$EVID/$GATE/${LABEL}_logcat_slice.txt"
python3 - "$GATE" "$LABEL" > "$EVID/$GATE/${LABEL}_result.json" <<'PYEOF'
import json, re, sys
gd, lab = sys.argv[1:3]
xml = open(f"/var/www/cards.sanaa.ug/Sanaa-Agent/mission/amara-10-10/evidence-device-AMARA-REL-20260826-C/{gd}/{lab}_target_surface.xml").read()
descs = re.findall(r'content-desc="([^"]+)"', xml)
out = {"gate_id": gd, "label": lab, "content_descs": descs}
print(json.dumps(out, indent=2))
PYEOF
echo "[$GATE/$LABEL] done"
