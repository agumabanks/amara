#!/usr/bin/env bash
set -euo pipefail

PACKAGE="co.sanaa.agent"
ACCESSIBILITY_COMPONENT="co.sanaa.agent/co.sanaa.agent.services.AccessibilityAgentService"
NOTIFICATION_COMPONENT="co.sanaa.agent/co.sanaa.agent.services.AgentNotificationListenerService"
SERIAL=""
APK=""
WAIT_SECONDS=180

usage() {
    echo "Usage: $0 --serial SERIAL [--apk /absolute/path.apk] [--wait-seconds N]" >&2
    exit 2
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --serial) SERIAL="${2:-}"; shift 2 ;;
        --apk) APK="${2:-}"; shift 2 ;;
        --wait-seconds) WAIT_SECONDS="${2:-}"; shift 2 ;;
        *) usage ;;
    esac
done

[[ -n "$SERIAL" ]] || usage
[[ "$SERIAL" =~ ^[A-Za-z0-9._:-]+$ ]] || { echo "error=invalid_device_serial" >&2; exit 2; }
[[ "$WAIT_SECONDS" =~ ^[0-9]+$ ]] || { echo "error=invalid_wait_duration" >&2; exit 2; }

ADB=(adb -s "$SERIAL")
timeout 10s "${ADB[@]}" get-state | grep -qx device || { echo "error=device_not_ready" >&2; exit 1; }

prop() {
    timeout 8s "${ADB[@]}" shell getprop "$1" 2>/dev/null | tr -d '\r'
}

echo "device_serial=$SERIAL"
echo "device_model=$(prop ro.product.model)"
echo "android_version=$(prop ro.build.version.release)"

