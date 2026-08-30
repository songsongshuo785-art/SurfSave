from __future__ import annotations

import tempfile
import unittest
import zipfile
from pathlib import Path

from verify_native_libs import ABIS, expected_apks, verify_native_libraries


class NativeLibraryContractTest(unittest.TestCase):
    @staticmethod
    def elf_for(abi: str) -> bytes:
        elf_class, machine = {
            "armeabi-v7a": (1, 40),
            "arm64-v8a": (2, 183),
            "x86": (1, 3),
            "x86_64": (2, 62),
        }[abi]
        header = bytearray(20)
        header[:4] = b"\x7fELF"
        header[4] = elf_class
        header[5] = 1
        header[6] = 1
        header[18:20] = machine.to_bytes(2, byteorder="little")
        return bytes(header) + b"x" * (64 * 1024)

    def write_apks(
        self,
        root: Path,
        missing: str | None = None,
        wrong_arch_entry: str | None = None,
        extra_abi_apk: str | None = None,
    ) -> None:
        for apk_name, abis in expected_apks("diagnostic").items():
            with zipfile.ZipFile(root / apk_name, "w") as archive:
                for abi in abis:
                    go_entry = f"lib/{abi}/libgojni.so"
                    rust_entry = f"lib/{abi}/libsurfsave_content_block.so"
                    if missing != go_entry:
                        archive.writestr(go_entry, b"go")
                    if missing != rust_entry:
                        elf_abi = "x86" if wrong_arch_entry == rust_entry else abi
                        archive.writestr(rust_entry, self.elf_for(elf_abi))
                if apk_name == extra_abi_apk:
                    archive.writestr("lib/riscv64/libunexpected.so", b"unexpected")

    def test_accepts_five_apks_with_all_expected_abis(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_apks(root)
            verify_native_libraries(root, "diagnostic")

    def test_rejects_missing_content_block_library(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            missing = f"lib/{ABIS[0]}/libsurfsave_content_block.so"
            self.write_apks(root, missing=missing)
            with self.assertRaisesRegex(ValueError, "missing"):
                verify_native_libraries(root, "diagnostic")

    def test_rejects_library_stored_under_wrong_abi(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            wrong = "lib/arm64-v8a/libsurfsave_content_block.so"
            self.write_apks(root, wrong_arch_entry=wrong)
            with self.assertRaisesRegex(ValueError, "architecture mismatch"):
                verify_native_libraries(root, "diagnostic")

    def test_rejects_unexpected_abi_in_split(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_apks(root, extra_abi_apk="app-arm64-v8a-diagnostic.apk")
            with self.assertRaisesRegex(ValueError, "ABI set mismatch"):
                verify_native_libraries(root, "diagnostic")


if __name__ == "__main__":
    unittest.main()
