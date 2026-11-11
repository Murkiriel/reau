"""Mutation runner for REau: applies one change at a time to a source file, runs a test command, restores.

Usage (from a script of your own):  run(mutations, command)
  mutations: list of (name, path relative to the repository root, old text, new text)
  command:   list passed to subprocess, run from the REau root; exit code != 0 means the tests caught it.
Returns how many mutations the tests caught. The curated list the weekly workflow runs is scripts/dev/mutacoes.py.
"""
import os
import signal
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
# The file as it was, kept here while a mutation is applied: a run killed in the middle (not by SIGTERM, which unwinds) leaves a mutated
# source file, and the next run puts it back first ([recover]). Under build/, which is not versioned.
PENDING = ROOT / "build" / "mutacoes_pendentes"
# The most one mutation's test run may take: a Gradle that hangs would otherwise take the whole job with it.
TIMEOUT_S = 900
ENV = {**os.environ, "PYTHONIOENCODING": "utf-8"}  # Gradle needs a JDK 21: JAVA_HOME set, or java on the PATH
sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def _write_durably(path, data):
    """Writes [data] and forces it to the disk: a machine that goes down right after (a crash, an update that reboots it) must find either
    the old file or the new one, never a file the disk had not got to (26/10/2026: six sources came back as garbage after a reboot in the
    middle of a mutation run)."""
    with open(path, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())


def recover(root=None, pending=None):
    """Puts back the files an earlier run left mutated (killed before it could restore them); returns how many."""
    root, pending = root or ROOT, pending or PENDING
    if not pending.is_dir():
        return 0
    restored = 0
    for meta in sorted(pending.glob("*.path")):
        original = meta.with_suffix(".orig")
        rel = meta.read_text(encoding="utf-8").strip()
        # A trace cut short (the run died while writing it) restores nothing: the source file had not been touched yet.
        if rel and original.is_file() and original.stat().st_size > 0:
            _write_durably(root / rel, original.read_bytes())
            restored += 1
        meta.unlink()
        original.unlink(missing_ok=True)
    return restored


def _unwind(signum, frame):
    sys.exit(128 + signum)  # raises SystemExit: the `finally` of [run] puts the file back


def run(mutations, command, tail=3):
    for signum in (signal.SIGTERM, signal.SIGINT):
        signal.signal(signum, _unwind)
    caught = 0
    for name, rel, old, new in mutations:
        path = ROOT / rel
        raw = path.read_bytes()
        text = raw.decode("utf-8")
        # With core.autocrlf the working tree may end its lines in CRLF: a snippet of several lines is tried that way too.
        if text.count(old) != 1 and "\n" in old and text.count(old.replace("\n", "\r\n")) == 1:
            old, new = old.replace("\n", "\r\n"), new.replace("\n", "\r\n")
        if text.count(old) != 1:
            print(f"{name}: TRECHO NÃO ACHADO ({text.count(old)}x)", flush=True)
            continue
        PENDING.mkdir(parents=True, exist_ok=True)
        key = rel.replace("/", "__")
        _write_durably(PENDING / f"{key}.orig", raw)
        _write_durably(PENDING / f"{key}.path", rel.encode("utf-8"))
        _write_durably(path, text.replace(old, new).encode("utf-8"))
        timed_out = False
        try:
            done = subprocess.run(command, cwd=ROOT, env=ENV, capture_output=True, text=True, encoding="utf-8",
                                  errors="replace", timeout=TIMEOUT_S)
        except subprocess.TimeoutExpired:
            timed_out = True
        finally:
            _write_durably(path, raw)
            (PENDING / f"{key}.path").unlink(missing_ok=True)
            (PENDING / f"{key}.orig").unlink(missing_ok=True)
        if timed_out:
            # A run that hangs proves nothing about the tests: not counted as caught, and said, so the list gets a look.
            print(f"{name}: NÃO TERMINOU em {TIMEOUT_S} s (não conta como pega)", flush=True)
            continue
        out = (done.stdout + done.stderr).strip().splitlines()
        if "NÃO COMPILOU" in "\n".join(out):
            print(f"{name}: NÃO COMPILOU (mutação inválida)\n   " + "\n   ".join(out[-tail:]), flush=True)
        elif done.returncode:
            caught += 1
            why = [line for line in out if "FALHOU" in line or "AssertionError" in line or "expected" in line][:2]
            print(f"{name}: PEGA  {' | '.join(w.strip()[:150] for w in why)}", flush=True)
        else:
            print(f"{name}: NÃO PEGA  {out[-1] if out else ''}", flush=True)
    print(f"{caught} de {len(mutations)} mutações pegas", flush=True)
    return caught
