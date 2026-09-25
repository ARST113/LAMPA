#!/usr/bin/env bash
# Smoke-проверка собранного клиента Lampa на уже запущенном эмуляторе.
# Запускается внутри reactivecircus/android-emulator-runner (adb доступен).
#
# Использование: bash ci/smoke_lampa.sh [flavor] [engine]
#   flavor — lite|full|ruStore|chromium (по умолчанию lite)
#   engine — default|XWalk|Cefrium|SysView (по умолчанию default)
#
# Движок задаётся до первого запуска записью в shared_prefs/settings.xml (ключ browser),
# иначе LAMPA на современном WebView выберет SysView и третий движок не проверится.
set -uo pipefail

flavor="${1:-lite}"
engine="${2:-default}"
flavor_lc=$(printf '%s' "$flavor" | tr '[:upper:]' '[:lower:]')
apk=$(find "app/build/outputs/apk/$flavor_lc/debug" -name '*.apk' 2>/dev/null | head -1)
if [[ -z "$apk" ]]; then
  echo "::error::APK не найден в app/build/outputs/apk/$flavor_lc/debug"
  ls -R app/build/outputs/apk 2>/dev/null || true
  exit 1
fi

mkdir -p artifacts/emulator
echo "APK: $apk ($(du -h "$apk" | cut -f1)) | движок: $engine"

echo "=== Устройство ==="
adb shell getprop ro.build.version.release | tee artifacts/emulator/android-release.txt
echo "PAGE_SIZE=$(adb shell getconf PAGE_SIZE | tr -d '\r')" | tee artifacts/emulator/page-size.txt
adb shell getprop > artifacts/emulator/android-properties.txt

echo "=== Установка ==="
adb logcat -c
adb install -r -t "$apk" 2>&1 | tee artifacts/emulator/install.log

if [[ "$engine" != "default" ]]; then
  echo "=== Выбор движка: $engine ==="
  cat > /tmp/lampa-settings.xml <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="browser">$engine</string>
</map>
XML
  if adb shell run-as top.rootu.lampa sh -c 'mkdir -p shared_prefs && cat > shared_prefs/settings.xml' < /tmp/lampa-settings.xml; then
    echo "настройки записаны"
    adb shell run-as top.rootu.lampa cat shared_prefs/settings.xml | tee artifacts/emulator/prefs.txt
  else
    echo "::warning::не удалось записать настройки через run-as (сборка не debuggable?) — движок останется по умолчанию"
  fi
fi

echo "=== Запуск ==="
adb shell am force-stop top.rootu.lampa
adb shell am start -n top.rootu.lampa/.MainActivity 2>&1 | tee artifacts/emulator/am-start.log || true

# На софтверном эмуляторе движок со 219 МБ libcef поднимается небыстро.
sleep 45

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

echo "=== Признаки движка ==="
grep -a -E "LampaCefrium|Cefrium|XWalk|SysView" artifacts/emulator/logcat.txt | head -40 | tee artifacts/emulator/engine-markers.txt || true

echo "=== Вердикт ==="
if adb shell dumpsys activity activities 2>/dev/null | grep -q "top.rootu.lampa/.CrashActivity"; then
  echo "::error::LAMPA ушла в CrashActivity"
  grep -a -A 40 -m 1 -E "FATAL EXCEPTION|CrashActivity" artifacts/emulator/logcat.txt | tail -60
  exit 1
fi
if [[ -z "$pid" ]]; then
  echo "::error::Процесс top.rootu.lampa не жив через 45 секунд после запуска"
  grep -a -B 5 -A 40 -m 1 "FATAL EXCEPTION" artifacts/emulator/logcat.txt | tail -60
  exit 1
fi
if [[ "$engine" == "Cefrium" ]]; then
  if grep -a -q "LampaCefrium: движок Cefrium поднят" artifacts/emulator/logcat.txt; then
    echo "движок Cefrium поднялся"
  else
    echo "::error::Выбран движок Cefrium, но подтверждения запуска движка в logcat нет"
    grep -a -E "LampaCefrium|Cefrium" artifacts/emulator/logcat.txt | tail -40
    exit 1
  fi
fi
echo "Smoke-проверка Lampa пройдена (flavor=$flavor, engine=$engine, pid=$pid)"
