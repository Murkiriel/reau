"""Confere se as bibliotecas nativas de um APK, AAR ou .so aguentam um aparelho com páginas de 16 KB.

O que olha, em cada .so (ELF de 32 ou 64 bits, little-endian):
  1. todo segmento PT_LOAD com alinhamento (p_align) de pelo menos 16 KB;
  2. em todo PT_LOAD, o deslocamento no arquivo e o endereço virtual com o mesmo resto na divisão por 16 KB;
  3. o fim do PT_GNU_RELRO: o linker do Android protege o RELRO por páginas, arredondando o fim para cima. Se o fim do RELRO não
     é múltiplo de 16 KB e ainda há bytes de outro segmento ou de dados gravaveis entre o fim dele e o fim da página, essa
     parte vira somente leitura e o aparelho falha. É o defeito do libconscrypt_jni.so 2.5.3 em arm64 (issue google/conscrypt#1413).
É uma conferência estática dos cabeçalhos, não o linker de verdade: a prova final é um aparelho ou uma imagem de 16 KB.

Num APK olha lib/arm64-v8a e lib/x86_64 (os ABIs de aparelhos de 16 KB); num AAR, jni/ dos mesmos ABIs; num .so, ele.
Sai com 1 se alguma biblioteca falhar, 0 se todas passarem, 2 se não achou nenhuma .so.

Uso (da raiz do repo):
    python scripts/elf_16kb_check.py app/build/outputs/apk/debug/app-debug.apk
    python scripts/elf_16kb_check.py conscrypt-android-2.6.1.aar libfoo.so
"""
from __future__ import annotations

import argparse
import struct
import sys
import zipfile

PAGE = 0x4000
PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552
ABIS_16KB = ("arm64-v8a", "x86_64")


def program_headers(data: bytes) -> list[dict]:
    """Os cabeçalhos de programa: tipo, deslocamento, endereço virtual, tamanho no arquivo, tamanho na memória, alinhamento."""
    if data[:4] != b"\x7fELF":
        raise ValueError("não é um ELF")
    if len(data) < 0x40:
        raise ValueError("ELF curto demais")
    is64 = data[4] == 2
    if data[5] != 1:
        raise ValueError("só ELF little-endian")
    if is64:
        phoff, = struct.unpack_from("<Q", data, 0x20)
        phentsize, phnum = struct.unpack_from("<HH", data, 0x36)
    else:
        phoff, = struct.unpack_from("<I", data, 0x1C)
        phentsize, phnum = struct.unpack_from("<HH", data, 0x2A)
    headers = []
    for i in range(phnum):
        at = phoff + i * phentsize
        if is64:
            p_type, _flags, offset, vaddr, _paddr, filesz, memsz, align = struct.unpack_from("<IIQQQQQQ", data, at)
        else:
            p_type, offset, vaddr, _paddr, filesz, memsz, _flags, align = struct.unpack_from("<IIIIIIII", data, at)
        headers.append({"type": p_type, "offset": offset, "vaddr": vaddr, "filesz": filesz, "memsz": memsz, "align": align})
    return headers


def problems(headers: list[dict]) -> list[str]:
    """O que impede a biblioteca de carregar com páginas de 16 KB; lista vazia: passa."""
    loads = [h for h in headers if h["type"] == PT_LOAD]
    found = []
    for h in loads:
        if h["align"] < PAGE:
            found.append(f"PT_LOAD em 0x{h['vaddr']:x} com alinhamento 0x{h['align']:x}, menor que 0x{PAGE:x}")
        if h["offset"] % PAGE != h["vaddr"] % PAGE:
            found.append(f"PT_LOAD em 0x{h['vaddr']:x}: deslocamento 0x{h['offset']:x} e endereço não casam módulo 0x{PAGE:x}")
    for relro in (h for h in headers if h["type"] == PT_GNU_RELRO):
        end = relro["vaddr"] + relro["memsz"]
        page_end = -(-end // PAGE) * PAGE
        if page_end == end:
            continue
        for h in loads:
            start, stop = h["vaddr"], h["vaddr"] + h["memsz"]
            if stop > end and start < page_end:
                found.append(
                    f"o RELRO termina em 0x{end:x}, antes do fim da página de 16 KB (0x{page_end:x}), e há dados de outro trecho "
                    f"(até 0x{min(stop, page_end):x}) na mesma página: ela seria protegida inteira"
                )
                break
    return found


def libraries(path: str) -> list[tuple[str, bytes]]:
    """Os .so a conferir de [path]: (nome para mostrar, conteúdo)."""
    if path.endswith(".so"):
        with open(path, "rb") as fh:
            return [(path, fh.read())]
    out = []
    with zipfile.ZipFile(path) as z:
        for name in sorted(z.namelist()):
            parts = name.split("/")
            if name.endswith(".so") and len(parts) >= 3 and parts[0] in ("lib", "jni") and parts[1] in ABIS_16KB:
                out.append((f"{path}!{name}", z.read(name)))
    return out


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("paths", nargs="+", help="APK, AAR ou .so")
    args = parser.parse_args(argv)
    checked = failed = 0
    for path in args.paths:
        for name, data in libraries(path):
            checked += 1
            try:
                found = problems(program_headers(data))
            except (ValueError, struct.error) as e:
                found = [f"cabeçalho ELF ilegível ({e})"]
            print(f"{'FALHA' if found else 'ok   '}  {name}")
            for line in found:
                print(f"        {line}")
            failed += bool(found)
    if checked == 0:
        print("nenhuma biblioteca .so de arm64-v8a ou x86_64 encontrada", file=sys.stderr)
        return 2
    print(f"{checked} biblioteca(s) conferida(s), {failed} com problema")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
