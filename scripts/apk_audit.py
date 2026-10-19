"""Audita um APK pronto antes de instalar no celular e compara com a linha de base guardada no repo.

Olha o que o APK traz de fato, não o código-fonte:
  - SHA-256, tamanho e certificado (apksigner);
  - permissões do manifesto (aapt2);
  - bibliotecas empacotadas (META-INF/*.version e *.properties da raiz, que cada biblioteca do Google/AndroidX deixa);
  - bibliotecas nativas (lib/<abi>/*.so);
  - hosts de URLs escritas nos .dex (o app e todas as bibliotecas).
Marca em destaque o que lembra rastreio, anúncio, cobrança ou atualizador (Firebase, Crashlytics, ads, billing...).

Sai com 1 se aparecer permissão, biblioteca, .so ou host que a linha de base não tem (algo novo entrou no APK: olhar
antes de instalar); 0 se nada mudou; 2 se faltou ferramenta ou arquivo. Uma mudança conferida e aceita entra na
linha de base com --gravar.

Uso (da raiz do repo):
    python scripts/apk_audit.py app/build/outputs/apk/completa/debug/app-completa-debug.apk
    python scripts/apk_audit.py <apk> --gravar        # aceita o APK atual como nova linha de base
O SDK vem de $ANDROID_HOME ou do sdk.dir do local.properties; o Java do apksigner, de $JAVA_HOME ou do PATH.
"""
from __future__ import annotations

import argparse
import glob
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASELINE = os.path.join(ROOT, "scripts", "apk_audit_baseline.json")
SUSPICIOUS = re.compile(
    r"firebase|crashlytics|play-services-ads|admob|adservices|\bads\b|billing|appsflyer|facebook|onesignal|adjust"
    r"|sentry|bugsnag|amplitude|mixpanel|segment\.io|branch\.io|appcenter|installreferrer|REQUEST_INSTALL_PACKAGES"
    r"|AD_ID|ANSWER_PHONE_CALLS|READ_SMS|READ_CONTACTS|QUERY_ALL_PACKAGES",
    re.I,
)
URL_RE = re.compile(rb"https?://([A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9])")
# Placeholders and XML namespaces that every APK carries; not hosts the app talks to.
IGNORED_HOSTS = {"localhost", "127.0.0.1", "schemas.android.com", "www.w3.org", "ns.adobe.com", "xml.org",
                 "xmlpull.org", "apache.org", "www.apache.org", "purl.org", "example.com", "www.example.com"}


def find_sdk() -> str | None:
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(env) and os.path.isdir(os.environ[env]):
            return os.environ[env]
    props = os.path.join(ROOT, "local.properties")
    if os.path.exists(props):
        with open(props, encoding="utf-8") as fh:
            lines = fh.readlines()
        for line in lines:
            if line.startswith("sdk.dir="):
                path = line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\\\", "\\")
                if os.path.isdir(path):
                    return path
    return None


def build_tool(sdk: str, name: str) -> str | None:
    hits = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", name)))
    return hits[-1] if hits else None


def java_env() -> dict:
    env = dict(os.environ)
    if env.get("JAVA_HOME"):
        env["PATH"] = os.path.join(env["JAVA_HOME"], "bin") + os.pathsep + env.get("PATH", "")
    return env


def permissions(aapt2: str, apk: str) -> list[str]:
    out = subprocess.run([aapt2, "dump", "permissions", apk], capture_output=True, text=True, check=True).stdout
    found = set()
    for line in out.splitlines():
        m = re.search(r"name='([^']+)'", line)
        if line.strip().startswith("uses-permission") and m:
            # The Lite edition (id com.reau.app.lite) names its own permission after that id: the same one.
            found.add(m.group(1).replace("com.reau.app.lite.", "com.reau.app."))
    return sorted(found)


def certificate(apksigner: str, apk: str) -> str:
    r = subprocess.run([apksigner, "verify", "--print-certs", apk], capture_output=True, text=True, env=java_env())
    lines = [l for l in r.stdout.splitlines() if "certificate DN" in l or "SHA-256 digest" in l]
    return "\n".join(lines) if r.returncode == 0 else f"FALHOU apksigner: {r.stderr.strip()[:300]}"


def scan_zip(apk: str) -> tuple[list[str], list[str], list[str]]:
    libraries, natives, hosts = set(), set(), set()
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if name.startswith("META-INF/") and name.endswith(".version"):
                libraries.add(name[len("META-INF/"):-len(".version")])
            elif "/" not in name and name.endswith(".properties"):
                libraries.add(name[:-len(".properties")])
            elif name.startswith("lib/") and name.endswith(".so"):
                natives.add(os.path.basename(name))
            if re.fullmatch(r"classes\d*\.dex", name):
                for m in URL_RE.finditer(z.read(name)):
                    host = m.group(1).decode("ascii").lower()
                    if host not in IGNORED_HOSTS and "." in host:
                        hosts.add(host)
    return sorted(libraries), sorted(natives), sorted(hosts)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("apk")
    ap.add_argument("--gravar", action="store_true", help="grava o APK atual como linha de base")
    args = ap.parse_args()
    if not os.path.exists(args.apk):
        print(f"APK não existe: {args.apk}")
        return 2
    sdk = find_sdk()
    aapt2 = sdk and build_tool(sdk, "aapt2.exe" if os.name == "nt" else "aapt2")
    apksigner = sdk and build_tool(sdk, "apksigner.bat" if os.name == "nt" else "apksigner")
    if not aapt2 or not apksigner:
        print("Android SDK sem build-tools (aapt2/apksigner): defina ANDROID_HOME ou sdk.dir no local.properties")
        return 2

    with open(args.apk, "rb") as fh:
        data = fh.read()
    print(f"APK: {args.apk}\nTamanho: {len(data):,} bytes\nSHA-256: {hashlib.sha256(data).hexdigest()}")
    print(certificate(apksigner, args.apk))
    libraries, natives, hosts = scan_zip(args.apk)
    current = {"permissions": permissions(aapt2, args.apk), "libraries": libraries, "native_libs": natives,
               "hosts": hosts}
    print(f"Permissões {len(current['permissions'])}, bibliotecas {len(libraries)}, .so {len(natives)}, hosts {len(hosts)}")

    flagged = [f"{k}: {v}" for k, vs in current.items() for v in vs if SUSPICIOUS.search(v)]
    if flagged:
        print("\nPara olhar (rastreio, anúncio, cobrança, atualizador):")
        for f in flagged:
            print(f"  ! {f}")

    if args.gravar:
        with open(BASELINE, "w", encoding="utf-8", newline="\n") as fh:
            json.dump(current, fh, indent=2, ensure_ascii=False)
            fh.write("\n")
        print(f"\nLinha de base gravada: {os.path.relpath(BASELINE, ROOT)}")
        return 0
    if not os.path.exists(BASELINE):
        print("\nSem linha de base: rode com --gravar depois de conferir este APK")
        return 2
    with open(BASELINE, encoding="utf-8") as fh:
        base = json.load(fh)
    new = {k: sorted(set(v) - set(base.get(k, []))) for k, v in current.items()}
    gone = {k: sorted(set(base.get(k, [])) - set(v)) for k, v in current.items()}
    for k, vs in gone.items():
        for v in vs:
            print(f"  - saiu ({k}): {v}")
    if any(new.values()):
        print("\nNOVO em relação à linha de base (conferir antes de instalar):")
        for k, vs in new.items():
            for v in vs:
                print(f"  + {k}: {v}")
        return 1
    print("\nNada novo em relação à linha de base.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
