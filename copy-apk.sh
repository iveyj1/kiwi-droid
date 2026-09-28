#!/usr/bin/env bash
# Publish the existing debug APK; build first with ./tools/check.sh.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
apk=app/build/outputs/apk/debug/app-debug.apk
destination=/mnt/main/public/kiwi-droid
if [[ ! -f "$apk" ]]; then
    echo "APK missing. Run ./tools/check.sh first." >&2
    exit 1
fi
if [[ ! -d /mnt/main/public ]]; then
    echo "Public share unavailable: /mnt/main/public" >&2
    exit 1
fi
mkdir -p "$destination"
cp "$apk" "$destination/kiwi-droid.apk"
printf 'Copied APK to %s/kiwi-droid.apk\n' "$destination"
