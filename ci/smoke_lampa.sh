#!/usr/bin/env bash
# Smoke-проверка собранного клиента Lampa на уже запущенном эмуляторе.
# Запускается внутри reactivecircus/android-emulator-runner (adb доступен).
#
# Использование: bash ci/smoke_lampa.sh [flavor]   (по умолчанию lite)
set -uo pipefail

flavor="${1:-lite}"
flavor_lc=$(printf '%s' "$flavor" | tr '[:upper:]' '[:lower:]')
apk=$(find "app/build/outputs/apk/$flavor_lc/debug" -name '*.apk' 2>/dev/null | head -1)
if [[ -z "$apk" ]]; then
  echo "::error::APK не найден в app/build/outputs/apk/$flavor_lc/debug"
  ls -R app/build/outputs/apk 2>/dev/null || true
  exit 1
fi

mkdir -p artifacts/emulator
echo "APK: $apk ($(du -h "$apk" | cut -f1))"

echo "=== Устройство ==="
adb shell getprop ro.build.version.release | tee artifacts/emulator/android-release.txt
echo "PAGE_SIZE=$(adb shell getconf PAGE_SIZE | tr -d '\r')" | tee artifacts/emulator/page-size.txt
adb shell getprop > artifacts/emulator/android-properties.txt

echo "=== Установка ==="
adb logcat -c
adb install -r -t "$apk" 2>&1 | tee artifacts/emulator/install.log

echo "=== Запуск ==="
adb shell am force-stop top.rootu.lampa
adb shell am start -n top.rootu.lampa/.MainActivity 2>&1 | tee artifacts/emulator/am-start.log || true

# На софтверном эмуляторе в CI приложение поднимается небыстро.
sleep 40

echo "=== Активности ==="
adb shell dumpsys activity activities > artifacts/emulator/activities.txt 2>&1 || true
grep -E "mResumedActivity|mFocusedApp|top.rootu.lampa" artifacts/emulator/activities.txt | head -30 || true
adb shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus" | head -3 | tee artifacts/emulator/current-focus.txt || true

echo "=== Живые процессы ==="
pid=$(adb shell pidof top.rootu.lampa | tr -d '\r')
echo "pid=$pid" | tee artifacts/emulator/pid.txt

echo "=== logcat ==="
adb logcat -d -v time > artifacts/emulator/logcat.txt 2>&1 || true
grep -a -E "FATAL EXCEPTION|AndroidRuntime|top.rootu.lampa|NoSuchMethodError|NoClassDefFoundError|ClassNotFoundException|UnsatisfiedLinkError|VerifyError|dlopen failed" \
  artifacts/emulator/logcat.txt | tail -200 || true
adb exec-out screencap -p > artifacts/emulator/screen.png 2>/dev/null || true

echo "=== Вердикт ==="
if adb shell dumpsys activity activities 2>/dev/null | grep -q "top.rootu.lampa/.CrashActivity"; then
  echo "::error::LAMPA ушла в CrashActivity"
  grep -a -A 40 -m 1 -E "FATAL EXCEPTION|CrashActivity" artifacts/emulator/logcat.txt | tail -60
  exit 1
fi
if [[ -z "$pid" ]]; then
  echo "::error::Процесс top.rootu.lampa не жив через 40 секунд после запуска"
  grep -a -B 5 -A 40 -m 1 "FATAL EXCEPTION" artifacts/emulator/logcat.txt | tail -60
  exit 1
fi
echo "Smoke-проверка Lampa пройдена (flavor=$flavor, pid=$pid)"
