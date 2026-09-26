#!/usr/bin/env python3
import re
import sys
import zipfile
import tempfile
from pathlib import Path

src = Path(sys.argv[1])
if not src.is_file():
    raise SystemExit(f"AAR not found: {src}")

dimen_tag = re.compile(
    rb'<dimen(?P<attrs>[^>]*)>\s*50%\s*</dimen>',
    re.IGNORECASE,
)
item_dimen = re.compile(
    rb'<item(?P<attrs>[^>]*\btype=["\']dimen["\'][^>]*)>\s*50%\s*</item>',
    re.IGNORECASE,
)

def patch_xml(data: bytes, name: str):
    changes = []

    def repl_dimen(m):
        attrs = m.group("attrs")
        if b'format=' not in attrs:
            attrs += b' format="float"'
        changes.append(("dimen", m.group(0)))
        return b'<item' + attrs + b' type="dimen">0.5</item>'

    def repl_item(m):
        attrs = m.group("attrs")
        if b'format=' not in attrs:
            attrs += b' format="float"'
        changes.append(("item", m.group(0)))
        return b'<item' + attrs + b'>0.5</item>'

    out = dimen_tag.sub(repl_dimen, data)
    out = item_dimen.sub(repl_item, out)

    # Avoid accidentally introducing duplicate type attributes when converting
    # a <dimen> tag whose attrs already contain unusual metadata.
    out = out.replace(b'type="dimen" type="dimen"', b'type="dimen"')
    return out, changes

tmp = src.with_suffix(".patched.aar")
total = 0

with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(tmp, "w") as zout:
    for info in zin.infolist():
        data = zin.read(info.filename)
        if info.filename.startswith("res/values") and info.filename.endswith(".xml"):
            patched, changes = patch_xml(data, info.filename)
            if changes:
                print(f"{info.filename}: {len(changes)} legacy 50% pseudo-dimen(s)")
                for _, old in changes:
                    print("  ", old.decode("utf-8", "replace").strip())
                total += len(changes)
                data = patched
        zout.writestr(info, data)

if total == 0:
    tmp.unlink(missing_ok=True)
    raise SystemExit("No legacy 50% pseudo-dimens found; refusing blind patch")

tmp.replace(src)
print(f"Patched {total} Cefrium resource value(s) in {src}")
