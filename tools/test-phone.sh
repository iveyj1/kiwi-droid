#!/usr/bin/env bash
# Installs in place (preserving settings), runs UI checks first, then screen-off playback.
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
if [[ $# -gt 1 ]]; then echo "Usage: $0 [adb-serial]" >&2; exit 1; fi
args=()
if [[ $# == 1 ]]; then args=(-s "$1"); fi
adb "${args[@]}" get-state
printf 'Unlock the phone before running. Tests interrupt playback and finish with the phone locked.\n'
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb "${args[@]}" install -r app/build/outputs/apk/debug/app-debug.apk
adb "${args[@]}" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
log=$(mktemp)
trap 'rm -f "$log"' EXIT
for test in RadioScreenTest BackgroundPlaybackTest; do
    adb "${args[@]}" shell am instrument -w -r -e class "org.kiwidroid.app.$test" \
        org.kiwidroid.app.test/androidx.test.runner.AndroidJUnitRunner | tee "$log"
    # am instrument may return exit status 0 even when JUnit fails.
    grep -q '^OK (1 test)' "$log" || { echo "$test did not pass." >&2; exit 1; }
done
printf '\nPhone tests passed. Unlock the phone to resume manual testing.\n'
