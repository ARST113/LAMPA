#!/usr/bin/env python3
"""Переписать major-версию class-файлов внутри jar (например, 69 → 65 или 55).

Зачем: Cefrium SDK собирается JDK 25 и отдаёт class-файлы major 69. Сборочный стек
LAMPA (AGP 8.10 / R8) их не декоирует. Скрипт делает копию jar с понижённой версией,
чтобы проверить, принимает ли D8 такой байткод.

Использование:
    python3 tools/downgrade_class_version.py ВХОД.jar ВЫХОД.jar [ЦЕЛЕВАЯ_MAJOR=65]

Печатает распределение версий до и после; классы с неизвестной сигнатурой копирует как есть.
"""
from __future__ import annotations

import collections
import struct
import sys
import zipfile

MAGIC = b"\xca\xfe\xba\xbe"


def peek_version(data: bytes) -> int | None:
    if data[:4] != MAGIC or len(data) < 8:
        return None
    return struct.unpack(">HH", data[4:8])[1]


def rewrite(data: bytes, target: int) -> tuple[bytes, int | None]:
    major = peek_version(data)
    if major is None:
        return data, None
    return data[:4] + struct.pack(">HH", 0, target) + data[8:], major


def main(argv: list[str]) -> None:
    if len(argv) < 2:
        sys.exit(__doc__)
    src, dst = argv[0], argv[1]
    target = int(argv[2]) if len(argv) > 2 else 65

    before: collections.Counter = collections.Counter()
    after: collections.Counter = collections.Counter()
    total = 0

    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            new, major = rewrite(data, target)
            if major is not None and item.filename.endswith(".class"):
                before[major] += 1
                after[peek_version(new)] += 1
                total += 1
            zout.writestr(item, new)

    print(f"вход: {src}")
    print(f"выход: {dst} (major → {target})")
    print(f"class-файлов: {total}")
    print("версии до :", dict(sorted(before.items())))
    print("версии после:", dict(sorted(after.items())))


if __name__ == "__main__":
    main(sys.argv[1:])
