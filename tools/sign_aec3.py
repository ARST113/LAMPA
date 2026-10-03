#!/usr/bin/env python3
"""Sign inside a trusted runner; never print or publish signing credentials."""
import hashlib
import json
import os
import secrets
import subprocess
from pathlib import Path

EXPECTED_CERT = "6a9a0b5d59e90767a890733411b4bd59a7d063da250913d952054468f4a5145d"


def main():
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "36.0.0"
    original_key = os.environ.get("HAS_PROJECT_KEY") == "true"
    credentials = dict(os.environ)
    if original_key:
        config = Path("app/keystore/keystore_config")
        if config.exists():
            props = {}
            for line in config.read_text().splitlines():
                if line.strip() and not line.lstrip().startswith("#") and "=" in line:
                    key, value = line.split("=", 1)
                    props[key.strip()] = value.strip()
            keystore = Path("app") / props["storeFile"]
            credentials["AEC3_STORE_PASS"] = props["storePassword"]
            credentials["AEC3_KEY_PASS"] = props["keyPassword"]
            alias = props["keyAlias"]
        else:
            keystore = Path(os.environ["KEYSTORE_FILE"])
            if not keystore.is_absolute():
                keystore = Path("app") / keystore
            credentials["AEC3_STORE_PASS"] = os.environ["KEYSTORE_PASSWORD"]
            credentials["AEC3_KEY_PASS"] = os.environ["RELEASE_SIGN_KEY_PASSWORD"]
            alias = os.environ["RELEASE_SIGN_KEY_ALIAS"]
    else:
        keystore = Path(os.environ["RUNNER_TEMP"]) / "aec3-test.jks"
        credentials["AEC3_STORE_PASS"] = secrets.token_urlsafe(32)
        credentials["AEC3_KEY_PASS"] = credentials["AEC3_STORE_PASS"]
        alias = "aec3-compression-test"
        subprocess.run(["keytool", "-genkeypair", "-keystore", str(keystore), "-alias", alias,
                        "-storepass:env", "AEC3_STORE_PASS", "-keypass:env", "AEC3_KEY_PASS",
                        "-keyalg", "RSA", "-keysize", "3072", "-validity", "3650",
                        "-dname", "CN=AEC3 Compression Test"], env=credentials, check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    max15 = os.environ.get("AEC3_MAX15") == "true"
    target = Path("artifacts/Lampa-AEC3-compressed.apk" if original_key else ("artifacts/Lampa-AEC3-MAX15-TEST.apk" if max15 else "artifacts/Lampa-AEC3-compressed-TEST.apk"))
    subprocess.run([str(tools / "zipalign"), "-f", "4", "compressed-unsigned.apk", "compressed-aligned.apk"], check=True)
    subprocess.run([str(tools / "apksigner"), "sign", "--ks", str(keystore), "--ks-key-alias", alias,
                    "--ks-pass", "env:AEC3_STORE_PASS", "--key-pass", "env:AEC3_KEY_PASS",
                    "--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
                    "--v3-signing-enabled", "true", "--out", str(target), "compressed-aligned.apk"],
                   env=credentials, check=True)
    output = subprocess.check_output([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(target)], text=True)
    cert = next(line.split(": ", 1)[1] for line in output.splitlines() if "certificate SHA-256 digest:" in line)
    if original_key and cert != EXPECTED_CERT:
        target.unlink()
        raise ValueError("Configured key does not match the installed AEC3 certificate; refusing incompatible update")
    subprocess.run([str(tools / "zipalign"), "-c", "4", str(target)], check=True)
    report = {"name": target.name, "bytes": target.stat().st_size,
              "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
              "certificate_sha256": cert, "compatible_update": original_key,
              "package": "top.rootu.lampa.aec3" if original_key else ("top.rootu.lampa.cm15" if max15 else "top.rootu.lampa.comp"),
              "apk_signature_verification": output}
    Path("artifacts/signature-report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    Path("artifacts/SHA256SUMS.txt").write_text(report["sha256"] + "  " + target.name + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()

