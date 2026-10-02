#!/usr/bin/env bash
# Refreshes app/src/main/assets/mw_stations.tsv from the FCC AM Query. Review the diff before committing.
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
url='https://transition.fcc.gov/fcc-bin/amq?freq=530&fre2=1700&list=4&size=9'
raw=$(mktemp)
trap 'rm -f "$raw"' EXIT
printf 'Downloading FCC AM records (about 10 MB)…\n'
curl --fail --silent --show-error --max-time 300 -o "$raw" "$url"
mkdir -p app/src/main/assets
./gradlew -q :core:importMwStations --args="$raw app/src/main/assets/mw_stations.tsv"
git diff --stat -- app/src/main/assets/mw_stations.tsv || true
