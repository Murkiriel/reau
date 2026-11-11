"""Tests for scripts/apk_audit.py: what it reads from the APK's zip and how it answers against the baseline (0 nothing
new, 1 something new, 2 missing tool or file).
Characterization tests, written after the script (it came without tests). The SDK tools (aapt2, apksigner) are
replaced by fakes: the APK here is a small synthetic zip.

Run from the repo root:  python -m unittest discover -s scripts -p "test_apk_audit.py" -v
"""
import io
import json
import os
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import apk_audit  # noqa: E402


def fake_dex(types: list[str] | None = None, payload: bytes = b"") -> bytes:
    """A dex with just what the audit reads: the header's string and type tables, and `payload` after them."""
    types = ["Lcom/reau/app/MainActivity;"] if types is None else types
    header_size = 0x70
    strings_off = header_size
    types_off = strings_off + 4 * len(types)
    data_off = types_off + 4 * len(types)
    string_data = b""
    offsets = []
    for descriptor in types:
        raw = descriptor.encode("utf-8")
        assert len(raw) < 128
        offsets.append(data_off + len(string_data))
        string_data += bytes([len(raw)]) + raw + b"\x00"
    header = bytearray(b"dex\n035\x00" + bytes(header_size - 8))
    struct.pack_into("<IIII", header, 0x38, len(types), strings_off, len(types), types_off)
    return (bytes(header) + b"".join(struct.pack("<I", o) for o in offsets)
            + b"".join(struct.pack("<I", i) for i in range(len(types))) + string_data + payload)


def elf64(loads: list[tuple], relro: tuple | None = None) -> bytes:
    """An ELF64 with just program headers: LOAD (offset, vaddr, filesz, memsz, align) and an optional GNU_RELRO (vaddr, memsz)."""
    headers = [(1, off, va, fs, ms, al) for off, va, fs, ms, al in loads]
    if relro:
        headers.append((0x6474E552, relro[0], relro[0], relro[1], relro[1], 1))
    out = bytearray(b"\x7fELF" + bytes([2, 1, 1]) + bytes(9))
    out += bytes(0x40 - len(out))
    struct.pack_into("<Q", out, 0x20, 0x40)
    struct.pack_into("<HH", out, 0x36, 56, len(headers))
    for p_type, offset, vaddr, filesz, memsz, align in headers:
        out += struct.pack("<IIQQQQQQ", p_type, 4, offset, vaddr, vaddr, filesz, memsz, align)
    return bytes(out)


# A library that loads with 16 KB pages, and the shape of Conscrypt 2.5.3's arm64 one, which does not (RELRO ending at 0x203000, data after it).
GOOD_SO = elf64([(0, 0, 0x10000, 0x10000, 0x4000)])
BAD_SO = elf64([(0, 0, 0x1E8804, 0x1E8804, 0x4000), (0x1E8C20, 0x1ECC20, 0x184B0, 0x1A170, 0x4000)], relro=(0x1ECC20, 0x163E0))


def make_apk(folder: str, dex: bytes = b"", extra: dict | None = None, types: list[str] | None = None,
             profile: bool = True, so: bytes = GOOD_SO, name: str = "app.apk") -> str:
    path = os.path.join(folder, name)
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("META-INF/androidx.core_core.version", "1.13.0")
        z.writestr("kotlin-tooling-metadata.properties", "x=1")
        z.writestr("res/raw/notes.properties", "not a library: not at the root")
        z.writestr("lib/arm64-v8a/libexample.so", so)
        z.writestr("classes.dex", fake_dex(types, dex))
        if profile:
            z.writestr(apk_audit.BASELINE_PROFILE, b"prof")
        for name, content in (extra or {}).items():
            z.writestr(name, content)
    return path


def xmltree(components: list[tuple], debuggable: bool = False, package: str = "com.reau.app") -> str:
    """A manifest as `aapt2 dump xmltree` prints it (the indentation and the attribute lines of the real one).
    A component is (tag, name, exported or None, with an intent-filter?)."""
    ns = "http://schemas.android.com/apk/res/android"
    lines = ["N: android=http://schemas.android.com/apk/res/android (line=3)", "  E: manifest (line=3)",
             f'    A: package="{package}" (Raw: "{package}")', "      E: uses-sdk (line=8)",
             f"        A: {ns}:minSdkVersion(0x0101020c)=29", "      E: application (line=80)",
             f'        A: {ns}:name(0x01010003)="com.reau.app.REauApp" (Raw: "com.reau.app.REauApp")']
    if debuggable:
        lines.append(f"        A: {ns}:debuggable(0x0101000f)=true")
    for tag, name, exported, with_filter in components:
        lines += [f"          E: {tag} (line=100)", f'            A: {ns}:name(0x01010003)="{name}" (Raw: "{name}")']
        if exported is not None:
            lines.append(f"            A: {ns}:exported(0x01010010)={'true' if exported else 'false'}")
        if with_filter:
            lines += ["              E: intent-filter (line=107)", "                  E: action (line=108)",
                      f'                    A: {ns}:name(0x01010003)="android.intent.action.MAIN" (Raw: "x")']
    lines += ["          E: uses-library (line=177)", f'            A: {ns}:name(0x01010003)="androidx.window.sidecar"']
    return "\n".join(lines) + "\n"


