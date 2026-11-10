"""Tests for scripts/emu/run_instrumented.py: which instrumented classes it runs, and what it installs.

One app since 0.3.0: all the instrumented tests are in src/androidTest.

Run from the repo root:  python -B -m unittest discover -s scripts/emu -p "test_run_instrumented.py" -v
"""
import importlib
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)


def load():
    os.environ["REAU_EMU_SERIAL"] = "emulator-5580"
    import run_instrumented
    return importlib.reload(run_instrumented)


class OneAppTest(unittest.TestCase):
    def test_the_app_is_com_reau_app_and_runs_all_the_instrumented_classes(self):
        run = load()
        self.assertEqual("com.reau.app", run.APP_ID)
        self.assertEqual("com.reau.app.test/com.reau.app.KeepAppStateRunner", run.RUNNER)
        self.assertEqual(
            ("app/build/outputs/apk/debug/app-debug.apk", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"), run.APKS)
        self.assertIn("com.reau.app.service.ProjectionEndToEndTest", run.all_classes())
        self.assertEqual("com.reau.app.service.ProjectionEndToEndTest", run.qualified("ProjectionEndToEndTest"))
        self.assertNotIn("com.reau.app.service.AaServerAccessibilityTest", run.all_classes())

    def test_there_is_no_edition_switch_any_more(self):
        run = load()
        self.assertFalse(hasattr(run, "EDITION"))
        self.assertFalse(any("androidTestCompleta" in str(root) for root in run.test_roots()))


class OneProcessPerClassTest(unittest.TestCase):
    """The whole suite in a single process was retired (28/10/2026): one process per class is the only way."""

    def test_the_one_process_option_is_refused_before_any_adb_call(self):
        run = load()
        old = sys.argv
        sys.argv = ["run_instrumented.py", "--one-process"]
        try:
            with self.assertRaises(SystemExit) as caught:
                run.main()
        finally:
            sys.argv = old
        self.assertIn("--one-process", str(caught.exception))
        self.assertIn("uma classe por processo", str(caught.exception))

    def test_the_docs_of_the_script_do_not_offer_the_mode(self):
        self.assertNotIn("--one-process", load().__doc__)


if __name__ == "__main__":
    unittest.main()
