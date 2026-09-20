#!/bin/bash
set -euo pipefail
cd /var/www/cards.sanaa.ug/Sanaa-Agent
echo "=== GIT STATUS ==="
git status --short 2>&1 | head -40
echo
echo "=== GIT BRANCH / HEAD ==="
git branch -a 2>&1 | head
git log --oneline -5 2>&1
echo
echo "=== TIKTOK / AMARA / SOKO SOURCES ==="
find . -type f \( -name '*.kt' -o -name '*.java' -o -name '*.dart' \) \
  \( -iname '*soko*' -o -iname '*tiktok*' -o -iname '*amara*' -o -iname '*sanaa*' \) 2>/dev/null | grep -v -E '\.git/|build/|\.gradle/' | head -80
echo
echo "=== GRADLE / ADB PROCESSES (clean) ==="
ps aux | grep -E 'gradle|adb|ADB|Watchdog|watchdog' | grep -v grep | head -20
echo
echo "=== PORT 5037/5038 LISTENING ==="
(ss -ltnp 2>/dev/null || netstat -ltnp 2>/dev/null) | grep -E '5037|5038' || echo "no listener on 5037/5038"
echo
echo "=== ADB VERSION / DEVICES ==="
export ADB_SERVER_SOCKET=tcp:127.0.0.1:5038
adb --version 2>&1 | head -3
adb devices -l 2>&1 | head -10
ADB_SERVER_SOCKET=tcp:127.0.0.1:5037 adb devices -l 2>&1 | head -10
