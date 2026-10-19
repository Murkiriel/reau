"""Mutation runner for REau: applies one change at a time to a source file, runs a test command, restores.

Usage (from a script of your own):  run(mutations, command)
  mutations: list of (name, path relative to the repository root, old text, new text)
  command:   list passed to subprocess, run from the REau root; exit code != 0 means the tests caught it.
"""
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ENV = {**os.environ, "PYTHONIOENCODING": "utf-8"}  # Gradle needs a JDK 21: JAVA_HOME set, or java on the PATH
sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def run(mutations, command, tail=3):
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
        path.write_bytes(text.replace(old, new).encode("utf-8"))
        try:
            done = subprocess.run(command, cwd=ROOT, env=ENV, capture_output=True, text=True, encoding="utf-8",
                                  errors="replace")
        finally:
            path.write_bytes(raw)
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
