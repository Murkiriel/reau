"""Runs REau's JVM unit tests, optionally only some classes, and says plainly how it ended.

Usage:  python scripts/dev/run_unit.py [TestClass ...]
Gradle needs a JDK 21: JAVA_HOME set, or java on the PATH.
Exit code 0 = green. Prints "NÃO COMPILOU" when the build broke before any test ran (a mutation runner must not
count that as a test catching the change), or one "FALHOU: <test>" line per failed test.
"""
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ENV = dict(os.environ)
GRADLEW = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def main(classes):
    command = [str(GRADLEW), ":app:testCompletaDebugUnitTest", "--console=plain"]
    for name in classes:
        command += ["--tests", f"*.{name}"]
    done = subprocess.run(command, cwd=ROOT, env=ENV, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", stdin=subprocess.DEVNULL)
    out = done.stdout + done.stderr
    if done.returncode == 0:
        print("VERDE")
        return 0
    failed = re.findall(r"^(\w+ > .+) FAILED$", out, flags=re.MULTILINE)
    if not failed:
        print("NÃO COMPILOU")
        print("\n".join(line for line in out.splitlines() if line.startswith("e: "))[:1500])
        return 2
    for name in failed:
        print(f"FALHOU: {name}")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