# The components of the real release manifest (the one app, 0.3.0): no accessibility service since 03/11/2026.
RELEASE_COMPONENTS = [
    ("activity", "com.reau.app.MainActivity", True, True),
    ("service", "com.reau.app.service.QuickStartTile", True, True),
    ("service", "com.reau.app.service.ProjectionService", False, False),
    ("provider", "androidx.core.content.FileProvider", False, False),
    ("provider", "androidx.startup.InitializationProvider", False, False),
    ("receiver", "androidx.profileinstaller.ProfileInstallReceiver", True, True),
]
RELEASE_MANIFEST = xmltree(RELEASE_COMPONENTS)
RELEASE_ENTRIES = ["classes.dex", apk_audit.BASELINE_PROFILE]


class BuildToolTest(unittest.TestCase):
    def test_the_newest_build_tools_is_the_one_with_the_greatest_number_not_the_last_as_text(self):
        with tempfile.TemporaryDirectory() as sdk:
            for version in ("9.0.0", "35.0.0", "35.0.1", "34.0.0"):
                folder = os.path.join(sdk, "build-tools", version)
                os.makedirs(folder)
                open(os.path.join(folder, "aapt2"), "w").close()
            self.assertTrue(apk_audit.build_tool(sdk, "aapt2").endswith(os.path.join("35.0.1", "aapt2")))
            self.assertIsNone(apk_audit.build_tool(sdk, "apksigner"))
            self.assertEqual([35, 0, 1], apk_audit.version_key(os.path.join(sdk, "build-tools", "35.0.1", "aapt2")))


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

    def test_the_old_Lite_editions_own_permission_is_still_read_as_the_apps(self):
        dump = "\n".join([
            "uses-permission: name='android.permission.INTERNET'",
            "uses-permission: name='com.reau.app.lite.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'",
        ])
        done = mock.Mock(stdout=dump)
        with mock.patch.object(apk_audit.subprocess, "run", return_value=done):
            found = apk_audit.permissions("aapt2", "x.apk")
        self.assertEqual(["android.permission.INTERNET", "com.reau.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"], found)


class CertificateTest(unittest.TestCase):
    def test_the_certificate_of_a_failed_apksigner_is_not_valid_and_of_a_good_one_is(self):
        def run(code):
            done = subprocess.CompletedProcess([], code, stdout="Signer #1 certificate DN: CN=x\nother\n", stderr="DOES NOT VERIFY")
            with mock.patch.object(apk_audit.subprocess, "run", return_value=done):
                return apk_audit.certificate("apksigner", "a.apk")
        self.assertEqual(("Signer #1 certificate DN: CN=x", True), run(0))
        text, ok = run(1)
        self.assertFalse(ok)
        self.assertIn("FALHOU apksigner: DOES NOT VERIFY", text)


class AgainstTheBaselineTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.apk = make_apk(self.tmp.name, dex=b"https://raw.githubusercontent.com/x")
        self.baseline = os.path.join(self.tmp.name, "baseline.json")
        self.perms = ["android.permission.INTERNET"]
        self.manifest = RELEASE_MANIFEST
        self.signed = True
        for name, value in (("BASELINE", self.baseline), ("find_sdk", lambda: "sdk"),
                            ("build_tool", lambda sdk, name: name), ("certificate", lambda signer, apk: ("cert", self.signed)),
                            ("permissions", lambda aapt2, apk: list(self.perms)),
                            ("manifest_text", lambda aapt2, apk: self.manifest)):
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

    def test_a_release_shape_problem_exits_1_even_with_nothing_new_and_is_not_recorded(self):
        self.run_main(self.apk, "--gravar")
        self.manifest = xmltree(RELEASE_COMPONENTS, debuggable=True)
        code, out = self.run_main(self.apk)
        self.assertEqual(1, code)
        self.assertIn("Nada novo", out)
        self.assertIn("FORMA DE RELEASE ERRADA", out)
        self.assertIn("depurável", out)
        before = os.path.getmtime(self.baseline)
        code, out = self.run_main(self.apk, "--gravar")
        self.assertEqual(1, code)
        self.assertIn("Nada gravado", out)
        self.assertEqual(before, os.path.getmtime(self.baseline))

    def test_the_dex_files_of_the_apk_are_read_for_the_texts_only_the_real_debug_code_has(self):
        # The release's devtools stubs are empty: a .dex that carries the real fault names is the debug code under the stubs' class names.
        self.run_main(self.apk, "--gravar")
        real = make_apk(self.tmp.name, dex=b"https://raw.githubusercontent.com/x DECODER_STALL", name="real.apk")
        code, out = self.run_main(real)
        self.assertEqual(1, code)
        self.assertIn("FORMA DE RELEASE ERRADA", out)
        self.assertIn("texto que só o código de debug de verdade tem: DECODER_STALL", out)
        self.assertEqual(0, self.run_main(self.apk)[0])

    def test_an_invalid_signature_exits_1_even_with_nothing_new_and_is_not_recorded(self):
        self.run_main(self.apk, "--gravar")
        self.signed = False
        code, out = self.run_main(self.apk)
        self.assertEqual(1, code)
        self.assertIn("Nada novo", out)
        self.assertIn("ASSINATURA INVÁLIDA", out)
        before = os.path.getmtime(self.baseline)
        code, out = self.run_main(self.apk, "--gravar")
        self.assertEqual(1, code)
        self.assertIn("Nada gravado", out)
        self.assertEqual(before, os.path.getmtime(self.baseline))
        # A debug APK is audited by its signature too.
        self.assertEqual(1, self.run_main(self.apk, "--apk-debug")[0])

    def test_a_debug_apk_can_be_audited_without_the_release_shape(self):
        self.run_main(self.apk, "--gravar")
        self.manifest = xmltree(RELEASE_COMPONENTS, debuggable=True)
        code, out = self.run_main(self.apk, "--apk-debug")
        self.assertEqual(0, code)
        self.assertNotIn("FORMA DE RELEASE", out)

    def test_a_native_library_that_does_not_load_with_16_kb_pages_exits_1_and_is_not_recorded(self):
        self.run_main(self.apk, "--gravar")
        bad = make_apk(self.tmp.name, dex=b"https://raw.githubusercontent.com/x", so=BAD_SO, name="bad.apk")
        code, out = self.run_main(bad)
        self.assertEqual(1, code)
        self.assertIn("NÃO CARREGA COM PÁGINAS DE 16 KB", out)
        self.assertIn("libexample.so", out)
        before = os.path.getmtime(self.baseline)
        code, out = self.run_main(bad, "--gravar")
        self.assertEqual(1, code)
        self.assertIn("Nada gravado", out)
        self.assertEqual(before, os.path.getmtime(self.baseline))

    def test_the_16_kb_check_also_applies_to_a_debug_apk(self):
        self.run_main(self.apk, "--gravar")
        bad = make_apk(self.tmp.name, dex=b"https://raw.githubusercontent.com/x", so=BAD_SO, name="bad.apk")
        self.assertEqual(1, self.run_main(bad, "--apk-debug")[0])

    def test_a_library_whose_header_cannot_be_read_is_a_problem_not_a_pass(self):
        broken = make_apk(self.tmp.name, so=b"\x7fELF", name="broken.apk")
        found = apk_audit.native_16kb(broken)
        self.assertEqual(1, len(found), found)
        self.assertIn("ilegível", found[0])

    def test_an_apk_that_cannot_be_read_for_its_shape_is_a_problem_not_a_pass(self):
        self.run_main(self.apk, "--gravar")
        self.manifest = "isto não é um manifesto\n"
        code, out = self.run_main(self.apk)
        self.assertEqual(1, code)
        self.assertIn("manifesto ilegível", out)


