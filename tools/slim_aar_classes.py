#!/usr/bin/env python3
"""Убрать из AAR классы, которые уже есть в других зависимостях сборки.

AAR Cefrium несёт внутри себя копии сторонних библиотек (androidx, material, lottie и т.д.).
Если те же классы приходят из настоящих зависимостей, AGP падает на
`checkChromiumDebugDuplicateClasses`. Скрипт оставляет каждый класс в одном месте — у библиотек.

Использование:
    python3 tools/slim_aar_classes.py ВХОД.aar ВЫХОД.aar СПИСОК_АРТЕФАКТОВ.txt
"""
from __future__ import annotations

import io
import os
import sys
import zipfile


def classes_of_artifact(path: str) -> set[str]:
    """Имена .class внутри JAR или внутри classes.jar у AAR."""
    result: set[str] = set()
    if not path.endswith((".aar", ".jar", ".zip")):
        return result
    try:
        with zipfile.ZipFile(path) as z:
            inner = "classes.jar" if path.endswith(".aar") and "classes.jar" in z.namelist() else None
            if inner:
                with zipfile.ZipFile(io.BytesIO(z.read(inner))) as jz:
                    result.update(n for n in jz.namelist() if n.endswith(".class"))
            else:
                result.update(n for n in z.namelist() if n.endswith(".class"))
    except (zipfile.BadZipFile, KeyError):
        pass
    return result


def main(argv: list[str]) -> None:
    if len(argv) < 3:
        sys.exit(__doc__)
    src, dst, list_file = argv[0], argv[1], argv[2]

    foreign: set[str] = set()
    artifacts = 0
    with open(list_file, encoding="utf-8") as fh:
        for line in fh:
            path = line.strip()
            if not path or not os.path.isfile(path):
                continue
            artifacts += 1
            foreign |= classes_of_artifact(path)
    print(f"просканировано артефактов: {artifacts}")
    print(f"классов у зависимостей: {len(foreign)}")

    with zipfile.ZipFile(src) as zin:
        jar = zin.read("classes.jar")
        with zipfile.ZipFile(io.BytesIO(jar)) as jz:
            entries = jz.infolist()
            kept, dropped = [], 0
            buffer = io.BytesIO()
            with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as out_jar:
                for item in entries:
                    name = item.filename
                    if name.endswith(".class") and name in foreign:
                        dropped += 1
                        continue
                    out_jar.writestr(item, jz.read(name))
                    kept.append(name)
        new_jar = buffer.getvalue()

        with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():
                data = new_jar if item.filename == "classes.jar" else zin.read(item.filename)
                zout.writestr(item, data)

    print(f"удалено классов-дубликатов: {dropped} (осталось {len(kept)})")
    print("итог:", dst, f"({os.path.getsize(dst) / 1048576:.1f} МБ)")


if __name__ == "__main__":
    main(sys.argv[1:])
