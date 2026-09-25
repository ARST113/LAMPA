#!/usr/bin/env python3
"""Убрать из AAR ресурсы, которые уже объявлены другими зависимостями сборки.

Зачем: AAR Cefrium везёт полный ресурсный набор Chrome, включая копии ресурсов androidx.
Если рядом те же ресурсы объявлены настоящими библиотеками, `mergeChromiumDebugResources`
падает на «Duplicate value for resource 'attr/...'». Скрипт оставляет каждое определение
ровно в одном месте — у библиотек, и убирает дубликат из AAR.

Использование:
    python3 tools/slim_aar_resources.py ВХОД.aar ВЫХОД.aar СПИСОК_АРТЕФАКТОВ.txt

СПИСОК_АРТЕФАКТОВ.txt — пути AAR/JAR рантайм-класспути (по одному в строке),
их печатает Gradle-задача printChromiumRuntimeArtifacts.
"""
from __future__ import annotations

import os
import re
import sys
import zipfile

KINDS = ("attr", "string", "style", "color", "dimen", "bool", "integer", "string-array", "array")
NAME_RE = {k: re.compile(rf'<{k}\b[^>]*\bname="([^"]+)"') for k in KINDS}


def declared_names(blob: str) -> dict[str, set[str]]:
    found: dict[str, set[str]] = {}
    for kind, rx in NAME_RE.items():
        names = set(rx.findall(blob))
        if names:
            found[kind] = names
    return found


def collect_from_artifact(path: str) -> dict[str, set[str]]:
    """Собирает имена ресурсов из AAR (res/values*) или JAR (res/values*)."""
    result: dict[str, set[str]] = {}
    if not path.endswith((".aar", ".jar", ".zip")):
        return result
    try:
        with zipfile.ZipFile(path) as z:
            for name in z.namelist():
                if "/values" not in name or not name.endswith(".xml"):
                    continue
                try:
                    blob = z.read(name).decode("utf-8", "replace")
                except Exception:
                    continue
                for kind, names in declared_names(blob).items():
                    result.setdefault(kind, set()).update(names)
    except zipfile.BadZipFile:
        pass
    return result


def strip_declarations(blob: str, foreign: dict[str, set[str]]) -> tuple[str, dict[str, int]]:
    removed: dict[str, int] = {}
    for kind in KINDS:
        names = foreign.get(kind)
        if not names:
            continue
        # <attr name="X" .../> или <attr name="X" ...>...</attr>
        pattern = re.compile(
            rf'[ \t]*<{kind}\b[^>]*\bname="([^"]+)"[^>]*(?:/>|>.*?</{kind}>)[ \t]*\n?',
            re.DOTALL,
        )

        def repl(m: re.Match) -> str:
            if m.group(1) in names:
                removed[kind] = removed.get(kind, 0) + 1
                return ""
            return m.group(0)

        blob = pattern.sub(repl, blob)
    return blob, removed


def main(argv: list[str]) -> None:
    if len(argv) < 3:
        sys.exit(__doc__)
    src, dst, list_file = argv[0], argv[1], argv[2]

    foreign: dict[str, set[str]] = {}
    artifacts = 0
    with open(list_file, encoding="utf-8") as fh:
        for line in fh:
            path = line.strip()
            if not path or not os.path.isfile(path):
                continue
            artifacts += 1
            for kind, names in collect_from_artifact(path).items():
                foreign.setdefault(kind, set()).update(names)
    print(f"просканировано артефактов: {artifacts}")
    print("найдено у зависимостей:", {k: len(v) for k, v in sorted(foreign.items())})

    total: dict[str, int] = {}
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if "/values" in item.filename and item.filename.endswith(".xml"):
                text = data.decode("utf-8", "replace")
                text, removed = strip_declarations(text, foreign)
                for kind, count in removed.items():
                    total[kind] = total.get(kind, 0) + count
                data = text.encode("utf-8")
            zout.writestr(item, data)

    print("удалено дубликатов из AAR:", dict(sorted(total.items())) or "нет")
    print("итог:", dst, f"({os.path.getsize(dst) / 1048576:.1f} МБ)")


if __name__ == "__main__":
    main(sys.argv[1:])