class DexClassesTest(unittest.TestCase):

    def test_the_types_a_dex_declares_come_out_as_internal_names(self):
        dex = fake_dex(["Lcom/reau/app/MainActivity;", "Landroidx/compose/ui/tooling/PreviewActivity;", "I", "[Lcom/x/Y;"])
        # primitive and array descriptors are not classes
        self.assertEqual(["com/reau/app/MainActivity", "androidx/compose/ui/tooling/PreviewActivity"], apk_audit.dex_classes(dex))

    def test_not_a_dex_or_a_cut_one_is_refused(self):
        with self.assertRaises(ValueError):
            apk_audit.dex_classes(b"not a dex at all" * 20)
        with self.assertRaises(ValueError):
            apk_audit.dex_classes(fake_dex(["Lcom/x/Y;"] * 3)[:0x72])

    def test_every_classes_dex_of_the_apk_is_read(self):
        with tempfile.TemporaryDirectory() as d:
            apk = make_apk(d, types=["Lcom/a/One;"], extra={"classes2.dex": fake_dex(["Lcom/b/Two;"])})
            self.assertEqual(["com/a/One", "com/b/Two"], sorted(apk_audit.apk_classes(apk)))


class ParseManifestTest(unittest.TestCase):

    def test_the_components_and_whether_they_are_really_exported(self):
        parsed = apk_audit.parse_manifest(xmltree([
            ("activity", "com.reau.app.MainActivity", True, True),
            ("service", "com.reau.app.service.ProjectionService", False, False),
            ("service", ".service.NoAttribute", None, True),       # an intent-filter and no attribute: exported
            ("service", "Bare", None, False),                       # no filter: not exported; a bare name gets the package
            ("provider", "androidx.core.content.FileProvider", None, True),   # a provider is never exported by default
        ]))
        self.assertEqual("com.reau.app", parsed["package"])
        self.assertFalse(parsed["debuggable"])
        self.assertEqual([
            ("activity", "com.reau.app.MainActivity", True),
            ("service", "com.reau.app.service.ProjectionService", False),
            ("service", "com.reau.app.service.NoAttribute", True),
            ("service", "com.reau.app.Bare", False),
            ("provider", "androidx.core.content.FileProvider", False),
        ], parsed["components"])

    def test_debuggable_is_read(self):
        self.assertTrue(apk_audit.parse_manifest(xmltree([], debuggable=True))["debuggable"])

    def test_uses_library_is_no_component(self):
        self.assertEqual([], apk_audit.parse_manifest(xmltree([]))["components"])


