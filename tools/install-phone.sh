#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
if [[ $# -gt 1 ]]; then echo "Usage: $0 [adb-serial]" >&2; exit 1; fi
args=()
if [[ $# == 1 ]]; then args=(-s "$1"); fi
adb "${args[@]}" get-state
./gradlew :app:assembleDebug
adb "${args[@]}" install -r app/build/outputs/apk/debug/app-debug.apk
adb "${args[@]}" shell am start -n org.kiwidroid.app/.MainActivity
