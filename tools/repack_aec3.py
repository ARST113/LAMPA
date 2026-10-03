#!/usr/bin/env python3
"""Losslessly compress a pinned AEC3 APK without changing its executable payload."""
import argparse
import hashlib
import json
import re
import struct
import zipfile
from pathlib import Path

SOURCE_SHA256 = "dc1e3e3ea8f7ac6c92e520e604c9f74cf31fdd91fd63143a14c346a6a5a0b711"


def manifest_strings(data):
    offset = 8
    while offset < len(data):
        kind, header_size, size = struct.unpack_from("<HHI", data, offset)
        if kind == 1:
            count, _, flags, strings_start = struct.unpack_from("<IIII", data, offset + 8)
            strings = []
            spans = []
            for i in range(count):
                at = offset + strings_start + struct.unpack_from("<I", data, offset + header_size + i * 4)[0]
                if flags & 0x100:
                    # Both character and byte lengths use one or two bytes.
                    if data[at] & 0x80:
                        at += 2
                    else:
                        at += 1
                    length = data[at]
                    at += 1
                    if length & 0x80:
                        length = ((length & 0x7f) << 8) | data[at]
                        at += 1
                    end = at + length
                    strings.append(bytes(data[at:end]).decode("utf-8"))
                    spans.append((at, end, "utf-8"))
                else:
                    length = struct.unpack_from("<H", data, at)[0]
                    at += 2
                    if length & 0x8000:
                        length = ((length & 0x7fff) << 16) | struct.unpack_from("<H", data, at)[0]
                        at += 2
                    end = at + length * 2
                    strings.append(bytes(data[at:end]).decode("utf-16le"))
                    spans.append((at, end, "utf-16le"))
            return strings, spans
        offset += size
    raise ValueError("AXML string pool missing")


def patch_manifest(source, test_package=False):
    data = bytearray(source)
    strings, spans = manifest_strings(data)
    patched = 0
    offset = 8
    while offset < len(data):
        kind, _, size = struct.unpack_from("<HHI", data, offset)
        if kind == 0x102:
            name = struct.unpack_from("<I", data, offset + 20)[0]
            attr_start, attr_size, attr_count = struct.unpack_from("<HHH", data, offset + 24)
            if strings[name] == "application":
                for i in range(attr_count):
                    attr = offset + 16 + attr_start + i * attr_size
                    attr_name = struct.unpack_from("<I", data, attr + 4)[0]
                    if strings[attr_name] == "extractNativeLibs":
                        if data[attr + 15] != 0x12:
                            raise ValueError("extractNativeLibs must be a typed boolean")
                        struct.pack_into("<I", data, attr + 16, 0xffffffff)
                        patched += 1
        offset += size
    if patched != 1:
        raise ValueError(f"Expected exactly one extractNativeLibs attribute, got {patched}")
    if test_package:
        changed = 0
        for value, (start, end, encoding) in zip(strings, spans):
            if "top.rootu.lampa.aec3" in value:
                replacement = value.replace("top.rootu.lampa.aec3", "top.rootu.lampa.comp").encode(encoding)
                if len(replacement) != end - start:
                    raise ValueError("Test application ID must retain string-pool lengths")
                data[start:end] = replacement
                changed += 1
        if not changed:
            raise ValueError("Original application ID missing")
    return bytes(data)


def repack(source, target, test_package=False):
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    if digest != SOURCE_SHA256:
        raise ValueError(f"Source SHA256 mismatch: {digest}")
    payloads = []
    with zipfile.ZipFile(source) as src, zipfile.ZipFile(target, "w", allowZip64=False) as dst:
        for original in src.infolist():
            name = original.filename
            if re.match(r"^META-INF/[^/]+\.(SF|RSA|DSA|EC)$", name, re.I) or name == "META-INF/MANIFEST.MF":
                continue
            payload = src.read(name)
            before_sha = hashlib.sha256(payload).hexdigest()
            if name == "AndroidManifest.xml":
                payload = patch_manifest(payload, test_package)
            info = zipfile.ZipInfo(name, original.date_time)
            info.external_attr = original.external_attr
            info.create_system = original.create_system
            info.compress_type = original.compress_type
            if re.fullmatch(r"classes\d*\.dex", name) or name.startswith("lib/") and name.endswith(".so"):
                info.compress_type = zipfile.ZIP_DEFLATED
            # Retain STORED assets used through AssetManager.openFd and resource table.
            if info.compress_type == zipfile.ZIP_STORED:
                position = dst.fp.tell() + 30 + len(name.encode("utf-8"))
                padding = (-(position + 4)) % 4
                info.extra = struct.pack("<HH", 0xD935, padding) + bytes(padding)
            dst.writestr(info, payload, compresslevel=9)
            payloads.append({"name": name, "size": len(payload), "source_sha256": before_sha,
                             "sha256": hashlib.sha256(payload).hexdigest(), "method": info.compress_type})
    with zipfile.ZipFile(target) as result:
        assert result.testzip() is None
        for item in payloads:
            payload = result.read(item["name"])
            assert hashlib.sha256(payload).hexdigest() == item["sha256"]
            if item["name"] != "AndroidManifest.xml":
                assert item["source_sha256"] == item["sha256"]
            entry = result.getinfo(item["name"])
            if entry.compress_type == zipfile.ZIP_STORED:
                with target.open("rb") as raw:
                    raw.seek(entry.header_offset + 26)
                    name_len, extra_len = struct.unpack("<HH", raw.read(4))
                assert (entry.header_offset + 30 + name_len + extra_len) % 4 == 0
        assert result.getinfo("resources.arsc").compress_type == zipfile.ZIP_STORED
        for name in ("assets/icudtl.dat", "assets/resources.pak"):
            assert result.getinfo(name).compress_type == zipfile.ZIP_STORED
    return {"source_sha256": digest, "source_bytes": source.stat().st_size,
            "unsigned_bytes": target.stat().st_size,
            "unsigned_sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
            "package": "top.rootu.lampa.comp" if test_package else "top.rootu.lampa.aec3",
            "extractNativeLibs": True, "compression": "DEFLATE level 9",
            "payload_verification": "all executable payloads identical; manifest packaging boolean only",
            "entries": payloads}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("target", type=Path)
    parser.add_argument("--test-package", action="store_true")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    report = repack(args.source, args.target, args.test_package)
    if args.report:
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key != "entries"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

