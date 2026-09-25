#!/usr/bin/env python3
"""Инжектит рантайм Cefrium (Chromium) в уже собранный APK.

Почему так, а не Gradle-зависимостью: AAR SDK везёт полный ресурсный набор Chrome
(6154 файла, 728 attr) и конфликтует с androidx по attr/string/layout, из-за чего
`mergeChromiumDebugResources` падает. Поэтому классы декоируются отдельно (d8),
а в APK добавляются dex, нативная библиотека и ассеты CEF.

Использование:
    python3 tools/inject_cefrium_runtime.py APK AAR ABI WORKDIR

Ожидает, что dex уже собран в WORKDIR/dex/classes*.dex (это делает CI шагом d8).
Результат: WORKDIR/injected.apk (неподписанный — подпись и zipalign делает CI).
"""
from __future__ import annotations

import os
import re
import shutil
import sys
import zipfile


def dex_index(name: str) -> int:
    """classes.dex → 1, classes2.dex → 2, ..."""
    m = re.fullmatch(r"classes(\d*)\.dex", name)
    if not m:
        return 0
    return int(m.group(1)) if m.group(1) else 1


def main(argv: list[str]) -> None:
    if len(argv) < 4:
        sys.exit(__doc__)
    apk_path, aar_path, abi, workdir = argv[0], argv[1], argv[2], argv[3]
    os.makedirs(workdir, exist_ok=True)

    # 1. Подготовить полезную нагрузку из AAR
    with zipfile.ZipFile(aar_path) as aar:
        names = aar.namelist()
        lib_name = f"jni/{abi}/libcef.so"
        if lib_name not in names:
            sys.exit(f"в AAR нет {lib_name}; доступны: {[n for n in names if n.startswith('jni/')]}")
        lib_path = os.path.join(workdir, "libcef.so")
        with aar.open(lib_name) as src, open(lib_path, "wb") as dst:
            shutil.copyfileobj(src, dst, 1 << 20)
        assets = {}
        for n in names:
            if n.startswith("assets/") and not n.endswith("/"):
                assets[n[len("assets/"):]] = aar.read(n)
        print(f"libcef.so: {os.path.getsize(lib_path) / 1048576:.1f} МБ | ассетов CEF: {len(assets)}")

    # 2. Dex, подготовленный d8
    dex_dir = os.path.join(workdir, "dex")
    dex_files = sorted(
        (f for f in os.listdir(dex_dir) if re.fullmatch(r"classes\d*\.dex", f)),
        key=dex_index,
    ) if os.path.isdir(dex_dir) else []
    if not dex_files:
        sys.exit(f"нет dex-файлов в {dex_dir} — сначала запустите d8")
    print("dex-файлы:", dex_files)

    # 3. Пересобрать APK.
    #    Порядок dex-файлов важен: загрузчик классов берёт первое совпадение по имени,
    #    а Crosswalk в этом же APK владеет пакетом org.chromium.* (Chromium 53 против 152).
    #    Поэтому классы Cefrium ставим сразу после classes.dex, а dex-файлы приложения
    #    сдвигаем на количество добавленных — иначе побеждает старая копия Chromium.
    shift = len(dex_files)
    out_path = os.path.join(workdir, "injected.apk")
    with zipfile.ZipFile(apk_path) as zin:
        existing = set(zin.namelist())
        with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as zout:
            added = 0

            # 3.1 наши dex-файлы: classes2.dex … classes(shift+1).dex
            for offset, dex in enumerate(dex_files, start=1):
                idx = 1 + offset
                target = "classes.dex" if idx == 1 else f"classes{idx}.dex"
                zout.writestr(target, open(os.path.join(dex_dir, dex), "rb").read())
                added += 1

            # 3.2 dex-файлы приложения со сдвигом (classes.dex остаётся первым)
            for item in zin.infolist():
                name = item.filename
                idx = dex_index(name)
                if idx >= 2:
                    new_name = f"classes{idx + shift}.dex"
                    data = zin.read(name)
                    zout.writestr(new_name, data)
                else:
                    zout.writestr(item, zin.read(name))

            # libcef.so — без сжатия, чтобы система могла извлечь библиотеку
            target_lib = f"lib/{abi}/libcef.so"
            if target_lib not in existing:
                info = zipfile.ZipInfo(target_lib)
                info.compress_type = zipfile.ZIP_STORED
                info.external_attr = 0o644 << 16
                zout.writestr(info, open(lib_path, "rb").read())
                added += 1

            for name, data in assets.items():
                target = f"assets/{name}"
                if target in existing:
                    continue
                zout.writestr(target, data)
                added += 1

    print(f"{out_path}: добавлено записей {added}, dex сдвинуты на {shift} "
          f"({os.path.getsize(out_path) / 1048576:.1f} МБ)")


if __name__ == "__main__":
    main(sys.argv[1:])
