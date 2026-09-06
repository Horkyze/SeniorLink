#!/usr/bin/env python3
"""Restore the pilot APK from small, sync-friendly archive parts; no Android SDK needed."""
import hashlib
import io
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parent.parent
DIST = ROOT / "dist"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def main() -> None:
    expected, name = (DIST / "SHA256SUMS").read_text().strip().split()
    match = re.fullmatch(r"SeniorLink-(\d+\.\d+\.\d+)-debug\.apk", name)
    require(match is not None and re.fullmatch(r"[0-9a-f]{64}", expected) is not None, "Invalid checksum manifest")
    version = match[1]
    output = DIST / name
    if output.exists() and hashlib.sha256(output.read_bytes()).hexdigest() == expected:
        print(f"Verified installable APK: {output}")
        return
    parts = sorted(DIST.glob(f"SeniorLink-{version}.zip.part*"))
    require(parts and [p.suffix for p in parts] == [
        f".part{i:02d}" for i in range(1, len(parts) + 1)
    ], "Missing or out-of-order archive parts")
    require(sum(p.stat().st_size for p in parts) < 50 * 1024 * 1024, "Unexpected archive size")
    with zipfile.ZipFile(io.BytesIO(b"".join(p.read_bytes() for p in parts))) as archive:
        require(archive.getinfo(name).file_size < 100 * 1024 * 1024, "Unexpected APK size")
        data = archive.read(name)
    require(hashlib.sha256(data).hexdigest() == expected, "APK checksum mismatch; do not install")
    temporary = DIST / (name + ".tmp")
    temporary.write_bytes(data)
    temporary.replace(output)
    print(f"Verified installable APK: {output}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        raise SystemExit(f"Cannot restore APK: {error}") from error
