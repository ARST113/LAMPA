#!/usr/bin/env bash
# Проверка, что перед нами настоящий клиент Lampa, а не урезанная заготовка.
# Падает с подробным перечнем, если структура неполная (требование ТЗ: fail workflow
# if full Lampa structure is missing).
#
# Запуск: bash tools/verify-lampa-structure.sh
set -uo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

missing=()
present=()

need_file() {
  if [[ -f "$1" ]]; then present+=("$1"); else missing+=("файл $1"); fi
}
need_dir() {
  if [[ -d "$1" ]]; then present+=("$1/"); else missing+=("каталог $1"); fi
}
need_grep() {
  local pattern="$1" file="$2" what="$3"
  if [[ -f "$file" ]] && grep -q -- "$pattern" "$file"; then
    present+=("$what")
  else
    missing+=("$what (нет '$pattern' в $file)")
  fi
}

# --- 1. Каркас проекта ---
need_file app/build.gradle
need_file settings.gradle
need_file gradlew
need_file app/src/main/AndroidManifest.xml

# --- 2. Ключевые классы клиента ---
need_file app/src/main/java/top/rootu/lampa/MainActivity.kt
need_file app/src/main/java/top/rootu/lampa/AndroidJS.kt

# --- 3. Пакет browser с движками ---
need_dir app/src/main/java/top/rootu/lampa/browser
need_file app/src/main/java/top/rootu/lampa/browser/Browser.kt
need_file app/src/main/java/top/rootu/lampa/browser/XWalk.kt
need_file app/src/main/java/top/rootu/lampa/browser/SysView.kt

# --- 4. Загрузка контента (assets) ---
need_dir app/src/main/assets
need_grep 'LAMPA_URL' app/src/main/java/top/rootu/lampa/MainActivity.kt \
  'MainActivity работает с адресом Lampa (LAMPA_URL)'
# В коде LAMPA нет прямых ссылок на assets: cineplex.ttf подхватывает шрифтовой движок,
# а xwalk-command-line читает сам движок. Поэтому проверяем содержимое каталога, а не grep.
if [[ -n "$(ls -A app/src/main/assets 2>/dev/null)" ]]; then
  present+=("assets непустой ($(ls -A app/src/main/assets | wc -l) файл(ов))")
else
  missing+=("каталог app/src/main/assets пуст")
fi
need_file app/src/main/assets/xwalk-command-line

# --- 5. Мост AndroidJS: методы, которые обязаны сохраниться ---
for method in exit httpReq openPlayer openTorrentLink voiceStart; do
  need_grep "$method" app/src/main/java/top/rootu/lampa/AndroidJS.kt "AndroidJS.$method()"
done
need_grep 'JavascriptInterface' app/src/main/java/top/rootu/lampa/AndroidJS.kt \
  'AndroidJS помечен @JavascriptInterface'

# --- 6. Точки, за которые цепляется интеграция движка ---
need_grep 'onBrowserPageFinished' app/src/main/java/top/rootu/lampa/MainActivity.kt \
  'MainActivity.onBrowserPageFinished (колбэк окончания загрузки)'
need_grep 'SELECTED_BROWSER' app/src/main/java/top/rootu/lampa/MainActivity.kt \
  'MainActivity.SELECTED_BROWSER (выбор движка)'
need_grep 'activity_xwalk' app/src/main/java/top/rootu/lampa/MainActivity.kt \
  'MainActivity использует разметку activity_xwalk'
need_file app/src/main/res/layout/activity_xwalk.xml

echo "=== Найдено (${#present[@]}) ==="
printf '  OK  %s\n' "${present[@]}"

if (( ${#missing[@]} )); then
  echo
  echo "=== ОТСУТСТВУЕТ (${#missing[@]}) ==="
  printf '  !!  %s\n' "${missing[@]}"
  echo
  echo "Проверка структуры клиента Lampa не пройдена — сборку не запускаем."
  exit 1
fi

echo
echo "Структура полного клиента Lampa подтверждена: ${#present[@]} проверок пройдено."
