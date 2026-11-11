"""Tests of the mutation tooling itself: the list is well formed and its snippets are still in the code (no Gradle needed), the slices
split it exactly, and a file left mutated by a killed run is put back."""
import contextlib
import io
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mutacoes  # noqa: E402
import mutate  # noqa: E402


class ListaTest(unittest.TestCase):
    def test_every_entry_has_five_parts_and_a_unique_name(self):
        names = [m[0] for m in mutacoes.MUTATIONS]
        for m in mutacoes.MUTATIONS:
            self.assertEqual(5, len(m), m[0])
            self.assertTrue(m[4] and all(isinstance(c, str) for c in m[4]), f"{m[0]}: needs the test classes")
            self.assertNotEqual(m[2], m[3], f"{m[0]}: the mutation changes nothing")
        self.assertEqual([], sorted({n for n in names if names.count(n) > 1}), "names must be unique (the filter picks by name)")

    def test_every_snippet_is_in_its_file_exactly_once(self):
        stale = []
        for name, rel, old, _new, _classes in mutacoes.MUTATIONS:
            text = (mutate.ROOT / rel).read_text(encoding="utf-8")
            count = max(text.count(old), text.count(old.replace("\n", "\r\n")) if "\n" in old else 0)
            if count != 1:
                stale.append(f"{name}: {count}x in {rel}")
        self.assertEqual([], stale, "update the snippet after a refactor")


class CommandForTest(unittest.TestCase):
    def test_python_test_files_run_with_unittest_and_jvm_classes_with_gradle(self):
        py = mutacoes.command_for(["test_apk_audit.py"])
        self.assertEqual(["-m", "unittest", "discover"], py[2:5] if py[1] == "-B" else py[1:4])
        self.assertIn("test_apk_audit.py", py)
        self.assertTrue(mutacoes.command_for(["SunPlaceTest"])[1].endswith("run_unit.py"))
        self.assertEqual("SunPlaceTest", mutacoes.command_for(["SunPlaceTest"])[-1])

    def test_a_python_test_in_a_folder_runs_from_that_folder_and_a_bare_name_from_scripts(self):
        bare = mutacoes.command_for(["test_apk_audit.py"])
        self.assertEqual(["-s", "scripts", "-t", "scripts", "-p", "test_apk_audit.py"], bare[bare.index("-s"):])
        nested = mutacoes.command_for(["registro/test_chaves.py", "registro/test_olhadas.py"])
        self.assertEqual(["-s", "scripts/registro", "-t", "scripts/registro", "-p", "test_chaves.py", "-p", "test_olhadas.py"], nested[nested.index("-s"):])

    def test_test_files_of_one_mutation_in_two_folders_are_refused(self):
        with self.assertRaises(ValueError):
            mutacoes.command_for(["registro/test_chaves.py", "test_apk_audit.py"])

    def test_every_python_test_named_in_the_list_exists(self):
        faltam = [f"{m[0]}: {c}" for m in mutacoes.MUTATIONS for c in m[4] if c.endswith(".py") and not (mutate.ROOT / "scripts" / c).is_file()]
        self.assertEqual([], faltam)


class FatiaTest(unittest.TestCase):
    def test_slices_cover_the_list_once(self):
        items = list(range(23))
        for total in (1, 2, 5, 6, 23, 30):
            parts = [mutacoes.fatia(items, i, total) for i in range(1, total + 1)]
            self.assertEqual(items, sorted(x for p in parts for x in p), total)
            self.assertLessEqual(max(map(len, parts)) - min(map(len, parts)), 1, total)

    def test_the_command_line(self):
        self.assertEqual(((2, 6), True, ["Once"]), mutacoes.parse(["--fatia", "2/6", "--lista", "Once"]))
        self.assertEqual((None, False, []), mutacoes.parse([]))
        with self.assertRaises(SystemExit):
            mutacoes.parse(["--fatia", "7/6"])


