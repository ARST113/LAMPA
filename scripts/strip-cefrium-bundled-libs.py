#!/usr/bin/env python3
"""Strip Material Components and Lottie classes bundled inside the fat Cefrium AAR.

The published runtime carries these libraries as *class files only*, together with
Chromium's whitelisted slice of their resources. That slice is not usable by a normal
app: for example res/values-af declares error_icon_content_description while the default
configuration does not, so com.google.android.material.R$string lacks the field and
TextInputLayout dies with NoSuchFieldError.

The app therefore declares the real Maven artifacts
(com.google.android.material:material, com.airbnb.android:lottie). Their class copies
must be removed from the AAR, otherwise dexing fails on duplicate classes. The AAR's
resource files stay untouched: they are Chromium's own merged resources and the Android
resource merger resolves the overlap in favour of the Maven artifacts.
"""
import io
import sys
import zipfile
from pathlib import Path

STRIPPED_PREFIXES = (
    "com/google/android/material/",
    "com/airbnb/lottie/",
)


def strip_classes_jar(data: bytes) -> tuple[bytes, int]:
    src = zipfile.ZipFile(io.BytesIO(data), "r")
    out = io.BytesIO()
    removed = 0
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as dst:
        for info in src.infolist():
            if info.filename.startswith(STRIPPED_PREFIXES):
                removed += 1
                continue
            dst.writestr(info, src.read(info.filename))
    src.close()
    return out.getvalue(), removed


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit("usage: strip-cefrium-bundled-libs.py <runtime.aar>")
    src = Path(sys.argv[1])
    if not src.is_file():
        raise SystemExit(f"AAR not found: {src}")

    tmp = src.with_suffix(".stripped.aar")
    removed = 0
    with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(tmp, "w") as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)
            if info.filename == "classes.jar":
                data, removed = strip_classes_jar(data)
            zout.writestr(info, data)
    tmp.replace(src)
    print(f"Stripped {removed} bundled Material/Lottie classes from {src}")
    if removed == 0:
        raise SystemExit("nothing was stripped: is classes.jar layout different?")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())