"""Audita um APK pronto antes de instalar no celular e compara com a linha de base guardada no repo.

Olha o que o APK traz de fato, não o código-fonte:
  - SHA-256, tamanho e certificado (apksigner);
  - permissões do manifesto (aapt2);
  - bibliotecas empacotadas (META-INF/*.version e *.properties da raiz, que cada biblioteca do Google/AndroidX deixa);
  - bibliotecas nativas (lib/<abi>/*.so);
  - hosts de URLs escritas nos .dex (o app e todas as bibliotecas).
Confere também (28 a 30/10/2026, plano 20) que as bibliotecas nativas de arm64-v8a e x86_64 carregam num aparelho com páginas de 16 KB
(scripts/elf_16kb_check.py): vale para o APK de debug também, e uma biblioteca que não passa conta como problema como a forma de release.
Marca em destaque o que lembra rastreio, anúncio, cobrança ou atualizador (Firebase, Crashlytics, ads, billing...).

Confere também a "forma de release" do APK (28/10/2026), que não vem da linha de base e sim do código deste script: o APK
não é depurável; não traz nenhuma classe de com/reau/app/debug/, nem de androidx/compose/ui/tooling/ (nem as
anotações @Preview: a biblioteca ui-tooling-preview é compileOnly) nem de androidx/compose/ui/test; só declara os componentes da lista
(EXPORTED_COMPONENTS e UNEXPORTED_COMPONENTS), e só os de EXPORTED_COMPONENTS são exportados; e traz o perfil de base
(assets/dexopt/baseline.prof). Num APK de debug essa conferência não vale: use --apk-debug.

Sai com 1 se aparecer permissão, biblioteca, .so ou host que a linha de base não tem (algo novo entrou no APK: olhar
antes de instalar) ou se a forma de release falhar; 0 se nada mudou; 2 se faltou ferramenta ou arquivo. Uma mudança
conferida e aceita entra na linha de base com --gravar (que se recusa a gravar um APK de forma errada).

Uso (da raiz do repo):
    python scripts/apk_audit.py app/build/outputs/apk/debug/app-debug.apk
    python scripts/apk_audit.py <apk> --gravar        # aceita o APK atual como nova linha de base
    python scripts/apk_audit.py <apk de debug> --apk-debug   # sem a conferência da forma de release
O SDK vem de $ANDROID_HOME ou do sdk.dir do local.properties; o Java do apksigner, de $JAVA_HOME ou do PATH.
"""
from __future__ import annotations

import argparse
import glob
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import zipfile

import elf_16kb_check

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
            # The old Lite edition (id com.reau.app.lite, until 0.2.0) named its own permission after that id: the same one. Kept so an old APK can still be audited.
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


# --- A forma de release (28/10/2026) -----------------------------------------------------------------------------------
# Os componentes que o manifesto do APK de produção declara, lidos do manifesto real do app único (0.3.0; até 0.2.0 eram duas
# edições com os mesmos nomes de classe). Desde 03/11/2026 o app não tem serviço de acessibilidade: um APK que ainda o declare falha aqui
# ("componente fora da lista"). Exportados: a tela inicial (ícone), o bloco das Configurações rápidas e o
# receptor do perfil de base (protegido pela permissão DUMP, só o shell chega nele). Os outros nunca são exportados.
EXPORTED_COMPONENTS = frozenset({
    "com.reau.app.MainActivity",
    "com.reau.app.service.QuickStartTile",
    "androidx.profileinstaller.ProfileInstallReceiver",
})
UNEXPORTED_COMPONENTS = frozenset({
    "com.reau.app.service.ProjectionService",
    "androidx.core.content.FileProvider",
    "androidx.startup.InitializationProvider",
})
# Classes (nomes internos, com "/") que um APK de produção não pode ter: as telas de teste do debug e as ferramentas do Compose.
# Nada de androidx/compose/ui/tooling/, nem as anotações @Preview de .../tooling/preview/ (a ui-tooling-preview só entra no debug).
FORBIDDEN_CLASS_PREFIXES = ("com/reau/app/debug/", "androidx/compose/ui/tooling/", "androidx/compose/ui/test")
BASELINE_PROFILE = "assets/dexopt/baseline.prof"
COMPONENT_TAGS = ("activity", "activity-alias", "service", "receiver", "provider")


