"""O que os leitores do registro repetiam: o relógio do registro (a hora do dia, que dá a volta à meia-noite), os textos de duração e a linha que abre cada processo do app.

Puro: sem arquivo, sem estado. `ler_registro.py`, `quedas.py`, `varredura.py` e `logcat_sistema.py` usam isto em vez de cada um ter a sua cópia.
"""
import re
from datetime import datetime, timedelta

SEGUNDOS_NO_DIA = 86400
# `==== process start: app — app 0.3.0-dev+26.11.10-08.09 (29903203, 7eee9cb), …`: a linha que abre cada processo do app e traz a versão.
PROCESSO_COMECOU = "==== process start:"
VERSAO_DO_APP = re.compile(r"^==== process start: [^—]*— app ([^\s,]+)(?: \(([^)]*)\))?")


def dif(a, b):
    """Os segundos de `b` até `a` no relógio do registro (a hora do dia em segundos: passar da meia-noite é um dia a mais)."""
    return (a - b) % SEGUNDOS_NO_DIA


def assinado(a, b):
    """`a - b` entre -12 h e +12 h: uma linha um pouco antes ou depois da outra, sem o salto da meia-noite."""
    d = dif(a, b)
    return d - SEGUNDOS_NO_DIA if d > SEGUNDOS_NO_DIA / 2 else d


def dec(valor, casas=1):
    """`3,4`: um número com vírgula decimal."""
    return f"{valor:.{casas}f}".replace(".", ",")


def segundos_texto(valor):
    """`223,4 s`."""
    return dec(valor) + " s"


def hora_ms(segundos):
    """`07:44:46,8` de um instante do dia em segundos (o `seen N s ago` não tem mais precisão que o segundo)."""
    segundos %= SEGUNDOS_NO_DIA
    return f"{int(segundos // 3600):02d}:{int(segundos % 3600 // 60):02d}:{segundos % 60:04.1f}".replace(".", ",")


def hora8(x):
    """`07:44:46` de uma linha do registro (ou de uma entrada do logcat), sem os milissegundos."""
    return x.hora[:8]


def duracao_texto(s):
    """`3 min 05 s` ou `45 s`."""
    return f"{int(s // 60)} min {int(s % 60):02d} s" if s >= 60 else f"{s:.0f} s"


def duracao_resumo(s):
    """Uma duração como o `resumo` do app a escreve: `45s` ou `1m08s`."""
    s = int(round(s))
    return f"{s // 60}m{s % 60:02d}s" if s >= 60 else f"{s}s"


def duracao_s(texto):
    """Os segundos de uma duração do resumo (`45s`, `1m08s`); 0 se o texto não é uma."""
    m = re.fullmatch(r"(?:(\d+)m)?(\d+)s", texto)
    return int(m.group(1) or 0) * 60 + int(m.group(2)) if m else 0


def build_do_processo(texto):
    """`0.3.0-dev+26.11.10-08.09 (29903203, 7eee9cb)` da linha `process start`; `sem versão` se a linha não a traz; None se a linha não abre um processo."""
    if not texto.startswith(PROCESSO_COMECOU):
        return None
    m = VERSAO_DO_APP.match(texto)
    if not m:
        return "sem versão"
    return f"{m.group(1)} ({m.group(2)})" if m.group(2) else m.group(1)


def instante(x):
    """O instante (datetime) de uma linha do registro (`dia` e `hora`), ou None se o nome do arquivo dela não trazia o dia."""
    if not x.dia:
        return None
    h, m, s = x.hora.split(":")
    return datetime.fromisoformat(x.dia) + timedelta(hours=int(h), minutes=int(m), seconds=float(s))
