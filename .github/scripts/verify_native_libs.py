from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path


ABIS = ("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
MIN_CONTENT_BLOCK_LIBRARY_BYTES = 64 * 1024
ELF_HEADER_PREFIX_SIZE = 20
ELF_ARCHITECTURES = {
    "armeabi-v7a": (1, 40),
    "arm64-v8a": (2, 183),
    "x86": (1, 3),
    "x86_64": (2, 62),
}


def expected_apks(variant: str) -> dict[str, tuple[str, ...]]:
    return {
        f"app-universal-{variant}.apk": ABIS,
        **{
            f"app-{abi}-{variant}.apk": (abi,)
            for abi in ABIS
        },
    }


def verify_native_libraries(apk_dir: Path, variant: str) -> None:
    expected = expected_apks(variant)
    actual = {path.name for path in apk_dir.glob("*.apk") if path.is_file()}
    if actual != set(expected):
        raise ValueError(
            "APK filename set mismatch; "
            f"expected={sorted(expected)} actual={sorted(actual)}"
        )

    for apk_name, abis in expected.items():
        apk = apk_dir / apk_name
        with zipfile.ZipFile(apk) as archive:
            names = set(archive.namelist())
            packaged_abis = {
                parts[1]
                for name in names
                if name.startswith("lib/") and len(parts := name.split("/")) >= 3
            }
            if packaged_abis != set(abis):
                raise ValueError(
                    f"{apk_name} ABI set mismatch; "
                    f"expected={sorted(abis)} actual={sorted(packaged_abis)}"
                )
            for abi in abis:
                go_entry = f"lib/{abi}/libgojni.so"
                rust_entry = f"lib/{abi}/libsurfsave_content_block.so"
                for entry in (go_entry, rust_entry):
                    if entry not in names:
                        raise ValueError(f"{apk_name} is missing {entry}")
                rust_info = archive.getinfo(rust_entry)
                if rust_info.file_size < MIN_CONTENT_BLOCK_LIBRARY_BYTES:
                    raise ValueError(
                        f"{apk_name} has undersized {rust_entry}: {rust_info.file_size}"
                    )
                with archive.open(rust_entry) as library:
                    verify_elf_header(library.read(ELF_HEADER_PREFIX_SIZE), abi, apk_name)


def verify_elf_header(header: bytes, abi: str, apk_name: str) -> None:
    if len(header) < ELF_HEADER_PREFIX_SIZE or header[:4] != b"\x7fELF":
        raise ValueError(
            f"{apk_name} has invalid ELF header for "
            f"lib/{abi}/libsurfsave_content_block.so"
        )
    expected_class, expected_machine = ELF_ARCHITECTURES[abi]
    elf_class = header[4]
    elf_data = header[5]
    if elf_data != 1:
        raise ValueError(f"{apk_name} has non-little-endian Rust ELF for {abi}")
    machine = int.from_bytes(header[18:20], byteorder="little")
    if (elf_class, machine) != (expected_class, expected_machine):
        raise ValueError(
            f"{apk_name} Rust ELF architecture mismatch for {abi}; "
            f"class={elf_class} machine={machine}"
        )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Verify SurfSave native libraries in all split and universal APKs."
    )
    parser.add_argument("--apk-dir", required=True, type=Path)
    parser.add_argument("--variant", required=True, choices=("diagnostic", "release"))
    args = parser.parse_args(argv)
    try:
        verify_native_libraries(args.apk_dir, args.variant)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"Native APK verification failed: {error}", file=sys.stderr)
        return 1
    print(f"Verified SurfSave native libraries in {args.apk_dir} ({args.variant})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
