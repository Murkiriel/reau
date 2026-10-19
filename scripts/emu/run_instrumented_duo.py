"""Runs the instrumented suite split between TWO emulators at once.

    run_instrumented_duo.py [--no-build]

Builds once, then runs run_instrumented.py on emulator-5554 (the main one, AVD $REAU_AVD_MAIN, by default REau_Test) and
on emulator-5556 (AVD $REAU_AVD_SECOND, by default REau_Test2) at the same time, each with its share of the classes, one process per class as always. The classes that
measure time (video, compositor, end-to-end streams, the dash's watchdog) stay on the main emulator, which has more
memory and cores; the rest is balanced by each class's last duration (emu_out/<Class>.txt, "Time:"). The Gradle
daemons are stopped after the build, to leave memory for the two emulators.

An emulator that dies during its half (the main one did, several times, right after VideoLoopThermalTest, which now
runs last) is started again as by hand, and the classes of its half that had not passed run once more; the
summary shows both runs, and the output of the second goes to emu_out/_duo_<serial>_depois_da_queda.txt.

Never a phone: both serials are emulators, and run_instrumented.py refuses any other.
Exit code 0 only when both halves passed. Each half's output also goes to emu_out/_duo_<serial>.txt.
"""
import os
import re
import subprocess
import sys
import threading
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
import run_instrumented as single  # noqa: E402

MAIN, SECOND = "emulator-5554", "emulator-5556"
OUT = ROOT / "emu_out"

# Classes whose assertions depend on timing or on the encoder: they stay on the main emulator.
TIMED = {
    "DashCompositorTest", "VideoLoopTest", "VideoLoopThermalTest", "VideoRecordingTest", "ProjectionEndToEndTest",
    "DashSessionFakeDashTest", "DashSessionCardsTest", "CardsEndToEndTest", "ManeuverEndToEndTest",
    "ScreenRereadEndToEndTest", "CallKeysEndToEndTest", "AaReceiverTest", "VideoDecoderTest", "AaVideoSinkTest",
    "SessionReplayTest", "AndroidAutoTest", "DashStatsOnDeviceTest", "DashTakenTest", "CardEndToEndTest",
}


def seconds(name):
    """The class's last duration from its emu_out file, or a middling guess."""
    f = OUT / f"{name}.txt"
    if f.exists():
        m = re.findall(r"^Time: ([\d.,]+)", f.read_text(encoding="utf-8", errors="replace"), re.M)
        if m:
            return float(m[-1].replace(".", "").replace(",", ".")) if "," in m[-1] else float(m[-1])
    return 30.0


# Of those, the ones that measure gaps and pace run last on the main emulator: by then the second one is done and the
# machine is free (the five-minute screen-off stream saw a 1.6 s gap while both ran). The five-minute stream itself
# goes at the very end: the main emulator died right after it several times and took the classes after it down too.
LAST = ["VideoLoopTest", "DashCompositorTest", "ProjectionEndToEndTest", "VideoLoopThermalTest"]

EMULATOR = str(single.sdk_dir() / "emulator" / f"emulator{single.EXE}")
AVDS = {MAIN: ["-avd", os.environ.get("REAU_AVD_MAIN", "REau_Test")],
        SECOND: ["-avd", os.environ.get("REAU_AVD_SECOND", "REau_Test2"), "-port", "5556"]}
BOOTED_S = 150  # the emulator is taken as up once it has been on this long (timings go wrong before)


def split(classes):
    main, second = [], []
    load = {MAIN: 0.0, SECOND: 0.0}
    for c in classes:
        if c.split(".")[-1] in TIMED:
            main.append(c)
            load[MAIN] += seconds(c.split(".")[-1])
    for c in sorted((c for c in classes if c.split(".")[-1] not in TIMED), key=lambda c: -seconds(c.split(".")[-1])):
        target = SECOND if load[SECOND] <= load[MAIN] else MAIN
        (second if target == SECOND else main).append(c)
        load[target] += seconds(c.split(".")[-1])
    main.sort(key=lambda c: LAST.index(c.split(".")[-1]) if c.split(".")[-1] in LAST else -1)
    return main, second, load


def left_over(out, ran):
    """The classes of [ran] with no "<Class>: OK (" line in a half's output: the ones to run again after a crash."""
    passed = {line.split(":", 1)[0].strip() for line in out.splitlines() if ": OK (" in line}
    return [c for c in ran if c.split(".")[-1] not in passed]


