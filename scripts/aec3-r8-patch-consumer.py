#!/usr/bin/env python3
"""R8 experiment: narrow a fat SDK blanket keep; preserve the JNI rules."""
import hashlib
import json
import sys
import zipfile
from pathlib import Path

source = Path(sys.argv[1])
target = source.with_suffix('.r8.aar')
needle = b'-keep class org.chromium.** { *; }'
native_name = 'jni/arm64-v8a/libcef.so'
with zipfile.ZipFile(source) as zin:
    native_sha = hashlib.sha256(zin.read(native_name)).hexdigest()
    rules = zin.read('proguard.txt')
    assert rules.count(needle) == 1, 'Expected exactly one SDK blanket keep rule'
    assert b'CalledByNative' in rules and b'UsedByReflection' in rules
    with zipfile.ZipFile(target, 'w') as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)
            if info.filename == 'proguard.txt':
                data = data.replace(needle, b'# Experiment: retain annotated JNI/reflection entry points instead of all Chromium UI')
            zout.writestr(info, data)
with zipfile.ZipFile(target) as z:
    assert hashlib.sha256(z.read(native_name)).hexdigest() == native_sha
target.replace(source)
print(json.dumps({'removed_blanket_keep_rules': 1, 'native_sha256': native_sha,
                  'annotation_keep_rules_preserved': True}))

