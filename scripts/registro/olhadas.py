"""As leituras do classificador de imagem (o MLP) que o registro da build debug traz, `picture look: MAP p=0.942 (p under 0.98; 3 looks not written)`: as que ficaram abaixo de
`P_BAIXO` e o alarme da taxa de "sem certeza". Puro: recebe as linhas de uma projeção (qualquer objeto com `hora`, `segundos`, `etiqueta`, `texto`); não lê arquivo.

O app só escreve uma `picture look:` quando a classe muda ou quando a rede não tem certeza (p abaixo de 0,98), no máximo uma por segundo, e diz quantas deixou de escrever
(`N looks not written`). Então "sem certeza" é contável: as linhas com p entre 0 e 0,98 mais as que ele disse não ter escrito. `p=0.000` é a imagem preta que nem chegou à rede (a
primeira leitura de toda projeção): não é incerteza, vai à parte.

Alarme (limite escolhido de 11/11/2026, com a única coleta que tem estas linhas, a de 08/11): a taxa é «leituras sem certeza ÷ leituras feitas» (as leituras feitas vêm do campo `look=N`
das linhas `video fps` de 5 s). Nas projeções boas da coleta de 08/11 (mapa, com um cartão de chamada sobre ele por 33 s, que dá 92 a 95 %) a taxa foi de 1,2 % e 1,4 %: o alarme
dispara acima de `LIMITE_PCT` = 3 %, o dobro do que as boas deram e ainda pouco para um mapa de que a rede duvida de verdade. Sem as linhas `video fps` (o celular sem o painel, em casa) a
medida é por minuto de projeção: acima de `LIMITE_POR_MINUTO` = 7 por minuto (3 % de ~4 leituras por segundo, que é o `look=20` por janela de 5 s; fora do painel o ritmo pode ser outro, e a medida é só uma
segunda opinião). Nenhum dos dois alarma com menos de `MINIMO_BAIXAS` leituras sem
certeza (uma projeção curta com duas dúvidas não é taxa). Os limites estão aqui e valem até haver mais coletas com o J4; mudá-los é mudar este arquivo.
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tempo  # noqa: E402

LEITURA = re.compile(r"^picture look: (\w+) p=([\d.]+) \((.*)\)$")
NAO_ESCRITAS = re.compile(r"(\d+) looks? not written")
LOOK_NA_JANELA = re.compile(r"\blook=(\d+)(?:/[\d.]+/[\d.]+ms)?")
TELA_AA = re.compile(r"^aa-screen: (nav|menu|apps)\b")
CLASSE_DA_TELA = {"MAP": "nav", "MENU": "menu", "APPS": "apps"}
TELA_NOME = {"nav": "mapa", "menu": "menu", "apps": "apps"}
P_BAIXO = 0.98  # LookAudit.LOW_P do app: abaixo disto a rede "não tem certeza"
LIMITE_PCT = 3.0
LIMITE_POR_MINUTO = 7.0
MINIMO_BAIXAS = 10
LISTA_MAX = 40  # linhas da tabela por projeção; o resto é contado


def leituras(bloco):
    """As linhas `picture look:` de uma projeção, pela ordem: [{linha, classe, p, motivo, puladas, tela_aa}]. `tela_aa`: a última `aa-screen:` antes (nav, menu, apps) ou None."""
    saida, tela = [], None
    for x in bloco:
        t = TELA_AA.match(x.texto)
        if t:
            tela = t.group(1)
            continue
        m = LEITURA.match(x.texto)
        if not m:
            continue
        puladas = NAO_ESCRITAS.search(m.group(3))
        saida.append({"linha": x, "classe": m.group(1), "p": float(m.group(2)), "motivo": m.group(3),
                      "puladas": int(puladas.group(1)) if puladas else 0, "tela_aa": tela})
    return saida


def baixas(lidas):
    """As leituras com p entre 0 (exclusive: a imagem preta não é leitura) e `P_BAIXO` (exclusive)."""
    return [r for r in lidas if 0 < r["p"] < P_BAIXO]


def _leituras_feitas(bloco):
    return sum(int(m.group(1)) for x in bloco if x.texto.startswith("video fps target=") for m in [LOOK_NA_JANELA.search(x.texto)] if m)


def taxa(bloco, lidas=None):
    """{baixas (escritas + não escritas), escritas, puladas, pretas, incertas (linhas com a classe UNSURE), feitas, pct (ou None sem `look=`), minutos, por_minuto, alarme, minimo_p}
    de uma projeção; o alarme segue as regras do cabeçalho do módulo."""
    lidas = leituras(bloco) if lidas is None else lidas
    escritas = baixas(lidas)
    puladas = sum(r["puladas"] for r in lidas)
    total = len(escritas) + puladas
    feitas = _leituras_feitas(bloco)
    minutos = tempo.dif(bloco[-1].segundos, bloco[0].segundos) / 60.0 if bloco else 0.0
    pct = 100.0 * total / feitas if feitas else None
    por_minuto = total / minutos if minutos > 0 else 0.0
    if total < MINIMO_BAIXAS:
        alarme = False
    elif pct is not None:
        alarme = pct > LIMITE_PCT
    else:
        alarme = por_minuto > LIMITE_POR_MINUTO
    return {"baixas": total, "escritas": len(escritas), "puladas": puladas, "pretas": sum(1 for r in lidas if r["p"] == 0), "incertas": sum(1 for r in lidas if r["classe"] == "UNSURE"),
            "feitas": feitas, "pct": pct, "minutos": minutos, "por_minuto": por_minuto, "alarme": alarme,
            "minimo_p": min((r["p"] for r in escritas), default=None)}


def _v(n, casas=3):
    return f"{n:.{casas}f}".replace(".", ",")


def texto_da_taxa(t):
    """`58 leituras sem certeza (p < 0,98) em 4 190 leituras: 1,4 % (limite 3 %), a pior p=0,933`; sem as linhas `video fps`, por minuto."""
    base = f"{t['baixas']} leituras sem certeza (p < {_v(P_BAIXO, 2)}; {t['escritas']} escritas e {t['puladas']} que o app disse não ter escrito)"
    if t["pct"] is not None:
        base += f" em {t['feitas']} leituras: {_v(t['pct'], 1)} % (limite {_v(LIMITE_PCT, 0)} %)"
    else:
        base += f" em {_v(t['minutos'], 1)} min, sem as linhas `video fps` para contar as leituras: {_v(t['por_minuto'], 1)} por minuto (limite {_v(LIMITE_POR_MINUTO, 0)})"
    if t["minimo_p"] is not None:
        base += f", a pior p={_v(t['minimo_p'])}"
    return base


def tabela(bloco):
    """As linhas da tabela de uma projeção: cada leitura com p < 0,98 (até `LISTA_MAX`), com a classe, a tela que o `aa-screen:` dizia e se diverge dela."""
    lidas = baixas(leituras(bloco))
    saida = []
    for r in lidas[:LISTA_MAX]:
        esperada = CLASSE_DA_TELA.get(r["classe"])
        tela = TELA_NOME.get(r["tela_aa"], "sem aa-screen")
        diverge = " (DIVERGE do aa-screen)" if esperada and r["tela_aa"] and esperada != r["tela_aa"] else ""
        extra = f"; {r['puladas']} leituras não escritas" if r["puladas"] else ""
        saida.append(f"{r['linha'].hora[:8]}  {r['classe']:<6s} p={_v(r['p'])}  tela do AA: {tela}{diverge}{extra}")
    if len(lidas) > LISTA_MAX:
        saida.append(f"(mais {len(lidas) - LISTA_MAX} leituras não listadas)")
    return saida


def problemas(blocos):
    """O alarme de cada projeção cuja taxa de "sem certeza" passa do limite."""
    saida = []
    for bloco in blocos:
        t = taxa(bloco)
        if t["alarme"]:
            saida.append(f"projeção de {bloco[0].hora} ({bloco[0].arquivo}): o classificador de imagem ficou sem certeza demais — {texto_da_taxa(t)}")
    return saida


def secao(blocos):
    """As linhas da seção "LEITURAS DO CLASSIFICADOR": por projeção que tem alguma leitura abaixo de `P_BAIXO`, a taxa e a tabela. Vazio sem nenhuma."""
    saida = []
    for bloco in blocos:
        if not baixas(leituras(bloco)):
            continue
        saida.append(f"projeção de {bloco[0].hora} ({bloco[0].arquivo}): {texto_da_taxa(taxa(bloco))}")
        saida += ["  " + linha for linha in tabela(bloco)]
    return saida
