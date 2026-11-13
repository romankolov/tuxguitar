#!/usr/bin/env bash
# android-emulator-runner executes separate input lines in separate shells. Keep
# the working directory, Gradle exit status and diagnostics in this one script.
set -uo pipefail
cd "$(dirname "$0")/.."
mkdir -p build sample-android/build
python3 tools/device_diagnostics.py || exit 1
adb shell rm -rf /sdcard/Download/tux-compose-qa || exit 1
sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if [[ "$sdk" == "24" ]]; then
  # Android N occasionally fails the UTP install-write pipe with EIO for the
  # 118 MB bank/PDF APK. Install once without streaming and run the very same
  # complete AndroidJUnitRunner suite; no assertions, cases or skips are relaxed.
  python3 tools/ci_build.py ./gradlew :sample-android:assembleDebug :sample-android:assembleDebugAndroidTest --stacktrace --console=plain
  result=$?
  if [[ "$result" -eq 0 ]]; then
    python3 tools/ci_build.py python3 tools/debug_instrument.py
    result=$?
  fi
else
  python3 tools/ci_build.py ./gradlew :sample-android:connectedDebugAndroidTest --stacktrace --console=plain
  result=$?
fi
adb pull /sdcard/Download/tux-compose-qa sample-android/build/device-screenshots
capture_result=$?
if [[ "$result" -eq 0 && "$capture_result" -ne 0 ]]; then result=$capture_result; fi
if [[ "$result" -eq 0 ]]; then
  python3 tools/verify_device_captures.py || result=$?
  if [[ "$result" -eq 0 ]]; then python3 tools/verify_pdf_exports.py || result=$?; fi
fi
if [[ "$result" -eq 0 ]]; then
  python3 tools/release_smoke.py
  result=$?
fi
adb logcat -d > sample-android/build/device-logcat.txt || true
if [[ "$result" -ne 0 ]]; then
  python3 tools/device_diagnostics.py failure || true
fi
exit "$result"
