"""Compara as corridas pelo conjunto de chaves experimentais que estavam ligadas (item 15 do levantamento de 11/11/2026).

O app escreve, no começo de cada projeção, `settings in use: … experimentRtpPacing=false …` (cada chave, ligada ou não) e, se alguma está ligada, `connection experiments ON: video pacing
(RTP packets spaced out); …`. Uma «corrida» aqui é uma projeção; o conjunto de chaves dela é o que essas linhas diziam no começo dela (a linha vem logo depois de `projeção ligada`, ou no
`process start` de antes). Mudar uma chave no meio da projeção não é visto: vale a do começo. Os registros de antes das chaves (a build não escrevia os campos) formam um grupo à parte,
«sem registro das chaves», que não serve de referência.

CRITÉRIOS (escritos antes de olhar os dados; mudá-los é mudar este arquivo e a ajuda do `ler_registro.py`):

1. Referência: o grupo «nenhuma» (todas as chaves desligadas). Cada outro grupo é comparado com ele. Sem referência, só se mostram os números.
2. Medida principal: quedas com Wi-Fi firme por hora transmitindo. «Firme» é o sinal do painel de `FIRME_DBM` (-75 dBm) ou melhor na última olhada antes da queda (o `bike=` de até
   90 s antes, ou as últimas amostras do histórico `[link]`); queda sem sinal no registro não conta como firme. A queda do painel que desligou com a moto não é queda. Horas transmitindo
   são as do `resumo` (`streaming=`).
3. Medidas secundárias, só mostradas e anotadas: `late` (quadros que o codificador entregou atrasados) por 100 janelas de 5 s e janelas com `late` > 0; kbps médio nas janelas em
   movimento (`full=true`; um grupo com bitrate menor quase sempre é a chave de 200 kbps, e a legibilidade dele NÃO se mede aqui: é o teste cego do vídeo); segundos sem imagem por hora
   (o `dark=` do resumo); quedas totais por hora.
4. Dado suficiente: o grupo e a referência têm cada um ao menos `HORAS_MINIMAS` (1 h) transmitindo e `PROJECOES_MINIMAS` (3) projeções; senão o veredito é «dados insuficientes».
5. Veredito sobre a medida principal: com a taxa da referência, o grupo «esperaria» (taxa × as horas dele) quedas firmes. PIOR se viu `FATOR` (1,5) vezes isso ou mais e ao menos
   `DIFERENCA_MINIMA` (2) quedas a mais; MELHOR se viu 1/`FATOR` disso ou menos e ao menos 2 a menos; senão "sem diferença que se veja". Duas ou três quedas a mais ou a menos numa
   amostra pequena é acaso, por isso o piso de 2 e o de 1 h: o veredito é indício, não prova, e a decisão de ligar uma chave por padrão é do dono.
6. Uma nota (não veredito) quando `late` por janela ou segundos sem imagem por hora do grupo passam de `FATOR_NOTA` (2) vezes os da referência, com ao menos `JANELAS_MINIMAS`
   (100) janelas em cada um.

Puro: recebe as linhas e os fatos de cada projeção (quem os calcula é o `ler_registro.py`); não lê arquivo.
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tempo  # noqa: E402

# (campo do app, nome curto, trecho que a linha `connection experiments ON:` usa), na ordem das chaves das notas.
CHAVES = [
    ("experimentRtpPacing", "espaçar o RTP", "video pacing"),
    ("experimentDebugDscp", "DSCP na debug", "priority marks"),
    ("experimentAdaptiveBitrate", "bitrate menor", "lower adaptive bitrate"),
    ("experimentWifiBind", "Wi-Fi e EPERM", "bind retries on the same network"),
    ("experimentWifiBand", "só 2,4 GHz", "2.4 GHz only"),
    ("experimentEncoderWatch", "codificador mudo", "mute encoder watch"),
    ("experimentFocusGap", "pausa do foco", "focus gap"),
    ("experimentNightMemory", "dia e noite com memória", "day and night remembered"),
    # As sete da leva 5 (11/11/2026); o trecho é o que `connection experiments ON:` diz de cada uma.
    ("experimentWeatherAge", "clima velho some", "weather hidden from the dash"),
    ("experimentHeatCeiling", "teto pelo calor da bateria", "frame ceiling lowered by the battery"),
    ("experimentGpsSpeed", "velocidade do GPS", "GPS speed for the speed profile"),
    ("experimentRecalcInactive", "recalculando pelo inativo", "short inactive of the route"),
    ("experimentColorMatrix", "cores do codificador", "BT.601 limited-range"),
    ("experimentProtocolSix", "protocolo 6.0", "protocol 6.0 asked"),
    ("experimentTolerantRx", "vigia tolerante", "tolerant dash silence watch"),
]
GUARDA = "experimentStartGuard"
SETTINGS = "settings in use:"
EXPERIMENTOS_LIGADOS = "connection experiments ON:"
CAMPO = re.compile(r"\b(experiment\w+)=(true|false)\b")
FIRME_DBM = -75
JANELA_INICIO_S = 5.0
HORAS_MINIMAS = 1.0
PROJECOES_MINIMAS = 3
FATOR = 1.5
DIFERENCA_MINIMA = 2
FATOR_NOTA = 2.0
JANELAS_MINIMAS = 100
RESUMO = re.compile(r"^resumo duration=(\S+) streaming=(\S+)")
DARK = re.compile(r"\bdark=(\d+)/(\S+) \(max (\S+)\)")
VIDEO_LATE = re.compile(r"\blate=(\d+)\b")
VIDEO_KBPS = re.compile(r"\bkbps=(\d+)")
VIDEO_COMPLETA = re.compile(r"full=(true|false)")
SEM_REGISTRO = "sem registro das chaves"
NENHUMA = "nenhuma"


def chaves_da_linha(texto):
    """(conjunto de campos ligados, se a linha fala das chaves) de uma linha `settings in use:` ou `connection experiments ON:`; None para qualquer outra."""
    if texto.startswith(SETTINGS):
        campos = CAMPO.findall(texto)
        # A guarda da partida vem ligada por padrão desde 11/11/2026 e não é um experimento: agir só age com outro ligado. Não agrupa corridas.
        return {nome for nome, valor in campos if valor == "true" and nome != GUARDA}, bool(campos)
    if texto.startswith(EXPERIMENTOS_LIGADOS):
        return {campo for campo, _, trecho in CHAVES if trecho in texto}, True
    return None


def por_projecao(linhas, blocos):
    """[(frozenset de campos ligados, conhecido)] alinhado a [blocos]: o estado das chaves na última linha de chaves até a projeção ligar, trocado pelo da última que vier em até
    `JANELA_INICIO_S` s depois (a linha `settings in use:` do app vem logo depois de `projeção ligada`). Sem nenhuma linha de chaves: (vazio, desconhecido)."""
    posicao = {id(x): k for k, x in enumerate(linhas)}
    mudancas, atual = [], (frozenset(), False)
    for k, x in enumerate(linhas):
        achado = chaves_da_linha(x.texto)
        if achado is None:
            continue
        ligadas, conhecido = achado
        atual = (frozenset(ligadas), conhecido) if x.texto.startswith(SETTINGS) else (atual[0] | frozenset(ligadas), True)
        mudancas.append((k, x.segundos, atual))
    saida = []
    for bloco in blocos:
        inicio = posicao[id(bloco[0])]
        estado = (frozenset(), False)
        for k, segundos, depois in mudancas:
            if k <= inicio or tempo.dif(segundos, bloco[0].segundos) <= JANELA_INICIO_S:
                estado = depois
            if k > inicio and tempo.dif(segundos, bloco[0].segundos) > JANELA_INICIO_S:
                break
        saida.append(estado)
    return saida


def rotulo(ligadas, conhecido):
    """O nome do grupo: as chaves ligadas pela ordem das notas e unidas por « + », `nenhuma` (todas desligadas) ou `sem registro das chaves`."""
    if not conhecido:
        return SEM_REGISTRO
    if not ligadas:
        return NENHUMA
    return " + ".join(nome for campo, nome, _ in CHAVES if campo in ligadas)


def fatos(bloco, ligadas, conhecido, quedas, firmes):
    """Os números de uma projeção para a comparação. [quedas]: as quedas que não são moto desligada; [firmes]: as dessas com Wi-Fi firme (o `ler_registro.py` as separa).
    `transmitindo_s` vem do `resumo` e é None se a projeção não o tem (cortada)."""
    resumo = next((m for x in bloco for m in [RESUMO.match(x.texto)] if m), None)
    escuro = next((m for x in bloco for m in [DARK.search(x.texto)] if m and x.texto.startswith("resumo ")), None)
    janelas = [x for x in bloco if x.texto.startswith("video fps target=")]
    late = [int(m.group(1)) for x in janelas for m in [VIDEO_LATE.search(x.texto)] if m]
    movimento = [int(k.group(1)) for x in janelas for k in [VIDEO_KBPS.search(x.texto)] for c in [VIDEO_COMPLETA.search(x.texto)] if k and c and c.group(1) == "true"]
    return {"rotulo": rotulo(ligadas, conhecido), "conhecido": conhecido, "arquivo": bloco[0].arquivo, "hora": bloco[0].hora,
            "transmitindo_s": float(tempo.duracao_s(resumo.group(2))) if resumo else None,
            "quedas": quedas, "firmes": firmes,
            "janelas": len(late), "janelas_late": sum(1 for n in late if n > 0), "late": sum(late), "kbps": movimento,
            "escuro_s": float(tempo.duracao_s(escuro.group(2))) if escuro else 0.0}


def agrupar(corridas):
    """{rótulo: totais} das corridas: projeções, horas transmitindo (só das que têm resumo), quedas, quedas firmes, janelas, janelas com `late`, `late`, kbps e segundos sem imagem."""
    grupos = {}
    for c in corridas:
        g = grupos.setdefault(c["rotulo"], {"rotulo": c["rotulo"], "projecoes": 0, "com_resumo": 0, "horas": 0.0, "quedas": 0, "firmes": 0, "janelas": 0, "janelas_late": 0,
                                           "late": 0, "kbps": [], "escuro_s": 0.0})
        g["projecoes"] += 1
        if c["transmitindo_s"] is not None:
            g["com_resumo"] += 1
            g["horas"] += c["transmitindo_s"] / 3600.0
            g["escuro_s"] += c["escuro_s"]
        g["quedas"] += c["quedas"]
        g["firmes"] += c["firmes"]
        for k in ("janelas", "janelas_late", "late"):
            g[k] += c[k]
        g["kbps"] += c["kbps"]
    return grupos


def _por_hora(n, horas):
    return n / horas if horas > 0 else None


def suficiente(g):
    return g["horas"] >= HORAS_MINIMAS and g["projecoes"] >= PROJECOES_MINIMAS


def veredito(g, referencia):
    """(palavra, motivo) do grupo contra a referência, pelo critério 5 do módulo: PIOR, MELHOR, `sem diferença que se veja` ou `dados insuficientes`/`sem referência`."""
    if referencia is None:
        return "sem referência", f"nenhuma corrida com todas as chaves desligadas (`{NENHUMA}`) para comparar"
    if not suficiente(g) or not suficiente(referencia):
        quem = f"o grupo ({g['horas']:.1f} h, {g['projecoes']} projeções)" if not suficiente(g) else f"a referência ({referencia['horas']:.1f} h, {referencia['projecoes']} projeções)"
        return "dados insuficientes", f"{quem} tem menos de {HORAS_MINIMAS:g} h transmitindo ou de {PROJECOES_MINIMAS} projeções"
    esperadas = referencia["firmes"] / referencia["horas"] * g["horas"]
    viu = g["firmes"]
    if viu >= esperadas * FATOR and viu - esperadas >= DIFERENCA_MINIMA:
        return "PIOR", f"{viu} quedas com Wi-Fi firme onde a referência faria ~{esperadas:.1f} no mesmo tempo"
    if viu <= esperadas / FATOR and esperadas - viu >= DIFERENCA_MINIMA:
        return "MELHOR", f"{viu} quedas com Wi-Fi firme onde a referência faria ~{esperadas:.1f} no mesmo tempo"
    return "sem diferença que se veja", f"{viu} quedas com Wi-Fi firme contra ~{esperadas:.1f} esperadas pela referência"


def _notas(g, referencia):
    """As notas do critério 6 (e o kbps): onde o grupo se afasta da referência fora da medida principal."""
    notas = []
    if referencia is None or not suficiente(g) or not suficiente(referencia):
        return notas
    if g["janelas"] >= JANELAS_MINIMAS and referencia["janelas"] >= JANELAS_MINIMAS:
        a, b = g["janelas_late"] / g["janelas"], referencia["janelas_late"] / referencia["janelas"]
        if a >= b * FATOR_NOTA and a > b:
            notas.append(f"`late` em {100 * a:.1f} % das janelas contra {100 * b:.1f} % da referência")
    a, b = _por_hora(g["escuro_s"], g["horas"]), _por_hora(referencia["escuro_s"], referencia["horas"])
    if a is not None and b is not None and a > b * FATOR_NOTA and a - b > 0:
        notas.append(f"sem imagem {a:.0f} s por hora contra {b:.0f} s da referência")
    if g["kbps"] and referencia["kbps"]:
        notas.append(f"kbps médio em movimento {sum(g['kbps']) / len(g['kbps']):.0f} contra {sum(referencia['kbps']) / len(referencia['kbps']):.0f} da referência "
                     "(a legibilidade do mapa não se mede aqui)")
    return notas


def _n(valor, casas=2):
    return "-" if valor is None else f"{valor:.{casas}f}".replace(".", ",")


def texto(corridas):
    """As linhas da comparação: uma por grupo (números e veredito), mais as notas. Vazio sem nenhuma corrida."""
    if not corridas:
        return []
    grupos = agrupar(corridas)
    referencia = grupos.get(NENHUMA)
    ordem = sorted(grupos.values(), key=lambda g: (g["rotulo"] != NENHUMA, g["rotulo"] == SEM_REGISTRO, g["rotulo"]))
    saida = []
    for g in ordem:
        kbps = f"{sum(g['kbps']) / len(g['kbps']):.0f}" if g["kbps"] else "-"
        late = f"{_n(100 * g['janelas_late'] / g['janelas'], 1)} % das janelas" if g["janelas"] else "-"
        escuro = f"{_n(_por_hora(g['escuro_s'], g['horas']), 0)} s/h" if g["horas"] > 0 else "-"
        linha = (f"{g['rotulo']}: {g['projecoes']} {'projeção' if g['projecoes'] == 1 else 'projeções'}, {_n(g['horas'], 1)} h transmitindo, "
                 f"{g['quedas']} quedas ({_n(_por_hora(g['quedas'], g['horas']))}/h), {g['firmes']} com Wi-Fi firme ({_n(_por_hora(g['firmes'], g['horas']))}/h), "
                 f"late {late}, kbps médio {kbps}, sem imagem {escuro}")
        if g["rotulo"] == SEM_REGISTRO:
            saida.append(linha + " — sem veredito (a build não escrevia as chaves)")
            continue
        if g is referencia:
            saida.append(linha + " — referência")
            continue
        palavra, motivo = veredito(g, referencia)
        saida.append(f"{linha} — {palavra.upper() if palavra in ('PIOR', 'MELHOR') else palavra}: {motivo}")
        saida += [f"    nota: {n}" for n in _notas(g, referencia)]
    return saida
