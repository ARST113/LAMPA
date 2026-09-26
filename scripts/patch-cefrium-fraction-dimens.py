#!/usr/bin/env python3
import re
import sys
import tempfile
import zipfile
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit("usage: patch-cefrium-fraction-dimens.py <cefrium.aar>")

aar = Path(sys.argv[1])
if not aar.is_file():
    raise SystemExit(f"AAR not found: {aar}")

item_re = re.compile(
    r'<item(?P<attrs>[^>]*\btype\s*=\s*["\']dimen["\'][^>]*)>(?P<value>\s*50%p?\s*)</item>'
)
dimen_re = re.compile(
    r'<dimen(?P<attrs>[^>]*)>(?P<value>\s*50%p?\s*)</dimen>'
)

patched = []

with tempfile.TemporaryDirectory() as td:
    out = Path(td) / aar.name

    with zipfile.ZipFile(aar, "r") as zin, zipfile.ZipFile(out, "w") as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)

            if info.filename.startswith("res/values") and info.filename.endswith(".xml"):
                text = data.decode("utf-8")

                def patch_item(match):
                    attrs = match.group("attrs")
                    if re.search(r'\bformat\s*=', attrs):
                        return match.group(0)
                    name = re.search(r'\bname\s*=\s*["\']([^"\']+)["\']', attrs)
                    patched.append((info.filename, name.group(1) if name else "<unnamed>", "item"))
                    return f'<item{attrs} format="fraction">{match.group("value")}</item>'

                def patch_dimen(match):
                    attrs = match.group("attrs")
                    name = re.search(r'\bname\s*=\s*["\']([^"\']+)["\']', attrs)
                    patched.append((info.filename, name.group(1) if name else "<unnamed>", "dimen"))
                    return f'<item type="dimen"{attrs} format="fraction">{match.group("value")}</item>'

                text = item_re.sub(patch_item, text)
                text = dimen_re.sub(patch_dimen, text)
                data = text.encode("utf-8")

            zout.writestr(info, data)

    if len(patched) != 4:
        for path, name, kind in patched:
            print(f"candidate: {path}: {kind} {name}")
        raise SystemExit(f"Expected exactly 4 Chromium 50% dimen resources, patched {len(patched)}")

    out.replace(aar)

for path, name, kind in patched:
    print(f"patched: {path}: {kind} {name} -> dimen(format=fraction) 50%")
