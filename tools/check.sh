#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug "$@"
