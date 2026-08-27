#!/usr/bin/env bash
set -euo pipefail

agent_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

# Signing credentials live in the parent deployment .env. Parse only the two
# required keys, without evaluating the file or printing either secret.
if [[ -f "$agent_root/../.env" ]]; then
  while IFS='=' read -r key value; do
    case "$key" in
      KEYSTORE_PASS|KEY_PASS)
        value="${value%$'\r'}"
        if [[ "$value" == \"*\" && "$value" == *\" ]]; then value="${value:1:${#value}-2}"; fi
        if [[ "$value" == \'*\' && "$value" == *\' ]]; then value="${value:1:${#value}-2}"; fi
        export "$key=$value"
        ;;
    esac
  done < "$agent_root/../.env"
fi

cd "$agent_root"
bash mission/amara-10-10/scripts/check_progress.sh
./gradlew testDebugUnitTest
bash mission/amara-complete-employee/scripts/check_side_effect_boundary.sh
bash mission/amara-complete-employee/scripts/check_evidence_redaction.sh

cd "$agent_root/flutter_ui"
dart format --output=none --set-exit-if-changed lib test
flutter analyze
flutter test

cd "$agent_root"
./gradlew assembleRelease

apk="$agent_root/app/build/outputs/apk/release/app-release.apk"
test -s "$apk" || { echo "Signed release APK was not produced: $apk"; exit 1; }
sdk_root="$(sed -n 's/^sdk.dir=//p' "$agent_root/local.properties" | head -n 1)"
apksigner="$(find "$sdk_root/build-tools" -mindepth 2 -maxdepth 2 -type f -name apksigner | sort -V | tail -n 1)"
test -x "$apksigner" || { echo "Android apksigner was not found under $sdk_root/build-tools"; exit 1; }
"$apksigner" verify "$apk"
echo "All local mission gates passed. APK: $apk"
