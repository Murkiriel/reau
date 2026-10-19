"""Tests for scripts/apk_audit.py: what it reads from the APK's zip and how it answers against the baseline (0 nothing
new, 1 something new, 2 missing tool or file).
Characterization tests, written after the script (it came without tests). The SDK tools (aapt2, apksigner) are
replaced by fakes: the APK here is a small synthetic zip.

Run from the repo root:  python -m unittest discover -s scripts -p "test_apk_audit.py" -v
"""
import io
import json
import os
import sys
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import apk_audit  # noqa: E402


def make_apk(folder: str, dex: bytes = b"", extra: dict | None = None) -> str:
    path = os.path.join(folder, "app.apk")
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("META-INF/androidx.core_core.version", "1.13.0")
        z.writestr("kotlin-tooling-metadata.properties", "x=1")
        z.writestr("res/raw/notes.properties", "not a library: not at the root")
        z.writestr("lib/arm64-v8a/libexample.so", b"\x7fELF")
        z.writestr("classes.dex", b"dex\n" + dex)
        for name, content in (extra or {}).items():
            z.writestr(name, content)
    return path


class ScanZipTest(unittest.TestCase):

    def test_libraries_natives_and_hosts_come_from_the_zip(self):
        with tempfile.TemporaryDirectory() as d:
            apk = make_apk(d, dex=b"\x00https://raw.githubusercontent.com/x\x00http://schemas.android.com/apk/res\x00"
                                  b"https://Routing.Example.ORG/route\x00http://localhost:8002\x00",
                           extra={"classes2.dex": b"https://tiles.example.net/{z}"})
            libraries, natives, hosts = apk_audit.scan_zip(apk)
        self.assertEqual(["androidx.core_core", "kotlin-tooling-metadata"], libraries)
        self.assertEqual(["libexample.so"], natives)
        # lower case, from every classesN.dex; namespaces and localhost are not hosts the app talks to
        self.assertEqual(["raw.githubusercontent.com", "routing.example.org", "tiles.example.net"], hosts)

    def test_tracking_ads_and_billing_are_flagged(self):
        for name in ("com.google.firebase_firebase-analytics", "crashlytics", "com.android.billingclient_billing",
                     "android.permission.REQUEST_INSTALL_PACKAGES", "com.google.android.gms.permission.AD_ID"):
            self.assertTrue(apk_audit.SUSPICIOUS.search(name), name)
        for name in ("androidx.core_core", "android.permission.INTERNET", "raw.githubusercontent.com"):
            self.assertFalse(apk_audit.SUSPICIOUS.search(name), name)


class PermissionsTest(unittest.TestCase):

    def test_the_Lite_editions_own_permission_is_the_full_editions(self):
        dump = "\n".join([
            "uses-permission: name='android.permission.INTERNET'",
            "uses-permission: name='com.reau.app.lite.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'",
        ])
        done = mock.Mock(stdout=dump)
        with mock.patch.object(apk_audit.subprocess, "run", return_value=done):
            found = apk_audit.permissions("aapt2", "x.apk")
        self.assertEqual(["android.permission.INTERNET", "com.reau.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"], found)


class AgainstTheBaselineTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.apk = make_apk(self.tmp.name, dex=b"https://raw.githubusercontent.com/x")
        self.baseline = os.path.join(self.tmp.name, "baseline.json")
        self.perms = ["android.permission.INTERNET"]
        for name, value in (("BASELINE", self.baseline), ("find_sdk", lambda: "sdk"),
                            ("build_tool", lambda sdk, name: name), ("certificate", lambda signer, apk: "cert"),
                            ("permissions", lambda aapt2, apk: list(self.perms))):
            patcher = mock.patch.object(apk_audit, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def run_main(self, *args: str) -> tuple[int, str]:
        out = io.StringIO()
        with mock.patch.object(sys, "argv", ["apk_audit.py", *args]), redirect_stdout(out):
            code = apk_audit.main()
        return code, out.getvalue()

    def test_record_then_the_same_apk_is_nothing_new(self):
        self.assertEqual(0, self.run_main(self.apk, "--gravar")[0])
        with open(self.baseline, encoding="utf-8") as fh:
            self.assertEqual(["raw.githubusercontent.com"], json.load(fh)["hosts"])
        code, out = self.run_main(self.apk)
        self.assertEqual(0, code)
        self.assertIn("Nada novo", out)

    def test_a_new_permission_is_reported_and_exits_1(self):
        self.run_main(self.apk, "--gravar")
        self.perms.append("android.permission.READ_CONTACTS")
        code, out = self.run_main(self.apk)
        self.assertEqual(1, code)
        self.assertIn("+ permissions: android.permission.READ_CONTACTS", out)
        self.assertIn("! permissions: android.permission.READ_CONTACTS", out)

    def test_something_gone_is_listed_but_is_not_new(self):
        self.perms.append("android.permission.VIBRATE")
        self.run_main(self.apk, "--gravar")
        self.perms.remove("android.permission.VIBRATE")
        code, out = self.run_main(self.apk)
        self.assertEqual(0, code)
        self.assertIn("- saiu (permissions): android.permission.VIBRATE", out)

    def test_no_baseline_or_no_apk_exits_2(self):
        self.assertEqual(2, self.run_main(self.apk)[0])
        self.assertEqual(2, self.run_main(os.path.join(self.tmp.name, "missing.apk"))[0])


if __name__ == "__main__":
    unittest.main()
