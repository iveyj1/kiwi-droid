#!/usr/bin/env bash
# Rebuilds app/src/main/assets/mw_stations.tsv from the FCC AM Query, vetted against FCC LMS (US),
# ISED (Canada), and the CRT/IFT lists (Mexico). Review build/mw-import-report.txt and the diff before committing.
#
# Mexico: download the latest "Infraestructura de Estaciones AM/FM" CSV from
#   https://www.datos.gob.mx/dataset/infraestructura_estaciones_am_fm
# in a browser (the file server rejects scripted downloads) and save it as tools/data/mx_crt_am_fm.csv
# (any tools/data/mx_crt*.csv name works; the newest is used).
# Without it, Mexican rows are checked against the committed 2014 IFT list.
#
# --formats also refreshes app/src/main/assets/mw_formats.tsv from Wikidata/Wikipedia (about 11,000 articles;
# CC BY-SA 4.0). Formats are keyed by facility ID and channel, so an older format table stays usable.
set -euo pipefail
formats=false
if [[ ${1:-} == --formats ]]; then formats=true; elif [[ $# -gt 0 ]]; then echo "Usage: $0 [--formats]" >&2; exit 1; fi
cd "$(dirname "$0")/.."
source tools/env.sh
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
get() { curl --fail --silent --show-error --location --max-time 600 -o "$1" "$2"; }

printf 'FCC AM Query (about 10 MB)…\n'
get "$work/amq.txt" 'https://transition.fcc.gov/fcc-bin/amq?freq=530&fre2=1700&list=4&size=9'

printf 'FCC LMS facility table (about 10 MB)…\n'
for days in 0 1 2 3; do
    day=$(date -u -d "-$days day" +%m-%d-%Y)
    if get "$work/lms.zip" "https://enterpriseefiling.fcc.gov/dataentry/api/download/dbfile/$day/facility.zip" 2>/dev/null; then break; fi
done
unzip -q -o "$work/lms.zip" facility.dat -d "$work"

printf 'ISED broadcasting extract (about 15 MB)…\n'
get "$work/baserad.zip" 'https://www.ic.gc.ca/engineering/BC_DBF_FILES/baserad.zip'
unzip -q -o -j "$work/baserad.zip" amstatio.dbf -d "$work"

mexico=(--mx-ift tools/data/mx_ift_am_2014.tsv)
# Newest browser download wins; tolerate names such as mx_crt_am_fm.csv.csv or mx_crt_am_fm (1).csv.
crt=$(ls -t tools/data/mx_crt*.csv* 2>/dev/null | head -n 1 || true)
if [[ -n $crt ]]; then
    printf 'Mexico: using %s\n' "$crt"
    mexico=(--mx-crt "$crt")
else
    printf 'Mexico: no tools/data/mx_crt*.csv; using the 2014 IFT list\n'
fi

mkdir -p app/src/main/assets build
./gradlew -q :core:importMwStations --args="--fcc $work/amq.txt --lms $work/facility.dat --ised $work/amstatio.dbf ${mexico[0]} '${mexico[1]}' \
    --out app/src/main/assets/mw_stations.tsv --report build/mw-import-report.txt"
git diff --stat -- app/src/main/assets/mw_stations.tsv || true
if $formats; then
    printf 'Wikipedia formats…\n'
    ./gradlew -q :core:importMwFormats --args="--stations app/src/main/assets/mw_stations.tsv --out app/src/main/assets/mw_formats.tsv"
    git diff --stat -- app/src/main/assets/mw_formats.tsv || true
fi
