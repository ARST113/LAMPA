#!/usr/bin/env python3
"""Убрать из AAR ресурсы, которые уже объявлены другими зависимостями сборки.

Зачем: AAR Cefrium везёт полный ресурсный набор Chrome, включая копии ресурсов androidx.
Если рядом те же ресурсы объявлены настоящими библиотеками, `mergeChromiumDebugResources`
падает на «Duplicate value for resource 'attr/...'». Скрипт оставляет каждое определение
ровно в одном месте — у библиотек, и убирает дубликат из AAR.

Удаление делается XML-парсером, а не регулярками: значения в values-файлах бывают
многострочными с вложенными элементами (`<style>` с `<item>`), и regex легко рвёт XML.

Использование:
    python3 tools/slim_aar_resources.py ВХОД.aar ВЫХОД.aar СПИСОК_АРТЕФАКТОВ.txt

СПИСОК_АРТЕФАКТОВ.txt — пути AAR/JAR рантайм-класспути (по одному в строке),
их печатает Gradle-задача printChromiumRuntimeArtifacts.
"""
from __future__ import annotations

import os
import sys
import zipfile
import xml.etree.ElementTree as ET

DECL = ("attr", "string", "style", "color", "dimen", "bool", "integer", "string-array", "array")
XML_HEADER = b'<?xml version="1.0" encoding="utf-8"?>\n'


def collect_from_artifact(path: str) -> dict[str, set[str]]:
    """Имена ресурсов, объявленных в AAR/JAR (файлы res/values*)."""
    result: dict[str, set[str]] = {}
    if not path.endswith((".aar", ".jar", ".zip")):
        return result
    try:
        with zipfile.ZipFile(path) as z:
            for name in z.namelist():
                if "/values" not in name or not name.endswith(".xml"):
                    continue
                try:
                    root = ET.fromstring(z.read(name))
                except ET.ParseError:
                    continue
                for child in root:
                    if child.tag in DECL:
                        res_name = child.get("name")
                        if res_name:
                            result.setdefault(child.tag, set()).add(res_name)
    except zipfile.BadZipFile:
        pass
    return result


def strip_declarations(blob: bytes, foreign: dict[str, set[str]],
                       seen: dict[str, set[str]]) -> tuple[bytes, dict[str, int]]:
    """Убирает объявления, которые уже есть у других библиотек ИЛИ уже встречались в этом AAR.

    Второе важно не меньше первого: ресурсы AAR собраны из 1738 values-файлов Chrome и
    пересекаются между собой, поэтому один и тот же attr может лежать в двух файлах —
    для resource merger это такой же дубликат.
    """
    removed: dict[str, int] = {}
    try:
        root = ET.fromstring(blob)
    except ET.ParseError:
        return blob, removed
    for child in list(root):
        # attr объявляются и в корне, и внутри <declare-styleable> — проверяем оба уровня,
        # иначе дубликат остаётся и merger падает на «Duplicate value for resource attr/...».
        if child.tag == "declare-styleable":
            for sub in list(child):
                name = sub.get("name")
                if not name:
                    continue
                names = foreign.get(sub.tag, set())
                already = seen.setdefault(sub.tag, set())
                if name in names or name in already:
                    child.remove(sub)
                    key = f"declare-styleable/{sub.tag}"
                    removed[key] = removed.get(key, 0) + 1
                else:
                    already.add(name)
            if len(child) == 0:
                root.remove(child)
            continue
        name = child.get("name")
        if not name:
            continue
        names = foreign.get(child.tag, set())
        already = seen.setdefault(child.tag, set())
        if name in names or name in already:
            root.remove(child)
            removed[child.tag] = removed.get(child.tag, 0) + 1
        else:
            already.add(name)
    if not removed:
        return blob, removed
    return XML_HEADER + ET.tostring(root, encoding="utf-8"), removed


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
    print("объявлений у зависимостей:", {k: len(v) for k, v in sorted(foreign.items())})

    total: dict[str, int] = {}
    seen: dict[str, set[str]] = {}
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if "/values" in item.filename and item.filename.endswith(".xml"):
                data, removed = strip_declarations(data, foreign, seen)
                for kind, count in removed.items():
                    total[kind] = total.get(kind, 0) + count
            zout.writestr(item, data)

    print("удалено дубликатов из AAR:", dict(sorted(total.items())) or "нет")
    print("итог:", dst, f"({os.path.getsize(dst) / 1048576:.1f} МБ)")


if __name__ == "__main__":
    main(sys.argv[1:])
