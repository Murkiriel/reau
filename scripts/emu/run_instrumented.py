"""Builds the debug and test APKs and runs instrumented tests on the EMULATOR ONLY.

Every adb call names one emulator (emulator-5554, or the one REAU_EMU_SERIAL names). Gradle only assembles: its connected*/install* tasks pick whatever device is
attached, and reinstalling on a real phone wipes that app's data, so they are never used here.

Usage:
    run_instrumented.py                       whole suite, one process PER CLASS (see below)
    run_instrumented.py MainScreenTest ...    these classes (short name, or fully qualified), one process each
    run_instrumented.py --no-build ...        skip the Gradle build (APKs already built)

The full output of each run is written to emu_out/ (ignored by git). Exit code 0 only when every run passed.
Tests skipped by an assumption are listed as PULADO, never counted as passed.

The Android SDK comes from ANDROID_HOME (or ANDROID_SDK_ROOT), else from sdk.dir in local.properties; Gradle needs a
JDK 21 (JAVA_HOME set, or java on the PATH).

One process per class is the only way (the whole suite in a single process was retired on 28/10/2026): in one process the
Android 16 emulator wore down, compositor timing tests failed only there, an activity never reached DESTROYED, twice
the emulator's own system crashed mid-run (SystemUI's FinalizerWatchdog destroying a HardwareRenderer, load 14), and 13
tests failed from the language and the state another class left behind. The same classes one per process: all green, the
encoder-bound ones skipped (EncoderCapacity).
"""
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "emu_out"
# Which emulator: emulator-5554 unless REAU_EMU_SERIAL names another one (run_instrumented_duo.py runs two at once).
SERIAL = os.environ.get("REAU_EMU_SERIAL", "emulator-5554")
if not SERIAL.startswith("emulator-"):
    sys.exit(f"REAU_EMU_SERIAL={SERIAL}: only emulators, never a phone")
# One app since 0.3.0 (id com.reau.app); its instrumented tests are all in src/androidTest.
PACKAGE = "com.reau.app"
APP_ID = PACKAGE
RUNNER = f"{APP_ID}.test/{PACKAGE}.KeepAppStateRunner"
APKS = (
    "app/build/outputs/apk/debug/app-debug.apk",
    "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",
)


def test_roots():
    """Where the instrumented tests are: the androidTest folder, and the shared one."""
    roots = [ROOT / "app/src/androidTest/java", ROOT / "app/src/sharedTest/java"]  # sharedTest: also runs on the JVM (FakeDashPeer, DashSessionFakeDashTest)
    return [r for r in roots if r.is_dir()]


def sdk_dir() -> Path:
    """The Android SDK: ANDROID_HOME or ANDROID_SDK_ROOT, else sdk.dir in local.properties (a path even if missing)."""
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(name):
            return Path(os.environ[name])
    props = ROOT / "local.properties"
    if props.is_file():
        for line in props.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                return Path(line[len("sdk.dir="):].replace("\\:", ":").replace("\\\\", "\\"))
    return ROOT / "android-sdk"


EXE = ".exe" if os.name == "nt" else ""
ADB = str(sdk_dir() / "platform-tools" / f"adb{EXE}")
GRADLEW = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
ENV = dict(os.environ)


def adb(*args, timeout=300):
    return subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True, encoding="utf-8",
                          errors="replace", timeout=timeout)


def build():
    done = subprocess.run([str(GRADLEW), ":app:assembleDebug", ":app:assembleDebugAndroidTest",
                           "--console=plain", "-q"], cwd=ROOT, env=ENV, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")
    if done.returncode:
        text = done.stdout + done.stderr
        errors = [line for line in text.splitlines() if line.startswith("e: ")]
        sys.exit("NÃO COMPILOU:\n" + ("\n".join(errors[:20]) if errors else text.strip()[-2000:]))


def install():
    for apk in APKS:
        done = adb("install", "-r", "-t", str(ROOT / apk))
        if "Success" not in done.stdout:
            sys.exit(f"NÃO INSTALOU {apk}: " + (done.stdout + done.stderr).strip()[-400:])


def qualified(name):
    """Short class names are looked up under app/src/androidTest; a name with a dot is taken as given."""
    if "." in name:
        return name
    hits = [(root, f) for root in test_roots() for f in root.rglob(f"{name}.kt")]
    if len(hits) != 1:
        sys.exit(f"classe de teste '{name}': {len(hits)} arquivos encontrados")
    root, file = hits[0]
    return ".".join(file.relative_to(root).with_suffix("").parts)


def skipped():
    """Tests skipped by an assumption since the last `logcat -c`: `am instrument` counts them in its "OK (n tests)"
    and never names them, so they are read from the runner's own log lines."""
    log = adb("logcat", "-d", "-s", "TestRunner:E").stdout.splitlines()
    names = [line.split("assumption failed: ", 1)[1].strip() for line in log if "assumption failed: " in line]
    reasons = [line.split("AssumptionViolatedException: ", 1)[1].strip() for line in log if "AssumptionViolatedException: " in line]
    return list(zip(names, reasons + [""] * (len(names) - len(reasons))))


def run(label, extra):
    adb("logcat", "-c")
    out = adb("shell", "am", "instrument", "-w", *extra, RUNNER, timeout=3600).stdout
    skips = skipped()
    if skips:
        out += "\nPULADOS (assumption, não aprovados):\n" + "\n".join(f"  {n}: {r}" for n, r in skips) + "\n"
    OUT.mkdir(exist_ok=True)
    (OUT / f"{label}.txt").write_text(out, encoding="utf-8")
    ok = [line.strip() for line in out.splitlines() if line.strip().startswith("OK (")]
    if ok:
        print(f"{label}: {ok[-1]}" + (f", {len(skips)} PULADO(S)" if skips else ""), flush=True)
        for n, r in skips:
            print(f"   pulado: {n}: {r}", flush=True)
        return True
    count = [line.strip() for line in out.splitlines() if "Tests run" in line]
    print(f"{label}: FALHOU {count[-1] if count else ''}  [emu_out/{label}.txt]", flush=True)
    print(out.strip()[-1500:], flush=True)
    return False


def all_classes():
    return sorted(".".join(f.relative_to(root).with_suffix("").parts) for root in test_roots() for f in root.rglob("*Test.kt"))


def main():
    args = sys.argv[1:]
    for arg in args:
        if arg.startswith("-") and arg != "--no-build":
            sys.exit(f"opção desconhecida: {arg} (o modo de um processo só foi aposentado: é sempre uma classe por processo)")
    devices = subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout
    if SERIAL not in devices:
        sys.exit(f"{SERIAL} não está no ar:\n{devices}")
    if "--no-build" in args:
        args.remove("--no-build")
    else:
        build()
    install()
    if not args:
        passed = [run(name.split(".")[-1], ["-e", "class", name]) for name in all_classes()]
        print(f"suite: {sum(passed)} de {len(passed)} classes verdes", flush=True)
    else:
        passed = [run(name.split(".")[-1], ["-e", "class", qualified(name)]) for name in args]
    sys.exit(0 if all(passed) else 1)


if __name__ == "__main__":
    main()
