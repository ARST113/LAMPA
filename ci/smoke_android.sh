#!/usr/bin/env bash
set -euo pipefail

APK="$(find app/build/outputs/apk/lite/debug -name '*.apk' | head -1)"
test -n "$APK"

adb logcat -c
adb install -r "$APK"
adb shell am force-stop top.rootu.lampa
adb shell am start -n top.rootu.lampa/.MainActivity || true

# Give the app time to initialize even on software-emulated CI.
sleep 35

echo "=== Current LAMPA activities ==="
adb shell dumpsys activity activities | grep -E "mResumedActivity|top.rootu.lampa" | head -60 || true

echo "=== LAMPA runtime log ==="
adb logcat -d -v time | grep -E "FATAL EXCEPTION|AndroidRuntime|top.rootu.lampa|NoSuchMethodError|NoClassDefFoundError|ClassNotFoundException|UnsatisfiedLinkError|VerifyError" | tail -400 || true

if adb shell dumpsys activity activities | grep -q "top.rootu.lampa/.CrashActivity"; then
  echo "LAMPA entered CrashActivity"
  exit 1
fi

if ! adb shell pidof top.rootu.lampa >/dev/null; then
  echo "LAMPA process is not alive"
  exit 1
fi

echo "LAMPA startup smoke test passed"
