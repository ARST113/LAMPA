#!/usr/bin/env python3
"""Заменить classes.jar внутри AAR на подготовленный (с понижённой версией class-файлов).

Использование:
    python3 tools/repack_aar.py ВХОД.aar НОВЫЙ_classes.jar ВЫХОД.aar

Остальные записи AAR (AndroidManifest.xml, resources.arsc, R.txt, jni/*.so) копируются как есть.
"""
from __future__ import annotations

import sys
import zipfile

TARGET = "classes.jar"


def main(argv: list[str]) -> None:
    if len(argv) < 3:
        sys.exit(__doc__)
    src_aar, new_jar, dst_aar = argv[0], argv[1], argv[2]

    with zipfile.ZipFile(new_jar) as z:
        jar_bytes = z.read("classes.jar") if "classes.jar" in z.namelist() else None
    if jar_bytes is None:
        # на вход подали уже готовый classes.jar
        jar_bytes = open(new_jar, "rb").read()

    replaced = 0
    with zipfile.ZipFile(src_aar) as zin, zipfile.ZipFile(dst_aar, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            if item.filename == TARGET:
                zout.writestr(TARGET, jar_bytes)
                replaced += 1
            else:
                zout.writestr(item, zin.read(item.filename))
    if replaced != 1:
        sys.exit(f"в {src_aar} не найдено ровно одной записи {TARGET} (найдено {replaced})")
    print(f"{dst_aar}: classes.jar заменён ({len(jar_bytes) / 1048576:.1f} МБ)")


if __name__ == "__main__":
    main(sys.argv[1:])
