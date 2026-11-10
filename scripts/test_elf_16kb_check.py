"""Tests for scripts/elf_16kb_check.py: which program headers an ELF library needs to load on a phone with 16 KB pages.
The libraries here are synthetic ELF files with only the headers the script reads. The two real cases use the numbers
measured with `readelf -lW` on libconscrypt_jni.so (arm64-v8a) of conscrypt-android 2.5.3 (which fails on a 16 KB phone,
google/conscrypt#1413) and 2.6.3 (which passes).

Run from the repo root:  python -m unittest discover -s scripts -p "test_elf_16kb_check.py" -v
"""
import io
import os
import struct
import sys
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr, redirect_stdout

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import elf_16kb_check as check  # noqa: E402

LOAD, RELRO = check.PT_LOAD, check.PT_GNU_RELRO


def elf64(headers: list[tuple]) -> bytes:
    """An ELF64 whose only content is its program headers: (type, offset, vaddr, filesz, memsz, align)."""
    out = bytearray(b"\x7fELF" + bytes([2, 1, 1]) + bytes(9))
    out += bytes(0x40 - len(out))
    struct.pack_into("<Q", out, 0x20, 0x40)
    struct.pack_into("<HH", out, 0x36, 56, len(headers))
    for p_type, offset, vaddr, filesz, memsz, align in headers:
        out += struct.pack("<IIQQQQQQ", p_type, 4, offset, vaddr, vaddr, filesz, memsz, align)
    return bytes(out)


def problems(headers: list[tuple]) -> list[str]:
    return check.problems(check.program_headers(elf64(headers)))


CONSCRYPT_2_5_3_ARM64 = [
    (LOAD, 0x0, 0x0, 0x1E8804, 0x1E8804, 0x4000),
    (LOAD, 0x1E8C20, 0x1ECC20, 0x184B0, 0x1A170, 0x4000),
    (RELRO, 0x1E8C20, 0x1ECC20, 0x163E0, 0x163E0, 0x1),
]
CONSCRYPT_2_6_3_ARM64 = [
    (LOAD, 0x0, 0x0, 0x29F870, 0x29F870, 0x4000),
    (LOAD, 0x29F870, 0x2A3870, 0x18080, 0x18790, 0x4000),
    (LOAD, 0x2B78F0, 0x2BF8F0, 0x22A8, 0x4D80, 0x4000),
    (RELRO, 0x29F870, 0x2A3870, 0x18080, 0x18790, 0x1),
]


class ProgramHeadersTest(unittest.TestCase):
    def test_reads_type_offset_address_sizes_and_alignment(self):
        (h,) = check.program_headers(elf64([(LOAD, 0x10, 0x20, 0x30, 0x40, 0x4000)]))
        self.assertEqual(h, {"type": LOAD, "offset": 0x10, "vaddr": 0x20, "filesz": 0x30, "memsz": 0x40, "align": 0x4000})

    def test_something_that_is_not_an_elf_is_refused(self):
        with self.assertRaises(ValueError):
            check.program_headers(b"PK\x03\x04 not an elf")


class ProblemsTest(unittest.TestCase):
    def test_conscrypt_2_5_3_on_arm64_fails_because_the_relro_page_shares_data(self):
        found = problems(CONSCRYPT_2_5_3_ARM64)
        self.assertEqual(len(found), 1, found)
        self.assertIn("RELRO", found[0])

    def test_conscrypt_2_6_3_on_arm64_passes(self):
        self.assertEqual(problems(CONSCRYPT_2_6_3_ARM64), [])

    def test_a_relro_that_ends_on_a_16k_boundary_passes_whatever_follows_it(self):
        self.assertEqual(problems([
            (LOAD, 0x0, 0x0, 0x10000, 0x10000, 0x4000),
            (LOAD, 0x10000, 0x10000, 0x4000, 0x9000, 0x4000),
            (RELRO, 0x10000, 0x10000, 0x4000, 0x4000, 0x1),
        ]), [])

    def test_a_relro_that_ends_inside_the_page_with_nothing_else_in_it_passes(self):
        self.assertEqual(problems([
            (LOAD, 0x0, 0x0, 0x10000, 0x10000, 0x4000),
            (LOAD, 0x10000, 0x10000, 0x2000, 0x2000, 0x4000),
            (RELRO, 0x10000, 0x10000, 0x2000, 0x2000, 0x1),
        ]), [])

    def test_a_segment_aligned_to_4k_fails(self):
        found = problems([(LOAD, 0x0, 0x0, 0x1000, 0x1000, 0x1000)])
        self.assertEqual(len(found), 1, found)
        self.assertIn("alinhamento", found[0])

    def test_an_offset_and_an_address_that_do_not_agree_modulo_16k_fail(self):
        found = problems([(LOAD, 0x1000, 0x2000, 0x1000, 0x1000, 0x4000)])
        self.assertEqual(len(found), 1, found)
        self.assertIn("casam", found[0])

    def test_a_library_with_no_relro_is_judged_by_its_segments_alone(self):
        self.assertEqual(problems([(LOAD, 0x0, 0x0, 0x1000, 0x1000, 0x4000)]), [])


class FilesTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)

    def zip_with(self, name: str, entries: dict[str, bytes]) -> str:
        path = os.path.join(self.dir.name, name)
        with zipfile.ZipFile(path, "w") as z:
            for entry, content in entries.items():
                z.writestr(entry, content)
        return path

    def run_main(self, *paths: str) -> tuple[int, str]:
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = check.main(list(paths))
        return code, out.getvalue() + err.getvalue()

    def test_an_apk_is_judged_by_its_arm64_and_x86_64_libraries_only(self):
        apk = self.zip_with("a.apk", {
            "lib/arm64-v8a/libgood.so": elf64(CONSCRYPT_2_6_3_ARM64),
            "lib/x86_64/libgood.so": elf64(CONSCRYPT_2_6_3_ARM64),
            "lib/armeabi-v7a/libold.so": elf64(CONSCRYPT_2_5_3_ARM64),  # a phone with 16 KB pages is never 32-bit
        })
        code, text = self.run_main(apk)
        self.assertEqual(code, 0, text)
        self.assertNotIn("libold", text)

    def test_an_apk_with_a_bad_arm64_library_exits_1_and_names_it(self):
        apk = self.zip_with("b.apk", {"lib/arm64-v8a/libconscrypt_jni.so": elf64(CONSCRYPT_2_5_3_ARM64)})
        code, text = self.run_main(apk)
        self.assertEqual(code, 1, text)
        self.assertIn("FALHA", text)
        self.assertIn("libconscrypt_jni.so", text)

    def test_an_aar_is_read_from_its_jni_folder(self):
        aar = self.zip_with("c.aar", {"jni/arm64-v8a/libconscrypt_jni.so": elf64(CONSCRYPT_2_5_3_ARM64)})
        code, _ = self.run_main(aar)
        self.assertEqual(code, 1)

    def test_a_single_so_file_is_read_as_it_is(self):
        path = os.path.join(self.dir.name, "libx.so")
        with open(path, "wb") as fh:
            fh.write(elf64(CONSCRYPT_2_6_3_ARM64))
        self.assertEqual(self.run_main(path)[0], 0)

    def test_an_archive_with_no_library_exits_2(self):
        code, text = self.run_main(self.zip_with("d.apk", {"classes.dex": b"dex"}))
        self.assertEqual(code, 2, text)


if __name__ == "__main__":
    unittest.main()
