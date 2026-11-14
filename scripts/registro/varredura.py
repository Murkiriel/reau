"""O teste do item 1a (a busca de redes com o app atrás), lido do registro: as leituras da lista de Wi-Fi com redes e vazias, com o app na frente e atrás,
o tipo `location` aceito pelo serviço em primeiro plano e os cortes da pausa. Puro: recebe as linhas lidas por `ler_registro` (qualquer objeto com `arquivo`,
`hora`, `segundos`, `etiqueta` e `texto`), em ordem, e devolve texto; não lê arquivo.

Critério (de 08/11/2026; a coleta de 07/11 mostrou que "qualquer `scan empty` com o app atrás reprova" dispara em falso: 4 leituras vazias de um lugar sem
outra rede): um `scan empty` (ou `scan unreadable`) com o app atrás só REPROVA se, no mesmo estado e em até 60 s, a leitura vizinha trouxe uma rede que não seja o painel,
ou se o celular estava unido a um Wi-Fi (o sufixo `wifi joined=` do app, de 08/11/2026; antes dele, a olhada `data=wifi` do celular). O sufixo `last non-empty reading: N
networks, A s ago` ajuda a decidir sozinho: N de 2 ou mais, lida há 60 s ou menos e sem o app mudar de estado desde então, também reprova. A prova POSITIVA é haver ao menos
uma leitura com redes com o app atrás. Registros de uma build sem a linha `foreground types accepted` são de antes da correção: contam-se, sem veredito.
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tempo  # noqa: E402

JANELA_S = 60.0
TIPOS_ACEITOS = re.compile(r"projection: foreground types accepted: (\S+)")
TIPO_RECUSADO = ("foreground start refused", "the location type was not accepted", "no precise location permission")
LEITURA_COM_REDES = re.compile(r"\bscan of (\d+) networks, the dash( not among them|: )")
LEITURA_VAZIA = re.compile(r"\bscan (empty|unreadable)\b")
IMPORTANCIA = re.compile(r"process importance (\d+)")
SUFIXO_UNIDO = re.compile(r"wifi joined=(no|dash\+other|dash|other)")
SUFIXO_ULTIMA = re.compile(r"last non-empty reading: (?:(\d+) networks, (\d+) s ago|none yet)")
DADOS = re.compile(r"\bdata=(wifi|cell|none)")


def _estado(importancia):
    """`frente` (a tela do app ou a de ajustes do Android Auto na frente: importância 100 ou melhor) ou `atras` (o serviço em primeiro plano, 125, ou pior)."""
    return "frente" if importancia <= 100 else "atras"


def _leitura(x):
    """(redes, painel visto, vazia) da linha, ou None se ela não traz a leitura da lista. As linhas `scan look:` e `scan lookup` não são leituras."""
    if x.etiqueta != "wifi":
        return None
    m = LEITURA_COM_REDES.search(x.texto)
    if m:
        return int(m.group(1)), m.group(2) == ": ", False
    m = LEITURA_VAZIA.search(x.texto)
    if m:
        return 0, False, True
    return None


def _de_cada_linha(linhas):
    """[(linha, build, importância do app, estado)] pela ordem: a build do último `process start` e a importância do último `app foreground` (100) ou
    `app background — process importance N`; sem esses, (None, None)."""
    saida, build, importancia = [], None, None
    for x in linhas:
        if x.texto.startswith(tempo.PROCESSO_COMECOU):
            build = tempo.build_do_processo(x.texto)
            importancia = None
        elif x.etiqueta == "system" and x.texto == "app foreground":
            importancia = 100
        elif x.etiqueta == "system" and x.texto.startswith("app background"):
            m = IMPORTANCIA.search(x.texto)
            importancia = int(m.group(1)) if m else importancia
        saida.append((x, build, importancia))
    return saida


def _motivos(r, leituras, olhadas, mudancas):
    """Por que o `scan empty` [r] reprova (lista de frases; vazia quando é só um lugar sem outra rede). [leituras]: as outras leituras do mesmo registro e estado
    `atras`; [olhadas]: as olhadas `[device]` do mesmo registro; [mudancas]: os segundos das trocas de estado do app (`app foreground`/`app background`)."""
    x = r["linha"]
    motivos = []
    for v in leituras:
        if v is r or abs(v["linha"].segundos - x.segundos) > JANELA_S:
            continue
        if v["redes"] >= (2 if v["painel"] else 1):
            dt = v["linha"].segundos - x.segundos
            motivos.append(f"a leitura de {tempo.hora8(v['linha'])} ({abs(dt):.0f} s {'depois' if dt > 0 else 'antes'}), no mesmo estado, trouxe {v['redes']} "
                           f"redes{' (o painel e outra)' if v['painel'] else ' sem o painel'}")
            break
    unido = SUFIXO_UNIDO.search(x.texto)
    if unido:
        if unido.group(1) != "no":
            motivos.append(f"o celular estava unido a um Wi-Fi (wifi joined={unido.group(1)})")
    else:
        antes = next((o for o in reversed(olhadas) if 0 <= x.segundos - o.segundos <= JANELA_S), None)
        dado = DADOS.search(antes.texto) if antes is not None else None
        if dado and dado.group(1) == "wifi":
            motivos.append(f"o celular estava unido a um Wi-Fi (data=wifi na olhada das {tempo.hora8(antes)})")
    ultima = SUFIXO_ULTIMA.search(x.texto)
    if ultima and ultima.group(1) and int(ultima.group(1)) >= 2 and int(ultima.group(2)) <= JANELA_S:
        desde = x.segundos - int(ultima.group(2))
        if not any(desde <= t <= x.segundos for t in mudancas):
            motivos.append(f"a última leitura com redes, {ultima.group(2)} s antes e sem o app mudar de estado, tinha {ultima.group(1)} redes")
    return motivos


def teste_1a(linhas):
    """O resultado do teste do item 1a por build do app: [{build, tipos, leituras, vazias, cortes, veredito, motivos}] na ordem em que as builds aparecem.
    `tipos`: (projeções com a linha, com `location`, recusas); `leituras`: {estado: [com redes, vazias]}; `vazias`: as leituras `atras` vazias, cada uma com os
    motivos que a reprovam; `cortes`: contagens; `veredito`: `PROVADO`, `REPROVADO`, `INCONCLUSIVO` (só vazias atrás), `SEM DADOS` (nenhuma leitura atrás) ou
    `ANTES DA CORREÇÃO` (a build não tem a linha `foreground types accepted`)."""
    por_build = {}
    for x, build, importancia in _de_cada_linha(linhas):
        por_build.setdefault(build, []).append((x, importancia))
    saida = []
    for build, itens in por_build.items():
        if build is None:
            continue  # lines before any `process start` (a file that does not start with it): no build to say
        saida.append(_da_build(build, itens))
    return saida


def _da_build(build, itens):
    tipos = [m for x, _ in itens for m in [TIPOS_ACEITOS.search(x.texto)] if m]
    com_location = sum(1 for m in tipos if "location" in m.group(1).split("|"))
    recusas = sum(1 for x, _ in itens for r in TIPO_RECUSADO if r in x.texto)
    leituras = []
    olhadas = [x for x, _ in itens if x.texto.startswith("device ")]
    for x, importancia in itens:
        lida = _leitura(x)
        if lida is None:
            continue
        redes, painel, vazia = lida
        if vazia:
            m = IMPORTANCIA.search(x.texto)
            importancia = int(m.group(1)) if m else importancia
        if importancia is None:
            continue
        leituras.append({"linha": x, "redes": redes, "painel": painel, "vazia": vazia, "estado": _estado(importancia),
                         "arquivo": x.arquivo, "unreadable": "unreadable" in x.texto and vazia})
    contagem = {"frente": [0, 0], "atras": [0, 0]}
    for v in leituras:
        contagem[v["estado"]][1 if v["vazia"] else 0] += 1
    vistos = sum(1 for x, _ in itens if re.search(r"the dash: '[^']*' -?\d+ dBm \d+ MHz seen \d+ s ago", x.texto))
    cortes = {
        "pausa cortada": sum(1 for x, _ in itens if "the pause is cut" in x.texto),
        "pedido de novo com o painel visto": sum(1 for x, _ in itens if "showed up in the scan" in x.texto and "asking again" in x.texto),
        "painel na varredura sem unir": sum(1 for x, _ in itens if "has not joined" in x.texto),
        "projeção tenta o painel já": sum(1 for x, _ in itens if "the dash is tried now, not when the wait ends" in x.texto),
    }
    vazias = []
    for r in (v for v in leituras if v["vazia"] and v["estado"] == "atras"):
        mesmo = [v for v in leituras if v["estado"] == "atras" and v["arquivo"] == r["arquivo"]]
        mesmas_olhadas = [o for o in olhadas if o.arquivo == r["arquivo"]]
        mudancas_do_arquivo = [x.segundos for x, _ in itens if x.arquivo == r["arquivo"] and x.etiqueta == "system"
                               and (x.texto == "app foreground" or x.texto.startswith("app background"))]
        vazias.append({"linha": r["linha"], "motivos": _motivos(r, mesmo, mesmas_olhadas, mudancas_do_arquivo)})
    reprovadas = [v for v in vazias if v["motivos"]]
    positiva = contagem["atras"][0] > 0
    if not tipos:
        veredito = "ANTES DA CORREÇÃO"
    elif recusas or com_location < len(tipos) or reprovadas:
        veredito = "REPROVADO"
    elif positiva:
        veredito = "PROVADO"
    elif contagem["atras"][1] > 0:
        veredito = "INCONCLUSIVO"
    else:
        veredito = "SEM DADOS"
    return {"build": build, "tipos": (len(tipos), com_location, recusas), "leituras": contagem, "vazias": vazias, "cortes": cortes, "vistos": vistos,
            "positiva": positiva, "veredito": veredito}


def texto_do_teste(resultados):
    """As linhas do bloco "TESTE DO ITEM 1a", uma sequência por build; vazio sem nenhuma build com leitura da lista."""
    saida = []
    for r in resultados:
        frente, atras = r["leituras"]["frente"], r["leituras"]["atras"]
        if not (frente[0] or frente[1] or atras[0] or atras[1] or r["tipos"][0]):
            continue
        saida.append(f"build {r['build']}: {r['veredito']}")
        projecoes, com_location, recusas = r["tipos"]
        if projecoes:
            saida.append(f"  tipos aceitos pelo serviço em primeiro plano: `location` em {com_location} de {projecoes} partidas, {recusas} recusas")
        else:
            saida.append("  sem a linha `foreground types accepted` (build de antes da correção do item 1a): o serviço não tinha o tipo `location`")
        saida.append(f"  leituras da lista de redes: app na frente {frente[0]} com redes e {frente[1]} vazias; app atrás {atras[0]} com redes e {atras[1]} vazias; "
                     f"{r['vistos']} com o painel visto (`seen N s ago`)")
        cortes = ", ".join(f"{nome} {n}" for nome, n in r["cortes"].items())
        saida.append(f"  cortes da pausa e vigias: {cortes}")
        if r["tipos"][0]:
            vazias = r["vazias"]
            reprovadas = [v for v in vazias if v["motivos"]]
            if vazias and not reprovadas:
                horas = ", ".join(tempo.hora8(v["linha"]) for v in vazias)
                saida.append(f"  {len(vazias)} leituras vazias com o app atrás ({horas}): nenhuma com leitura vizinha com outra rede, no mesmo estado e em até "
                             f"{JANELA_S:.0f} s, nem com o celular unido a um Wi-Fi: lugar sem outra rede, não falta de acesso")
            for v in reprovadas:
                saida.append(f"  REPROVA: leitura vazia com o app atrás às {tempo.hora8(v['linha'])}: " + "; ".join(v["motivos"]))
            saida.append(f"  prova positiva (ao menos uma leitura com redes com o app atrás): {'sim' if r['positiva'] else 'NÃO'}")
        elif atras[1]:
            saida.append(f"  {atras[1]} leituras vazias com o app atrás: o esperado antes da correção (o serviço não pedia o tipo `location`)")
    return saida


def problemas_do_teste(resultados):
    """Os motivos de um veredito REPROVADO (para a lista de problemas): o tipo recusado, partida sem o tipo `location` e cada leitura vazia que reprova."""
    saida = []
    for r in resultados:
        if r["veredito"] != "REPROVADO":
            continue
        projecoes, com_location, recusas = r["tipos"]
        if recusas or com_location < projecoes:
            saida.append(f"item 1a, build {r['build']}: o serviço em primeiro plano não teve o tipo `location` em {projecoes - com_location} de {projecoes} partidas "
                         f"({recusas} recusas): a lista de redes com o app atrás volta vazia")
        for v in r["vazias"]:
            if v["motivos"]:
                saida.append(f"{tempo.hora8(v['linha'])}: item 1a — leitura vazia da lista de redes com o app atrás (build {r['build']}): " + "; ".join(v["motivos"]))
    return saida