class ReleaseShapeTest(unittest.TestCase):
    """The production APK's shape (28/10/2026): each check fails on a fake APK that breaks only it."""

    GOOD_CLASSES = ["com/reau/app/MainActivity", "androidx/compose/ui/Modifier"]

    def shape(self, components=None, debuggable=False, classes=None, entries=None) -> list[str]:
        return apk_audit.release_shape(
            xmltree(RELEASE_COMPONENTS if components is None else components, debuggable=debuggable),
            self.GOOD_CLASSES if classes is None else classes, RELEASE_ENTRIES if entries is None else entries)

    def test_the_real_release_shape_passes(self):
        self.assertEqual([], self.shape())

    def test_an_apk_that_still_declares_the_accessibility_service_fails_exported_or_not(self):
        # Removed on 03/11/2026 (it must not show in Android's Accessibility settings): the expected change of the component baseline is that it is gone.
        for exported in (True, False):
            problems = self.shape(components=RELEASE_COMPONENTS + [("service", "com.reau.app.service.AaServerAccessibilityService", exported, True)])
            self.assertEqual(["componente fora da lista (service): com.reau.app.service.AaServerAccessibilityService"], problems)

    def test_a_debuggable_apk_fails(self):
        problems = self.shape(debuggable=True)
        self.assertEqual(1, len(problems))
        self.assertIn("depurável", problems[0])

    def test_a_class_of_the_debug_package_fails(self):
        problems = self.shape(classes=self.GOOD_CLASSES + ["com/reau/app/debug/CrashTestActivity"])
        self.assertEqual(["classe que só o debug tem: com/reau/app/debug/CrashTestActivity"], problems)

    def test_the_three_empty_devtools_stubs_of_a_release_pass(self):
        # The release's ArrowTrial, DebugTriggers and FaultInjection (src/release) are in com.reau.app.devtools, which is not the refused debug package.
        stubs = ["com/reau/app/devtools/ArrowTrial", "com/reau/app/devtools/DebugTriggers", "com/reau/app/devtools/FaultInjection"]
        self.assertEqual([], self.shape(classes=self.GOOD_CLASSES + stubs))
        self.assertEqual([], apk_audit.release_shape(xmltree(RELEASE_COMPONENTS), self.GOOD_CLASSES + stubs, RELEASE_ENTRIES,
                                                       [b"dex\n035 swallowDecoderOutput muteEncoderOutput"]))

    def test_any_other_class_of_the_devtools_package_fails_the_real_fault_enum_and_a_new_one_included(self):
        for name in ("com/reau/app/devtools/FaultInjection$Fault", "com/reau/app/devtools/ArrowTrial$Companion",
                     "com/reau/app/devtools/DebugTriggers$rows$1", "com/reau/app/devtools/Other"):
            self.assertEqual([f"classe que só o debug tem: {name}"], self.shape(classes=self.GOOD_CLASSES + [name]), name)

    def test_the_real_debug_code_under_the_stubs_names_fails_by_its_texts(self):
        for text in (b"DECODER_STALL", b"ENCODER_MUTE", b"FOCUS_CYCLE", b"debug trigger fired: x", b"fault injection armed"):
            problems = apk_audit.release_shape(xmltree(RELEASE_COMPONENTS), self.GOOD_CLASSES, RELEASE_ENTRIES, [b"dex\n035 " + text])
            self.assertEqual(1, len(problems), text)
            self.assertIn("texto que só o código de debug de verdade tem", problems[0])

    def test_any_compose_tooling_class_fails_the_preview_annotations_included(self):
        for name in ("androidx/compose/ui/tooling/PreviewActivity", "androidx/compose/ui/tooling/ComposeViewAdapter",
                     "androidx/compose/ui/tooling/data/Group", "androidx/compose/ui/tooling/preview/Preview",
                     "androidx/compose/ui/tooling/preview/Preview$Container"):
            self.assertEqual([f"classe que só o debug tem: {name}"], self.shape(classes=self.GOOD_CLASSES + [name]), name)

    def test_the_compose_test_library_fails(self):
        for name in ("androidx/compose/ui/test/junit4/ComposeTestRule", "androidx/compose/ui/test/manifest/R"):
            self.assertEqual([f"classe que só o debug tem: {name}"], self.shape(classes=[name]), name)

    def test_the_preview_library_marker_in_the_zip_fails(self):
        problems = self.shape(entries=RELEASE_ENTRIES + ["androidx/compose/ui/tooling/preview/x.kotlin_module"])
        self.assertEqual(["arquivo que só o debug tem: androidx/compose/ui/tooling/preview/x.kotlin_module"], problems)

    def test_a_debug_only_file_in_the_zip_fails(self):
        problems = self.shape(entries=RELEASE_ENTRIES + ["androidx/compose/ui/tooling/data/x.kotlin_module"])
        self.assertEqual(["arquivo que só o debug tem: androidx/compose/ui/tooling/data/x.kotlin_module"], problems)

    def test_a_component_outside_the_list_fails_exported_or_not(self):
        for exported in (True, False):
            problems = self.shape(components=RELEASE_COMPONENTS + [("activity", "com.reau.app.debug.CrashTestActivity", exported, False)])
            self.assertEqual(["componente fora da lista (activity): com.reau.app.debug.CrashTestActivity"], problems)

    def test_a_listed_component_that_became_exported_fails(self):
        for name in ("com.reau.app.service.ProjectionService", "androidx.core.content.FileProvider"):
            components = [(t, n, True if n == name else e, f) for t, n, e, f in RELEASE_COMPONENTS]
            self.assertEqual([f"componente exportado que não devia ser ({dict((c[1], c[0]) for c in components)[name]}): {name}"],
                             self.shape(components=components), name)

    def test_a_service_exported_by_its_intent_filter_alone_fails(self):
        components = [(t, n, None if n.endswith("ProjectionService") else e, True if n.endswith("ProjectionService") else f)
                      for t, n, e, f in RELEASE_COMPONENTS]
        self.assertEqual(["componente exportado que não devia ser (service): com.reau.app.service.ProjectionService"],
                         self.shape(components=components))

    def test_the_main_activity_must_stay_exported(self):
        components = [(t, n, False if n.endswith("MainActivity") else e, f) for t, n, e, f in RELEASE_COMPONENTS]
        self.assertEqual(["componente que devia ser exportado e não é (activity): com.reau.app.MainActivity"],
                         self.shape(components=components))

    def test_a_missing_baseline_profile_fails(self):
        problems = self.shape(entries=["classes.dex"])
        self.assertEqual(["falta o perfil de base (assets/dexopt/baseline.prof)"], problems)

    def test_the_exported_list_is_the_owners(self):
        self.assertEqual({"com.reau.app.MainActivity", "com.reau.app.service.QuickStartTile",
                          "androidx.profileinstaller.ProfileInstallReceiver"}, set(apk_audit.EXPORTED_COMPONENTS))


if __name__ == "__main__":
    unittest.main()
