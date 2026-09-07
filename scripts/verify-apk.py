#!/usr/bin/env python3
"""Fail if an APK is missing native code, contains desktop binaries, or breaks 16 KB support."""
import hashlib
from pathlib import Path
import struct
import sys
import zipfile


def verify(path: Path) -> None:
    abis = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86_64": (2, 62)}
    with zipfile.ZipFile(path) as apk, path.open("rb") as raw:
        names = set(apk.namelist())
        for abi, (elf_class, machine) in abis.items():
            for library in ("libiroh_ffi.so", "libjnidispatch.so", "libmaplibre.so"):
                name = f"lib/{abi}/{library}"
                assert name in names, f"Missing {name}"
                info = apk.getinfo(name)
                data = apk.read(name)
                assert data[:4] == b"\x7fELF" and data[4] == elf_class and data[5] == 1, name
                assert struct.unpack_from("<H", data, 18)[0] == machine, name
                if elf_class == 2:
                    phoff = struct.unpack_from("<Q", data, 32)[0]
                    entsize, count = struct.unpack_from("<HH", data, 54)
                    align_offset, align_type = 48, "<Q"
                else:
                    phoff = struct.unpack_from("<I", data, 28)[0]
                    entsize, count = struct.unpack_from("<HH", data, 42)
                    align_offset, align_type = 28, "<I"
                alignments = [
                    struct.unpack_from(align_type, data, phoff + i * entsize + align_offset)[0]
                    for i in range(count)
                    if struct.unpack_from("<I", data, phoff + i * entsize)[0] == 1
                ]
                minimum = 16384 if elf_class == 2 else 4096
                assert alignments and all(a >= minimum for a in alignments), f"{name}: {alignments}"
                assert info.compress_type == zipfile.ZIP_STORED, f"{name} is not mmap-able"
                raw.seek(info.header_offset)
                header = raw.read(30)
                name_length, extra_length = struct.unpack_from("<HH", header, 26)
                offset = info.header_offset + 30 + name_length + extra_length
                assert offset % 16384 == 0, f"{name}: APK entry is not 16 KB aligned"
                print(f"OK {name}: ABI, ELF LOAD alignment, APK alignment")
        assert not any(n.startswith(("darwin-", "linux-", "win32-")) for n in names), "Desktop library in APK"
    with path.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    print(f"SHA-256 {digest}  {path}")


if __name__ == "__main__":
    verify(Path(sys.argv[1] if len(sys.argv) > 1 else "app/build/outputs/apk/debug/app-debug.apk"))