class RunTest(unittest.TestCase):
    """mutate.run against a test command that is a small Python program: it reads the mutated file and decides how the "tests" end."""

    def run_one(self, program, old="1 + 1", new="1 + 2", source="x = 1 + 1\n", timeout=None):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "a.py").write_text(source, encoding="utf-8")
            keep = mutate.ROOT, mutate.PENDING, mutate.TIMEOUT_S
            mutate.ROOT, mutate.PENDING = root, root / "build" / "pendentes"
            if timeout is not None:
                mutate.TIMEOUT_S = timeout
            out = io.StringIO()
            try:
                with contextlib.redirect_stdout(out):
                    caught = mutate.run([("uma", "a.py", old, new)], [sys.executable, "-c", program])
            finally:
                mutate.ROOT, mutate.PENDING, mutate.TIMEOUT_S = keep
            pending = list((root / "build" / "pendentes").iterdir()) if (root / "build" / "pendentes").exists() else []
            return caught, out.getvalue(), (root / "a.py").read_text(encoding="utf-8"), pending

    def test_a_mutation_the_tests_fail_on_is_caught_and_the_file_is_put_back(self):
        program = "import sys; sys.exit(1 if '1 + 2' in open('a.py').read() else 0)"
        caught, said, after, pending = self.run_one(program)
        self.assertEqual(1, caught)
        self.assertIn("uma: PEGA", said)
        self.assertEqual("x = 1 + 1\n", after)
        self.assertEqual([], pending)

    def test_a_mutation_the_tests_pass_with_is_not_caught(self):
        caught, said, after, _ = self.run_one("print('VERDE')")
        self.assertEqual(0, caught)
        self.assertIn("uma: NÃO PEGA  VERDE", said)
        self.assertEqual("x = 1 + 1\n", after)

    def test_a_build_that_broke_is_not_a_caught_mutation(self):
        caught, said, _, _ = self.run_one("import sys; print('NÃO COMPILOU'); sys.exit(1)")
        self.assertEqual(0, caught)
        self.assertIn("uma: NÃO COMPILOU", said)

    def test_a_snippet_that_is_not_in_the_file_exactly_once_runs_nothing(self):
        for source in ("x = 2\n", "x = 1 + 1\ny = 1 + 1\n"):
            caught, said, after, _ = self.run_one("import sys; sys.exit(1)", source=source)
            self.assertEqual(0, caught)
            self.assertIn("uma: TRECHO NÃO ACHADO", said)
            self.assertEqual(source, after)

    def test_a_test_run_that_hangs_is_not_caught_and_the_file_is_put_back(self):
        caught, said, after, pending = self.run_one("import time; time.sleep(30)", timeout=1)
        self.assertEqual(0, caught)
        self.assertIn("uma: NÃO TERMINOU em 1 s", said)
        self.assertEqual("x = 1 + 1\n", after)
        self.assertEqual([], pending)

    def test_the_real_limit_is_a_quarter_of_an_hour(self):
        self.assertEqual(900, mutate.TIMEOUT_S)


class BytecodeTest(unittest.TestCase):
    """11/11/2026: a .pyc written from a mutated source outlived the restore (same size, same second), and the mutated code kept being loaded."""

    def run_recording(self, program):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "a.py").write_text("ok = 1 != 2\n", encoding="utf-8")
            keep = mutate.ROOT, mutate.PENDING
            mutate.ROOT, mutate.PENDING = root, root / "build" / "pendentes"
            try:
                with contextlib.redirect_stdout(io.StringIO()):
                    caught = mutate.run([("uma", "a.py", "!=", "==")], [sys.executable, "-c", program])
            finally:
                mutate.ROOT, mutate.PENDING = keep
            seen = (root / "seen.txt").read_text(encoding="utf-8") if (root / "seen.txt").exists() else ""
            return caught, seen, (root / "a.py").stat().st_mtime, (root / "a.py").read_text(encoding="utf-8")

    def test_the_tests_of_a_run_and_their_children_write_no_bytecode(self):
        program = "import os, sys; open('seen.txt', 'w').write(os.environ.get('PYTHONDONTWRITEBYTECODE', '')); sys.exit(1)"
        caught, seen, _, after = self.run_recording(program)
        self.assertEqual(1, caught)
        self.assertEqual("1", seen)
        self.assertEqual("ok = 1 != 2\n", after)

    def test_the_restored_file_is_a_whole_second_later_than_the_mutated_one(self):
        program = "import os, sys; open('seen.txt', 'w').write(repr(os.stat('a.py').st_mtime)); sys.exit(1)"
        caught, seen, restored_at, after = self.run_recording(program)
        self.assertEqual(1, caught)
        self.assertGreaterEqual(restored_at, float(seen) + 1.0)
        self.assertEqual("ok = 1 != 2\n", after)


class RecoverTest(unittest.TestCase):
    def test_a_file_left_mutated_is_put_back_and_the_trace_goes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root, pending = Path(tmp), Path(tmp) / "build" / "pendentes"
            pending.mkdir(parents=True)
            target = root / "src" / "A.kt"
            target.parent.mkdir()
            target.write_text("mutated", encoding="utf-8")
            (pending / "src__A.kt.orig").write_bytes(b"original")
            (pending / "src__A.kt.path").write_text("src/A.kt", encoding="utf-8")
            self.assertEqual(1, mutate.recover(root, pending))
            self.assertEqual("original", target.read_text(encoding="utf-8"))
            self.assertEqual([], list(pending.iterdir()))
            self.assertEqual(0, mutate.recover(root, pending), "nothing left to restore")

    def test_a_trace_cut_short_restores_nothing_and_does_not_touch_the_root(self):
        with tempfile.TemporaryDirectory() as tmp:
            root, pending = Path(tmp), Path(tmp) / "build" / "pendentes"
            pending.mkdir(parents=True)
            (root / "keep.txt").write_text("intact", encoding="utf-8")
            (pending / "a.orig").write_bytes(b"")   # the run died writing the backup
            (pending / "a.path").write_text("", encoding="utf-8")
            self.assertEqual(0, mutate.recover(root, pending))
            self.assertEqual("intact", (root / "keep.txt").read_text(encoding="utf-8"))
            self.assertEqual([], list(pending.iterdir()))

    def test_no_pending_folder_is_nothing_to_do(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(0, mutate.recover(Path(tmp), Path(tmp) / "absent"))


if __name__ == "__main__":
    unittest.main()
