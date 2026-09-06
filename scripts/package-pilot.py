#!/usr/bin/env python3
"""Package the built APK into small parts for the initial family handoff."""
import hashlib
import io
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parent.parent
DIST = ROOT / "dist"
APK = "SeniorLink-0.1.0-debug.apk"
PART_SIZE = 7 * 1024 * 1024


def main() -> None:
    data = (ROOT / "app/build/outputs/apk/debug/app-debug.apk").read_bytes()
    DIST.mkdir(exist_ok=True)
    (DIST / APK).write_bytes(data)
    (DIST / "SHA256SUMS").write_text(f"{hashlib.sha256(data).hexdigest()}  {APK}\n")
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        # Fixed metadata keeps packaging reproducible for the same APK.
        entry = zipfile.ZipInfo(APK, date_time=(2026, 1, 1, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(entry, data, compresslevel=9)
    compressed = buffer.getvalue()
    for old in DIST.glob("SeniorLink-0.1.0.zip.part*"):
        old.unlink()
    for index, start in enumerate(range(0, len(compressed), PART_SIZE), 1):
        (DIST / f"SeniorLink-0.1.0.zip.part{index:02d}").write_bytes(compressed[start:start + PART_SIZE])
    print(f"Installable APK: {DIST / APK}")
    print(f"Sync-friendly archive: {len(compressed):,} bytes in {(len(compressed) + PART_SIZE - 1) // PART_SIZE} parts")


if __name__ == "__main__":
    main()
