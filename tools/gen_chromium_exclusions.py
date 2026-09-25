#!/usr/bin/env python3
"""Сгенерировать Gradle-исключения зависимостей, конфликтующих с AAR Cefrium.

AAR SDK везёт внутри себя копии сторонних библиотек. Если та же библиотека приходит
из зависимостей приложения, AGP падает (mergeExtDex: duplicate classes) либо resource
merger ругается на дубли attr. Чтобы движок получил свои классы и ресурсы — как в
работающем probe-приложении — из flavor chromium исключаем эти зависимости.

Использование:
    python3 tools/gen_chromium_exclusions.py AAR СПИСОК_АРТЕФАКТОВ.txt ВЫХОД.gradle

Координаты (group/name) берутся из пути кэша Gradle: .../files-2.1/<group>/<name>/<version>/...
"""
from __future__ import annotations

import io
import os
import sys
import zipfile


def classes_of(path: str) -> set[str]:
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


def coordinates(path: str, gradle_cache: str = "") -> tuple[str, str] | None:
    """group:name для артефакта.

    AGP отдаёт пути двух видов: из кэша модулей (.../files-2.1/<group>/<name>/<version>/…)
    и из transforms (.../transforms/<hash>/transformed/jetified-<name>-<version>/…).
    Во втором случае group в пути отсутствует, поэтому ищем модуль в кэше по имени и версии.
    """
    parts = path.split(os.sep)
    if "files-2.1" in parts:
        i = parts.index("files-2.1")
        return (parts[i + 1], parts[i + 2]) if len(parts) >= i + 4 else None

    for part in parts:
        if part.startswith("jetified-") or part.startswith("_"):
            stem = part.split("-", 1)[1] if part.startswith("jetified-") else part[1:]
            if "-" not in stem:
                continue
            name, version = stem.rsplit("-", 1)
            if not gradle_cache or not os.path.isdir(gradle_cache):
                return None
            for entry in os.listdir(gradle_cache):
                candidate = os.path.join(gradle_cache, entry, name, version)
                if os.path.isdir(candidate):
                    return entry, name
    return None


def main(argv: list[str]) -> None:
    if len(argv) < 3:
        sys.exit(__doc__)
    aar, list_file, out_file = argv[0], argv[1], argv[2]
    gradle_cache = argv[3] if len(argv) > 3 else os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")

    aar_classes = classes_of(aar)
    print(f"классов в AAR: {len(aar_classes)}")

    collisions: dict[tuple[str, str], int] = {}
    with open(list_file, encoding="utf-8") as fh:
        for line in fh:
            path = line.strip()
            if not path or not os.path.isfile(path):
                continue
            shared = len(classes_of(path) & aar_classes)
            if not shared:
                continue
            coords = coordinates(path, gradle_cache)
            if coords:
                collisions[coords] = collisions.get(coords, 0) + shared

    lines = [
        "// Сгенерировано CI (tools/gen_chromium_exclusions.py).",
        "// Зависимости, чьи классы дублируются внутри AAR Cefrium: для flavor chromium",
        "// их исключаем, чтобы движок работал со своими версиями (как в probe-приложении).",
        "configurations.matching { it.name.toLowerCase().startsWith('chromium') }.configureEach {",
    ]
    for (group, name), shared in sorted(collisions.items()):
        lines.append(f"    exclude group: '{group}', module: '{name}'   // общих классов: {shared}")
    lines.append("}")
    open(out_file, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print(f"исключений: {len(collisions)} → {out_file}")
    for (g, n), s in sorted(collisions.items(), key=lambda kv: -kv[1])[:15]:
        print(f"   {g}:{n} — {s}")


if __name__ == "__main__":
    main(sys.argv[1:])