def _uleb(data: bytes, pos: int) -> tuple[int, int]:
    value = shift = 0
    while True:
        byte = data[pos]
        pos += 1
        value |= (byte & 0x7F) << shift
        shift += 7
        if not byte & 0x80:
            return value, pos


def dex_classes(dex: bytes) -> list[str]:
    """Os tipos que o .dex declara (type_ids), como nomes internos: "com/reau/app/debug/Foo". ValueError se não for um .dex."""
    if len(dex) < 0x70 or not dex.startswith(b"dex\n"):
        raise ValueError("não é um arquivo .dex")
    strings_size, strings_off, types_size, types_off = struct.unpack_from("<IIII", dex, 0x38)
    names = []
    try:
        for i in range(types_size):
            (string_index,) = struct.unpack_from("<I", dex, types_off + 4 * i)
            if string_index >= strings_size:
                raise ValueError("índice de texto fora da tabela")
            (data_off,) = struct.unpack_from("<I", dex, strings_off + 4 * string_index)
            length, start = _uleb(dex, data_off)
            descriptor = dex[start:start + length].decode("utf-8", "replace")
            if descriptor.startswith("L") and descriptor.endswith(";"):
                names.append(descriptor[1:-1])
    except (struct.error, IndexError) as e:
        raise ValueError(f".dex truncado ou malformado: {e}") from e
    return names


def apk_classes(apk: str) -> list[str]:
    classes: list[str] = []
    with zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if re.fullmatch(r"classes\d*\.dex", name):
                classes += dex_classes(z.read(name))
    return classes


def manifest_text(aapt2: str, apk: str) -> str:
    return subprocess.run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk],
                          capture_output=True, text=True, check=True).stdout


def parse_manifest(text: str) -> dict:
    """O manifesto como `aapt2 dump xmltree` o imprime: {"package", "debuggable", "components": [(tag, nome, exportado)]}.
    "exportado" é o efetivo: o atributo, ou, sem ele, ter um intent-filter (como o Android decide; provedor: nunca)."""
    root = None
    stack: list[tuple[int, dict]] = []
    for line in text.splitlines():
        element = re.match(r"^(\s*)E: ([\w:.-]+) \(line=\d+\)", line)
        if element:
            indent = len(element.group(1))
            node = {"tag": element.group(2), "attrs": {}, "children": []}
            while stack and stack[-1][0] >= indent:
                stack.pop()
            if stack:
                stack[-1][1]["children"].append(node)
            elif root is None:
                root = node
            stack.append((indent, node))
            continue
        attr = re.match(r"^\s*A: (.*)$", line)
        if attr and stack:
            key, _, rest = attr.group(1).partition("=")
            key = re.sub(r"\(0x[0-9a-fA-F]+\)$", "", key).rsplit(":", 1)[-1]
            quoted = re.match(r'"([^"]*)"', rest)
            stack[-1][1]["attrs"][key] = quoted.group(1) if quoted else (rest.split() or [""])[0]
    if root is None or root["tag"] != "manifest":
        raise ValueError("manifesto ilegível (sem o elemento manifest)")
    package = root["attrs"].get("package", "")
    application = next((c for c in root["children"] if c["tag"] == "application"), None)
    if application is None:
        raise ValueError("manifesto sem o elemento application")
    components = []
    for child in application["children"]:
        if child["tag"] not in COMPONENT_TAGS:
            continue
        name = child["attrs"].get("name", "")
        if name.startswith("."):
            name = package + name
        elif name and "." not in name:
            name = f"{package}.{name}"
        explicit = child["attrs"].get("exported")
        has_filter = any(c["tag"] == "intent-filter" for c in child["children"])
        exported = (explicit == "true") if explicit is not None else (has_filter and child["tag"] != "provider")
        components.append((child["tag"], name, exported))
    return {"package": package, "debuggable": application["attrs"].get("debuggable") in ("true", "0xffffffff"),
            "components": components}


