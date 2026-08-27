#!/usr/bin/env bash
# gate_run.sh — capture a single gate's evidence
# usage: gate_run.sh <gate_id> "instruction text" [extra_sleep_seconds]
set -uo pipefail
EVID=mission/amara-10-10/evidence-device-AMARA-REL-20260826-C
GATE=$1
INSTR=$2
EXTRA_SLEEP=${3:-12}
mkdir -p "$EVID/$GATE"

DEV="adb -s 192.168.1.65:41001"

# 1) start a fresh logcat capture
$DEV logcat -c
START_TS=$(date -u +%FT%TZ)

# 2) before-target-surface
$DEV exec-out screencap -p > "$EVID/$GATE/before_target_surface.png"
$DEV shell uiautomator dump /sdcard/before.xml >/dev/null 2>&1
$DEV pull /sdcard/before.xml "$EVID/$GATE/before_target_surface.xml" >/dev/null 2>&1
$DEV shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp" > "$EVID/$GATE/before_focus.txt" 2>&1

# 3) type instruction
$DEV shell input tap 350 1359
sleep 0.5
ESC_INSTR=$(echo "$INSTR" | sed 's/ /%s/g')
$DEV shell input text "$ESC_INSTR"
sleep 1
$DEV shell uiautomator dump /sdcard/x.xml >/dev/null 2>&1
$DEV pull /sdcard/x.xml /tmp/x.xml >/dev/null 2>&1
SEND_BOUNDS=$(python3 -c "import re; xml=open('/tmp/x.xml').read(); m=re.search(r'content-desc=\"Send\"[^>]*bounds=\"(\[[^\"]+\])\"',xml); print(m.group(1) if m else 'NOTFOUND')")
if [[ "$SEND_BOUNDS" =~ ^\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]$ ]]; then
  CX=$(( (${BASH_REMATCH[1]} + ${BASH_REMATCH[3]}) / 2 ))
  CY=$(( (${BASH_REMATCH[2]} + ${BASH_REMATCH[4]}) / 2 ))
  $DEV shell input tap $CX $CY
else
  $DEV shell input keyevent KEYCODE_ENTER
fi

# 4) wait
sleep $EXTRA_SLEEP

# 5) after-target-surface
$DEV exec-out screencap -p > "$EVID/$GATE/after_target_surface.png"
$DEV shell uiautomator dump /sdcard/after.xml >/dev/null 2>&1
$DEV pull /sdcard/after.xml "$EVID/$GATE/after_target_surface.xml" >/dev/null 2>&1
$DEV shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp" > "$EVID/$GATE/after_focus.txt" 2>&1
$DEV shell dumpsys SurfaceFlinger --window-manager > "$EVID/$GATE/window_manager_dump.txt" 2>&1

# 6) logcat slice
$DEV logcat -d -t 800 2>&1 | grep -E "sanaa|Agent|Amara|Groq|Tasker|Soko|co.sanaa" | head -300 > "$EVID/$GATE/logcat_slice.txt"

# 7) result file
END_TS=$(date -u +%FT%TZ)
RESULT="$EVID/$GATE/result.json"
python3 - "$GATE" "$START_TS" "$END_TS" "$INSTR" "$SEND_BOUNDS" > "$RESULT" <<'PYEOF'
import json, re, sys
gate, start, end, instr, sb = sys.argv[1:6]
xml = open(f"/var/www/cards.sanaa.ug/Sanaa-Agent/mission/amara-10-10/evidence-device-AMARA-REL-20260826-C/{gate}/after_target_surface.xml").read()
descs = re.findall(r'content-desc="([^"]+)"', xml)
texts = re.findall(r'text="([^"]+)"', xml)
out = {"gate_id": gate, "start_ts": start, "end_ts": end,
       "instruction": instr, "send_bounds": sb,
       "content_descs": descs, "texts": texts}
print(json.dumps(out, indent=2))
PYEOF

echo "[$GATE] done. Send bounds: $SEND_BOUNDS"
ls -la "$EVID/$GATE/"
