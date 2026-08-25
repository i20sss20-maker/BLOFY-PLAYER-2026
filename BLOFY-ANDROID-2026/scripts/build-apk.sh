#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"

if [[ -z "${BLOFY_BASE_URL:-}" || ! "${BLOFY_BASE_URL}" =~ ^https:// ]]; then
  echo "Set BLOFY_BASE_URL to the public HTTPS Railway URL before building."
  exit 2
fi

if [[ ! -x ./gradlew ]]; then
  if ! command -v gradle >/dev/null 2>&1; then
    echo "Gradle is unavailable. Open the project in Android Studio or run this script in Codemagic."
    exit 3
  fi
  gradle wrapper --gradle-version 8.13
fi

./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug -PBLOFY_BASE_URL="$BLOFY_BASE_URL"
echo "APK: $project_dir/app/build/outputs/apk/debug/app-debug.apk"