def gone(devices, serial):
    """Whether [serial] is missing from `adb devices`, or there but not usable (offline)."""
    for line in devices.splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[0] == serial:
            return parts[1] != "device"
    return True


def emulator_command(serial):
    """The emulator started as by hand (cold boot, explicit DNS: see the emulator notes in docs)."""
    return [EMULATOR, *AVDS[serial], "-no-snapshot-load", "-dns-server", "8.8.8.8,1.1.1.1", "-no-boot-anim"]


def restart(serial):
    """Starts [serial] again and waits until it has been up BOOTED_S seconds; False if it never came (10 minutes)."""
    subprocess.Popen(emulator_command(serial), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    deadline = time.time() + 600
    while time.time() < deadline:
        up = subprocess.run([single.ADB, "-s", serial, "shell", "cat", "/proc/uptime"], capture_output=True, text=True)
        try:
            if float(up.stdout.split()[0]) > BOOTED_S:
                return True
        except (IndexError, ValueError):
            pass
        time.sleep(5)
    return False


def devices():
    return subprocess.run([single.ADB, "devices"], capture_output=True, text=True).stdout


def run_half(serial, classes, results, label=""):
    env = {**os.environ, "REAU_EMU_SERIAL": serial, "PYTHONIOENCODING": "utf-8"}
    done = subprocess.run([sys.executable, "-B", "-u", str(HERE / "run_instrumented.py"), "--no-build", *classes],
                          cwd=ROOT, env=env, capture_output=True, text=True, encoding="utf-8", errors="replace")
    OUT.mkdir(exist_ok=True)
    (OUT / f"_duo_{serial}{label}.txt").write_text(done.stdout + done.stderr, encoding="utf-8")
    results[serial] = done


def after_a_crash(serial, first, ran):
    """An emulator that died mid-half is started again and runs what had not passed; its answer, or None (no crash)."""
    if first.returncode == 0 or not gone(devices(), serial):
        return None
    again = left_over(first.stdout, ran)
    print(f"{serial} caiu; religando e rodando de novo {len(again)} classe(s): {', '.join(c.split('.')[-1] for c in again)}",
          flush=True)
    if not restart(serial):
        print(f"{serial} não voltou em 10 minutos", flush=True)
        return first
    results = {}
    run_half(serial, again, results, label="_depois_da_queda")
    return results[serial]


def main():
    attached = devices()
    for serial in (MAIN, SECOND):
        if gone(attached, serial):
            sys.exit(f"{serial} não está no ar:\n{attached}")
    if "--no-build" not in sys.argv[1:]:
        single.build()
        subprocess.run([str(single.GRADLEW), "--stop", "-q"], cwd=ROOT, env=single.ENV, capture_output=True)
    main_classes, second_classes, load = split(single.all_classes())
    print(f"{MAIN}: {len(main_classes)} classes (~{load[MAIN] / 60:.0f} min); "
          f"{SECOND}: {len(second_classes)} classes (~{load[SECOND] / 60:.0f} min)", flush=True)
    results = {}
    threads = [threading.Thread(target=run_half, args=(MAIN, main_classes, results)),
               threading.Thread(target=run_half, args=(SECOND, second_classes, results))]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    ran = {MAIN: main_classes, SECOND: second_classes}
    ok = True
    for serial in (MAIN, SECOND):
        done = results[serial]
        rerun = after_a_crash(serial, done, ran[serial])
        if rerun is not None and rerun is not done:
            print(f"=== {serial} (antes da queda)", flush=True)
            print("\n".join(line for line in done.stdout.splitlines() if ": OK (" in line or "FALHOU" in line), flush=True)
            done = rerun
        elif rerun is done:
            ok = False
        lines = [line for line in done.stdout.splitlines() if ": OK (" in line or "FALHOU" in line or "PULADO" in line
                 or "ABORTED" in line or "crashed" in line or line.startswith("suite")]
        print(f"=== {serial}", flush=True)
        print("\n".join(lines), flush=True)
        ok = ok and done.returncode == 0
    print("duo: " + ("tudo verde" if ok else "há falhas (ver acima e emu_out/_duo_*.txt)"), flush=True)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
