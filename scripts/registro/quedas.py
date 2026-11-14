"""O que o registro diz de cada queda da imagem do painel: quanto tempo ficou sem imagem (no total e com o painel ligado) e quanto o painel levou, depois de ligado,
para ser unido e para transmitir. Puro: recebe as linhas de uma projeção (qualquer objeto com `hora`, `segundos`, `etiqueta` e `texto`) e os grupos de queda que
`ler_registro` já separou; não lê arquivo.

O app escreve, desde 08/11/2026, uma linha quando a imagem volta (`image back: 223.4 s without it since the last dash packet before the loss; with the dash on: 86.5 s
(it came on 136.9 s into the loss)`); sem ela (registro de antes) o tempo sai calculado, da queda (o último pacote do painel, que a linha `link lost` traz) ao `→ STREAMING` seguinte, e o
tempo com o painel ligado da idade dele no primeiro pacote (`dash heartbeat age N s at the first packet`: o painel ligou N s antes). O contador dá a volta aos 256 s: numa queda mais
longa o tempo do painel é "pelo menos".
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tempo  # noqa: E402

IDADE = re.compile(r"dash heartbeat age (\d+) s at the first packet")
ULTIMO_PACOTE = re.compile(r"last dash packet ([\d.]+) s before")
IMAGEM_VOLTOU = re.compile(r"image back: ([\d.]+) s without it since the last dash packet before the loss; with the dash on: "
                           r"(?:(at least )?([\d.]+) s(?: \(it (stayed on|came on ([\d.]+) s into the loss)\))?|unknown)")
CONTADOR_VOLTA_S = 256
# A queda em que o painel já estava ligado e a imagem levou mais que isto para voltar (a partida média é de 22 s) é um problema; abaixo disso é informação.
COM_PAINEL_LONGO_S = 60.0
UNIDO = ("connected ", "using already-connected WiFi")
JANELA_IDADE_S = 30.0


def _com_o_painel(fim_s, perda_s, idade_s, depois_da_queda_s):
    """Quanto da queda o painel esteve ligado: (segundos, quanto depois da queda ele ligou ou None se já estava ligado). `idade_s` é a idade no primeiro pacote, lido
    em `depois_da_queda_s` depois da queda."""
    liga = depois_da_queda_s - idade_s  # o painel ligou `liga` s depois da queda (negativo: já estava ligado)
    total = tempo.dif(fim_s, perda_s)
    return (total - max(liga, 0.0), liga if liga > 0 else None)


def interrupcoes(bloco, grupos):
    """Uma entrada por interrupção da imagem de uma projeção, pela ordem: a queda (`grupos`) até o próximo `→ STREAMING`, ou até o fim da projeção (a última, com a moto
    desligada). As quedas que começam antes de a imagem voltar são a mesma interrupção. Cada uma: {queda, voltou (a linha `→ STREAMING` ou None), total, com_painel (s ou
    None), religou (s depois da queda em que o painel ligou, ou None se ficou ligado), idade (s no primeiro pacote ou None), origem (`app` ou `calculado`), final}."""
    saida, fim_anterior = [], None
    for g in grupos:
        inicio = g[0]
        if fim_anterior is not None and tempo.assinado(inicio.segundos, fim_anterior) < 0:
            continue  # inside the interruption before (the Wi-Fi lost again while the image had not come back)
        # The `last dash packet N s before` is counted from the line that says it, not from the first line of the group (the watchdog's can come seconds earlier:
        # 13:02:03.759 against 13:02:06.071 made 98,3 s of what was ~96 s, 08/11/2026).
        ancora = next(((x, float(m.group(1))) for x in g for m in [ULTIMO_PACOTE.search(x.texto)] if m), None)
        perda = tempo.dif(ancora[0].segundos, ancora[1]) if ancora else inicio.segundos
        depois = bloco[bloco.index(g[-1]) + 1:]
        voltou = next((x for x in depois if "→ STREAMING" in x.texto), None)
        fim = voltou if voltou is not None else bloco[-1]
        fim_anterior = fim.segundos
        entre = depois[:depois.index(voltou)] if voltou is not None else depois
        idade = next(((int(m.group(1)), x) for x in reversed(entre) for m in [IDADE.search(x.texto)] if m), None)
        unido = next((x for x in entre if x.etiqueta == "wifi" and x.texto.startswith(UNIDO)), None)
        item = {"queda": inicio, "voltou": voltou, "final": voltou is None, "total": tempo.dif(fim.segundos, perda), "com_painel": None, "religou": None,
                "idade": None, "origem": "calculado", "perda": perda, "unido": unido}
        if idade is not None:
            n, linha_idade = idade
            item["idade"] = n
            item["com_painel"], item["religou"] = _com_o_painel(fim.segundos, perda, n, tempo.dif(linha_idade.segundos, perda))
        if voltou is not None:
            do_app = next((m for x in depois[depois.index(voltou):depois.index(voltou) + 6] for m in [IMAGEM_VOLTOU.search(x.texto)] if m), None)
            if do_app:
                item["total"], item["origem"] = float(do_app.group(1)), "app"
                if do_app.group(3):
                    item["com_painel"] = float(do_app.group(3))
                    item["religou"] = float(do_app.group(5)) if do_app.group(5) else None
        saida.append(item)
    return saida


def texto_da_interrupcao(i):
    """A linha do tempo de uma interrupção: `sem imagem 223,4 s desde a queda das 17:44:06 … (86,5 s com o painel ligado: o painel religou 136,9 s depois da queda)`."""
    hora = i["queda"].hora[:8]
    if i["final"]:
        if i["unido"] is not None:
            return (f"sem imagem desde a queda das {hora} até o fim da projeção ({tempo.segundos_texto(i['total'])}): o painel voltou ao Wi-Fi às {i['unido'].hora[:8]} mas a imagem não voltou "
                    "(a última interrupção, que o resumo não conta)")
        return (f"sem imagem desde a queda das {hora} até o fim da projeção ({tempo.segundos_texto(i['total'])}): o painel não voltou (moto desligada?) "
                "(a última interrupção, que o resumo não conta)")
    longa = i["total"] >= CONTADOR_VOLTA_S
    if i["com_painel"] is None:
        painel = "tempo com o painel ligado: não sai (sem a idade do painel no primeiro pacote)"
    elif i["religou"] is None:
        painel = f"{tempo.segundos_texto(i['com_painel'])} com o painel ligado: o painel ficou ligado, só o Wi-Fi caiu e voltou"
    else:
        pelo_menos = "pelo menos " if longa and i["origem"] == "calculado" else ""
        painel = f"{pelo_menos}{tempo.segundos_texto(i['com_painel'])} com o painel ligado: o painel religou {tempo.segundos_texto(i['religou'])} depois da queda"
        if i["idade"] is not None:
            painel += f" (idade {i['idade']} s no primeiro pacote)"
    origem = "linha do app" if i["origem"] == "app" else "calculado do registro"
    return f"sem imagem {tempo.segundos_texto(i['total'])} desde a queda das {hora} ({painel}) [{origem}]"


def problemas_das_interrupcoes(interrupcoes_):
    """Uma interrupção com o painel ligado por `COM_PAINEL_LONGO_S` s ou mais é lenta demais (a partida de 07/11 levou 11,7 a 27,9 s do painel ligado ao unido, 22 s até transmitir)."""
    saida = []
    for i in interrupcoes_:
        if not i["final"] and i["com_painel"] is not None and i["com_painel"] >= COM_PAINEL_LONGO_S:
            saida.append(f"{i['voltou'].hora}: o painel ficou {tempo.segundos_texto(i['com_painel'])} ligado e sem imagem depois da queda das {i['queda'].hora[:8]} "
                         f"(a partida leva ~22 s): {texto_da_interrupcao(i)}")
    return saida


RESPOSTA_AO_PEDIDO = re.compile(r"(?<!not )the answer to REau's request #(\d+)")
UNIDO_PELO_CELULAR = "joined by the phone itself"


def _quem_uniu(texto):
    """O que dizer de uma união que o app não viu na varredura: de quem foi, pelo que a linha `connected` diz (`the answer to REau's request #4`: foi o pedido do REau, o painel
    só não estava na última varredura do app; `joined by the phone itself`: o celular o uniu sozinho; nada disso, uma build que não dizia: o texto de antes)."""
    if UNIDO_PELO_CELULAR in texto:  # first: that line also says `not the answer to REau's request #N`
        return "o app não viu o painel na varredura antes de ele ser unido (o celular o uniu sozinho, por sugestão ou rede salva)"
    pedido = RESPOSTA_AO_PEDIDO.search(texto)
    if pedido:
        return (f"o app não viu o painel na última varredura antes de ele ser unido, mas a união foi a resposta ao pedido #{pedido.group(1)} do REau "
                "(não foi o Android sozinho)")
    # Only a line that says it is "the phone itself": this one says nothing of who asked, so it does not say "alone" (it may well have been REau's own request).
    return "o app não viu o painel na varredura antes de ele ser unido (a linha `connected` não diz de quem foi a união: pode ter sido o pedido do REau ou o celular sozinho)"


def ligado_unido(bloco, interrupcoes_):
    """{id da linha `connected`: texto} de `painel ligado → unido`, pela idade do painel no primeiro pacote: quanto o painel levou, desde que ligou, para ser unido e para transmitir;
    e de que partida ou queda é: a partida, o painel que religou (a idade zera) ou o Wi-Fi que caiu e voltou com o painel ligado. Só os `connected` com a idade lida até
    `JANELA_IDADE_S` s depois."""
    saida, anterior = {}, bloco[0]
    for k, x in enumerate(bloco):
        if x.etiqueta != "wifi" or not x.texto.startswith(UNIDO):
            continue
        achada = next(((int(m.group(1)), y) for y in bloco[k + 1:k + 400] for m in [IDADE.search(y.texto)]
                       if m and 0 <= tempo.assinado(y.segundos, x.segundos) <= JANELA_IDADE_S), None)
        if achada is None:
            anterior = x
            continue
        n, linha_idade = achada
        liga_antes = n - tempo.assinado(linha_idade.segundos, x.segundos)  # o painel ligou `liga_antes` s antes do `connected`
        streaming = next((y for y in bloco[k + 1:] if "→ STREAMING" in y.texto), None)
        partes = [f"unido {tempo.segundos_texto(liga_antes)} depois de ligar (idade {n} s no primeiro pacote)"]
        if streaming is not None:
            partes.append(f"transmitindo {tempo.segundos_texto(liga_antes + tempo.dif(streaming.segundos, x.segundos))} depois de ligar")
        queda = next((i for i in interrupcoes_ if i["voltou"] is not None and tempo.assinado(x.segundos, i["perda"]) >= 0
                      and tempo.assinado(i["voltou"].segundos, x.segundos) >= 0), None)
        if queda is None:
            contexto = "partida"
        else:
            depois_da_queda = tempo.dif(x.segundos, queda["perda"]) - liga_antes
            contexto = (f"o painel religou {tempo.segundos_texto(depois_da_queda)} depois da queda das {queda['queda'].hora[:8]}" if depois_da_queda > 0
                        else f"o painel ficou ligado: o Wi-Fi caiu às {queda['queda'].hora[:8]} e voltou")
            if tempo.dif(x.segundos, queda["perda"]) >= CONTADOR_VOLTA_S:
                contexto += " (queda de 256 s ou mais: o contador dá a volta, a idade é um mínimo)"
        viu = any(y.etiqueta == "wifi" and ("the dash: '" in y.texto or "showed up in the scan" in y.texto)
                  for y in bloco[bloco.index(anterior):k])
        if not viu:
            partes.append(_quem_uniu(x.texto))
        saida[id(x)] = f"painel ligado → unido: {'; '.join(partes)} [{contexto}]"
        anterior = x
    return saida