def release_shape(manifest: str, classes: list[str], entries: list[str]) -> list[str]:
    """O que o APK tem de errado para ser o de produção; vazio quando está certo. Puro: recebe o manifesto, as classes e
    os nomes do zip."""
    problems = []
    try:
        parsed = parse_manifest(manifest)
    except ValueError as e:
        return [f"manifesto: {e}"]
    if parsed["debuggable"]:
        problems.append("o APK é depurável (android:debuggable=true)")
    for tag, name, exported in parsed["components"]:
        if name not in EXPORTED_COMPONENTS and name not in UNEXPORTED_COMPONENTS:
            problems.append(f"componente fora da lista ({tag}): {name}")
        elif exported and name not in EXPORTED_COMPONENTS:
            problems.append(f"componente exportado que não devia ser ({tag}): {name}")
        elif not exported and name in EXPORTED_COMPONENTS:
            problems.append(f"componente que devia ser exportado e não é ({tag}): {name}")
    for name in sorted(set(classes)):
        if name.startswith(FORBIDDEN_CLASS_PREFIXES):
            problems.append(f"classe que só o debug tem: {name}")
    for entry in entries:
        if entry.startswith(FORBIDDEN_CLASS_PREFIXES):
            problems.append(f"arquivo que só o debug tem: {entry}")
    if BASELINE_PROFILE not in entries:
        problems.append(f"falta o perfil de base ({BASELINE_PROFILE})")
    return problems


def native_16kb(apk: str) -> list[str]:
    """As bibliotecas nativas do APK que não carregam com páginas de 16 KB (vazio quando todas carregam ou não há nenhuma)."""
    found = []
    try:
        libs = elf_16kb_check.libraries(apk)
    except (zipfile.BadZipFile, OSError) as e:
        return [f"não deu para ler as bibliotecas nativas: {e}"]
    for name, data in libs:
        try:
            issues = elf_16kb_check.problems(elf_16kb_check.program_headers(data))
        except (ValueError, struct.error) as e:
            issues = [f"cabeçalho ilegível ({e})"]
        found += [f"{name.split('!', 1)[-1]}: {issue}" for issue in issues]
    return found


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("apk")
    ap.add_argument("--gravar", action="store_true", help="grava o APK atual como linha de base")
    ap.add_argument("--apk-debug", action="store_true", help="o APK é de debug: pula a conferência da forma de release")
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

    shape: list[str] = []
    if not args.apk_debug:
        try:
            with zipfile.ZipFile(args.apk) as z:
                entries = z.namelist()
            shape = release_shape(manifest_text(aapt2, args.apk), apk_classes(args.apk), entries)
        except (ValueError, subprocess.CalledProcessError, zipfile.BadZipFile) as e:
            shape = [f"não deu para ler o APK: {e}"]
        if shape:
            print("\nFORMA DE RELEASE ERRADA (o APK de produção não pode ser assim):")
            for problem in shape:
                print(f"  x {problem}")
        else:
            print("Forma de release certa: não depurável, sem classe de debug, componentes só os da lista, perfil de base presente.")

    pages = native_16kb(args.apk)
    if pages:
        print("\nBIBLIOTECA NATIVA QUE NÃO CARREGA COM PÁGINAS DE 16 KB:")
        for problem in pages:
            print(f"  x {problem}")
    else:
        print("Bibliotecas nativas (arm64-v8a, x86_64) carregam com páginas de 16 KB.")

    flagged = [f"{k}: {v}" for k, vs in current.items() for v in vs if SUSPICIOUS.search(v)]
    if flagged:
        print("\nPara olhar (rastreio, anúncio, cobrança, atualizador):")
        for f in flagged:
            print(f"  ! {f}")

    if args.gravar:
        if shape or pages:
            print("\nNada gravado: o APK não tem a forma de release ou tem biblioteca nativa que não carrega com 16 KB.")
            return 1
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
    return 1 if shape or pages else 0


if __name__ == "__main__":
    sys.exit(main())
