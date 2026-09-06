#!/usr/bin/env python3
"""Package the built APK into small parts for a synced family handoff."""
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parent.parent
DIST = ROOT / "dist"
PART_SIZE = 7 * 1024 * 1024


def main() -> None:
    build = ROOT / "app/build/outputs/apk/debug"
    metadata = json.loads((build / "output-metadata.json").read_text())
    version = metadata["elements"][0]["versionName"]
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise SystemExit("Unexpected APK version")
    apk = f"SeniorLink-{version}-debug.apk"
    data = (build / "app-debug.apk").read_bytes()
    DIST.mkdir(exist_ok=True)
    (DIST / apk).write_bytes(data)
    (DIST / "SHA256SUMS").write_text(f"{hashlib.sha256(data).hexdigest()}  {apk}\n")
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        # Fixed metadata keeps packaging reproducible for the same APK.
        entry = zipfile.ZipInfo(apk, date_time=(2026, 1, 1, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(entry, data, compresslevel=9)
    compressed = buffer.getvalue()
    # Only the current APK's handoff parts belong in the checkout; older releases stay on GitHub.
    for old in DIST.glob("SeniorLink-*.zip.part*"):
        old.unlink()
    for index, start in enumerate(range(0, len(compressed), PART_SIZE), 1):
        (DIST / f"SeniorLink-{version}.zip.part{index:02d}").write_bytes(compressed[start:start + PART_SIZE])
    print(f"Installable APK: {DIST / apk}")
    print(f"Sync-friendly archive: {len(compressed):,} bytes in {(len(compressed) + PART_SIZE - 1) // PART_SIZE} parts")


if __name__ == "__main__":
    main()
