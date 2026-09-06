#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
. ./scripts/env.sh
sh scripts/build-native.sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 scripts/verify-apk.py
python3 scripts/package-pilot.py
