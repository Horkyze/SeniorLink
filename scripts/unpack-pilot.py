#!/usr/bin/env python3
"""Restore the pilot APK from small, sync-friendly archive parts; no Android SDK needed."""
import hashlib
import io
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parent.parent
DIST = ROOT / "dist"
APK = "SeniorLink-0.1.0-debug.apk"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def main() -> None:
    expected, name = (DIST / "SHA256SUMS").read_text().strip().split()
    require(name == APK and len(expected) == 64, "Invalid checksum manifest")
    output = DIST / APK
    if output.exists() and hashlib.sha256(output.read_bytes()).hexdigest() == expected:
        print(f"Verified installable APK: {output}")
        return
    parts = sorted(DIST.glob("SeniorLink-0.1.0.zip.part*"))
    require(parts and [p.suffix for p in parts] == [
        f".part{i:02d}" for i in range(1, len(parts) + 1)
    ], "Missing or out-of-order archive parts")
    require(sum(p.stat().st_size for p in parts) < 50 * 1024 * 1024, "Unexpected archive size")
    with zipfile.ZipFile(io.BytesIO(b"".join(p.read_bytes() for p in parts))) as archive:
        require(archive.getinfo(APK).file_size < 100 * 1024 * 1024, "Unexpected APK size")
        data = archive.read(APK)
    require(hashlib.sha256(data).hexdigest() == expected, "APK checksum mismatch; do not install")
    temporary = DIST / (APK + ".tmp")
    temporary.write_bytes(data)
    temporary.replace(output)
    print(f"Verified installable APK: {output}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        raise SystemExit(f"Cannot restore APK: {error}") from error