LOCAL_SHA=""
if [[ -n "$APK" ]]; then
    [[ "$APK" = /* && -f "$APK" ]] || { echo "error=apk_must_be_existing_absolute_path" >&2; exit 2; }
    LOCAL_SHA=$(sha256sum "$APK" | awk '{print $1}')
    echo "apk_path=$APK"
    echo "local_apk_sha256=$LOCAL_SHA"
    "${ADB[@]}" install -r "$APK"
fi

timeout 15s "${ADB[@]}" shell am start -n "$PACKAGE/.MainActivity" -W >/dev/null

accessibility_dump() {
    timeout 12s "${ADB[@]}" shell dumpsys accessibility 2>/dev/null | tr -d '\r' || true
}

enabled_accessibility() {
    timeout 8s "${ADB[@]}" shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r' || true
}

is_accessibility_enabled() {
    enabled_accessibility | grep -Fqi "$ACCESSIBILITY_COMPONENT"
}

is_accessibility_bound() {
    accessibility_dump | sed -n '/Bound services:/,/Enabled services:/p' | grep -Eqi 'Sanaa Agent|AccessibilityAgentService'
}

is_accessibility_crashed() {
    accessibility_dump | sed -n '/Crashed services:/,$p' | grep -Eqi "$ACCESSIBILITY_COMPONENT|AccessibilityAgentService"
}

is_overlay_allowed() {
    timeout 8s "${ADB[@]}" shell appops get "$PACKAGE" SYSTEM_ALERT_WINDOW 2>/dev/null | grep -qi allow
}

enabled_notification_listeners() {
    timeout 8s "${ADB[@]}" shell settings get secure enabled_notification_listeners 2>/dev/null | tr -d '\r' || true
}

is_notification_listener_enabled() {
    enabled_notification_listeners | grep -Fqi "$PACKAGE"
}

is_battery_exempt() {
    timeout 10s "${ADB[@]}" shell dumpsys deviceidle whitelist 2>/dev/null | grep -Fqi "$PACKAGE"
}

wait_for() {
    local check="$1"
    local waited=0
    while (( waited < WAIT_SECONDS )); do
        if "$check"; then return 0; fi
        sleep 3
        waited=$((waited + 3))
    done
    "$check"
}

if is_accessibility_crashed || ! is_accessibility_bound; then
    echo "owner_action=Enable Downloaded apps > Sanaa Agent on the opened Accessibility screen"
    "${ADB[@]}" shell am start -a android.settings.ACCESSIBILITY_SETTINGS >/dev/null
    if ! wait_for is_accessibility_bound; then
        echo "accessibility=FAIL enabled=$(is_accessibility_enabled && echo yes || echo no) bound=no crashed=$(is_accessibility_crashed && echo yes || echo no)"
        exit 3
    fi
fi

if ! is_overlay_allowed; then
    echo "owner_action=Allow display over other apps for Sanaa Agent on the opened screen"
    "${ADB[@]}" shell am start -a android.settings.action.MANAGE_OVERLAY_PERMISSION -d "package:$PACKAGE" >/dev/null 2>&1 || \
        "${ADB[@]}" shell am start -a android.settings.MANAGE_OVERLAY_PERMISSION -d "package:$PACKAGE" >/dev/null 2>&1 || true
    if ! wait_for is_overlay_allowed; then
        echo "overlay=FAIL"
        exit 4
    fi
fi

if ! is_notification_listener_enabled; then
    echo "owner_action=Allow notification access for Sanaa Agent on the opened screen"
    "${ADB[@]}" shell am start -a android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS >/dev/null 2>&1 || true
    if ! wait_for is_notification_listener_enabled; then
        echo "notification_listener=FAIL"
        exit 5
    fi
fi

if ! is_battery_exempt; then
    echo "owner_action=Allow Sanaa Agent to ignore battery optimizations on the opened screen"
    "${ADB[@]}" shell am start -a android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -d "package:$PACKAGE" >/dev/null 2>&1 || true
    if ! wait_for is_battery_exempt; then
        echo "battery_exemption=FAIL"
        exit 6
    fi
fi

BASE_APK=$(timeout 10s "${ADB[@]}" shell pm path "$PACKAGE" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | head -n 1)
[[ -n "$BASE_APK" ]] || { echo "error=package_not_installed" >&2; exit 7; }
INSTALLED_SHA=$(timeout 25s "${ADB[@]}" shell sha256sum "$BASE_APK" 2>/dev/null | awk '{print $1}' || true)
echo "installed_apk_path=$BASE_APK"
echo "installed_apk_sha256=${INSTALLED_SHA:-unavailable}"
if [[ -n "$LOCAL_SHA" ]]; then
    if [[ "$LOCAL_SHA" != "$INSTALLED_SHA" ]]; then
        echo "apk_identity=FAIL"
        exit 8
    fi
    echo "apk_identity=PASS"
else
    echo "apk_identity=OBSERVED_NO_LOCAL_COMPARISON"
fi

PACKAGE_DUMP=$(timeout 12s "${ADB[@]}" shell dumpsys package "$PACKAGE" 2>/dev/null | tr -d '\r' || true)
VERSION_NAME=$(printf '%s\n' "$PACKAGE_DUMP" | sed -n 's/^[[:space:]]*versionName=//p' | head -n 1)
VERSION_CODE=$(printf '%s\n' "$PACKAGE_DUMP" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -n 1)
PID=$(timeout 8s "${ADB[@]}" shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)
SERVICES=$(timeout 12s "${ADB[@]}" shell dumpsys activity services "$PACKAGE" 2>/dev/null | tr -d '\r' || true)
AGENT_SERVICE=$(printf '%s\n' "$SERVICES" | grep -c 'co.sanaa.agent/.services.AgentService}' || true)
FOREGROUND=$(printf '%s\n' "$SERVICES" | sed -n '/co.sanaa.agent\/.services\.AgentService}/,/^[[:space:]]*$/p' | grep -cE 'isForeground=true|foregroundService=true' || true)
JOB_COUNT=$(timeout 12s "${ADB[@]}" shell dumpsys jobscheduler 2>/dev/null | grep -ci "$PACKAGE" || true)
CRASH_COUNT=$(timeout 12s "${ADB[@]}" logcat -d -t 500 2>/dev/null | grep -cE "FATAL EXCEPTION.*$PACKAGE|Process: $PACKAGE|ANR in $PACKAGE" || true)

echo "package=$PACKAGE"
echo "version_name=${VERSION_NAME:-unknown}"
echo "version_code=${VERSION_CODE:-unknown}"
echo "pid=${PID:-none}"
echo "accessibility=PASS enabled=$(is_accessibility_enabled && echo yes || echo no) bound=yes crashed=$(is_accessibility_crashed && echo yes || echo no)"
echo "overlay=PASS"
echo "notification_listener=PASS component=$NOTIFICATION_COMPONENT"
echo "battery_exemption=PASS"
echo "agent_service=$([[ "$AGENT_SERVICE" -gt 0 ]] && echo PASS || echo FAIL)"
echo "agent_service_foreground=$([[ "$FOREGROUND" -gt 0 ]] && echo PASS || echo FAIL)"
echo "jobscheduler_matches=$JOB_COUNT"
echo "recent_fatal_anr=$CRASH_COUNT"

if [[ -z "$PID" || "$AGENT_SERVICE" -eq 0 || "$FOREGROUND" -eq 0 || "$CRASH_COUNT" -ne 0 ]] || is_accessibility_crashed; then
    exit 9
fi
