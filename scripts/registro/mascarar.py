"""Hides the dash's Wi-Fi name from a log before it is shared.

The dash is an access point named `RE_<4 characters>_<6 digits>` (e.g. `RE_AB12_345678`); the name is tied to one bike and to its owner, and
the system's logcat (unlike REau's own ride log, which never writes it) carries it whole 51 times in the logs of 26/10/2026. This is
the list of things the collection script (`coletar_do_celular.sh`) masks in the copy it makes for sharing; the original stays private.

Usage:  python3 mascarar.py ENTRADA SAIDA
"""
import os
import re
import sys

# The dash's own pattern first, then any other name that starts the way a dash's does (a longer or odd variant must not leak).
_DASH = re.compile(r"\bRE_[A-Za-z0-9]{4}_\d{6}\b")
_OTHER = re.compile(r"\bRE_[A-Za-z0-9_]{4,}")
MASK = "RE_****_******"


def mascarar(texto: str) -> str:
    """[texto] with every dash Wi-Fi name replaced by [MASK]."""
    return _OTHER.sub(MASK, _DASH.sub(MASK, texto))


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    if os.path.abspath(argv[0]) == os.path.abspath(argv[1]):
        # Opening the output empties it: over its own input the file would be lost.
        print("a saída não pode ser o próprio arquivo de entrada", file=sys.stderr)
        return 2
    with open(argv[0], encoding="utf-8", errors="replace") as entrada, open(argv[1], "w", encoding="utf-8") as saida:
        for linha in entrada:
            saida.write(mascarar(linha))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
