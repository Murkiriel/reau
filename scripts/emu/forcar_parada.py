"""A real `am force-stop` and the next projection telling the exit. EMULATOR ONLY.

`am force-stop` also kills the test process, so it cannot happen inside an instrumented test: this script starts the
`:exittest` process (ExitTestActivity, debug builds only), force-stops the app and then runs
ExitHistoryTest#anExplicitForceStopIsToldByTheNextProjection with the pid that died.

Usage:  forcar_parada.py [--no-build]
Exit code 0 only when the test passed.
"""
import sys
import time
from pathlib import Path

# Some Python installs do not put the script's folder on the path.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from run_instrumented import PACKAGE, SERIAL, adb, build, install, run

TEST = "com.reau.app.data.ExitHistoryTest#anExplicitForceStopIsToldByTheNextProjection"


def exittest_pid(timeout_s=15.0):
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        pid = adb("shell", "pidof", f"{PACKAGE}:exittest").stdout.strip()
        if pid:
            return pid
        time.sleep(0.2)
    return None


def main():
    args = sys.argv[1:]
    if "--no-build" in args:
        args.remove("--no-build")
    else:
        build()
    install()
    adb("shell", "am", "start", "-n", f"{PACKAGE}/.debug.ExitTestActivity")
    pid = exittest_pid()
    if not pid:
        sys.exit(f"o processo {PACKAGE}:exittest não subiu em {SERIAL}")
    adb("shell", "am", "force-stop", PACKAGE)
    print(f"force-stop com {PACKAGE}:exittest no pid {pid}", flush=True)
    sys.exit(0 if run("forcar_parada", ["-e", "forceStoppedPid", pid, "-e", "class", TEST]) else 1)


if __name__ == "__main__":
    main()
