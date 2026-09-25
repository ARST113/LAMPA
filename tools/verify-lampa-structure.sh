#!/usr/bin/env bash
# Проверка, что перед нами настоящий клиент Lampa, а не урезанная заготовка.
# Учитывает состояние ветки feature/chromium-ac3-runtime: Crosswalk удалён,
# движки — встроенный Chromium (Cefrium) и системный WebView (SysView).
set -uo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"

missing=(); present=()
need_file() { [[ -f "$1" ]] && present+=("$1") || missing+=("файл $1"); }
need_dir()  { [[ -d "$1" ]] && present+=("$1/") || missing+=("каталог $1"); }
need_grep() {
  if [[ -f "$2" ]] && grep -q -- "$1" "$2"; then present+=("$3"); else missing+=("$3 (нет '$1' в $2)"); fi
}

# 1. Каркас проекта
need_file app/build.gradle
need_file settings.gradle
need_file gradlew
need_file app/src/main/AndroidManifest.xml

# 2. Ключевые классы клиента
need_file app/src/main/java/top/rootu/lampa/MainActivity.kt
need_file app/src/main/java/top/rootu/lampa/AndroidJS.kt

# 3. Пакет browser с движками (Crosswalk удалён)
need_dir app/src/main/java/top/rootu/lampa/browser
need_file app/src/main/java/top/rootu/lampa/browser/Browser.kt
need_file app/src/main/java/top/rootu/lampa/browser/SysView.kt
need_file app/src/main/java/top/rootu/lampa/browser/Cefrium.kt
need_file app/src/main/res/layout/activity_cefrium.xml

# 4. Контент и адрес Lampa
need_dir app/src/main/assets
if [[ -n "$(ls -A app/src/main/assets 2>/dev/null)" ]]; then
  present+=("assets непустой ($(ls -A app/src/main/assets | wc -l) файл(ов))")
else
  missing+=("каталог app/src/main/assets пуст")
fi
need_grep 'LAMPA_URL' app/src/main/java/top/rootu/lampa/MainActivity.kt 'MainActivity работает с адресом Lampa (LAMPA_URL)'

# 5. Мост AndroidJS
for method in exit httpReq openPlayer openTorrentLink voiceStart; do
  need_grep "$method" app/src/main/java/top/rootu/lampa/AndroidJS.kt "AndroidJS.$method()"
done
need_grep 'JavascriptInterface' app/src/main/java/top/rootu/lampa/AndroidJS.kt 'AndroidJS публикует методы через @JavascriptInterface'

# 6. Точки интеграции движка
need_grep 'onBrowserPageFinished' app/src/main/java/top/rootu/lampa/MainActivity.kt 'MainActivity.onBrowserPageFinished'
need_grep 'SELECTED_BROWSER' app/src/main/java/top/rootu/lampa/MainActivity.kt 'MainActivity.SELECTED_BROWSER'
need_grep 'useCefrium' app/src/main/java/top/rootu/lampa/MainActivity.kt 'MainActivity.useCefrium'

# 7. Регрессия: Crosswalk не должен вернуться
if grep -rq 'org\.xwalk' app/src/main/java app/src/main/res 2>/dev/null; then
  missing+=("в исходниках снова есть ссылки на Crosswalk (org.xwalk)")
else
  present+=("Crosswalk отсутствует в исходниках")
fi

echo "=== Найдено (${#present[@]}) ==="
printf '  OK  %s\n' "${present[@]}"
if (( ${#missing[@]} )); then
  echo; echo "=== ОТСУТСТВУЕТ (${#missing[@]}) ==="
  printf '  !!  %s\n' "${missing[@]}"
  echo; echo "Проверка структуры клиента Lampa не пройдена — сборку не запускаем."
  exit 1
fi
echo; echo "Структура полного клиента Lampa подтверждена: ${#present[@]} проверок пройдено."
