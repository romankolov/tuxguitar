#!/usr/bin/env bash
# android-emulator-runner executes separate input lines in separate shells. Keep
# the working directory, Gradle exit status and diagnostics in this one script.
set -uo pipefail
cd "$(dirname "$0")/.."
mkdir -p build sample-android/build
python3 tools/ci_build.py ./gradlew :sample-android:connectedDebugAndroidTest --stacktrace --console=plain
result=$?
adb pull /sdcard/Android/data/com.tuxcompose.sample/files/screenshots sample-android/build/device-screenshots || true
if [[ "$result" -eq 0 ]]; then
  python3 tools/release_smoke.py
  result=$?
fi
adb logcat -d > sample-android/build/device-logcat.txt || true
exit "$result"
