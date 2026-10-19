"""Lê registros do REau puxados do celular e mostra a linha do tempo resumida, acusando os problemas conhecidos.

    python -B scripts\\registro\\ler_registro.py <arquivo ou pasta> [...]

Lê os arquivos `ride-*.log` (uma pasta inteira, ou arquivos soltos), em ordem de nome, e escreve:

- a linha do tempo de cada projeção: ligou, Android Auto conectado, painel no Wi-Fi, transmitindo, primeiro quadro
  confirmado, quedas, vigias, marcas do piloto, travamentos, estado térmico e bateria, desligou, o resumo, e a pior
  leitura do Android Auto presa no decodificador (o `readHeld` das linhas aa-stats);
- os problemas conhecidos: transmitindo sem confirmação do primeiro quadro, vigias, buracos nas linhas de 5 s (ou no
  resumo), quedas seguidas (duas em menos de 60 s), temperatura subindo, o app travou, o Android Auto calado (15 s
  sem mandar nada) e o Android Auto que aceita e não responde (três negociações seguidas sem resposta).

As linhas de uma mesma queda são uma queda só; buraco nas linhas de 5 s só com o painel no ar; o calor
da bateria só conta sem carregador e com a tela ligada; e no fim de cada projeção a linha `vídeo:` (taxa, quadro, quadros-chave,
redução, trocas de perfil).
"""
import re
import sys
from dataclasses import dataclass
from pathlib import Path

# A warning or an error has its level at the start of its text ("W: ", "E: "); the reader keeps it apart.
NIVEL = re.compile(r"^([WE]): (.*)$")
LINHA = re.compile(r"^(\d\d:\d\d:\d\d\.\d{3})\s+(?:\+\s*\d+ms\s+)?(?:\[([\w-]+)\]\s+)?(.*)$")
LIGOU = ("---- projeção ligada", "==== session start: projection")
DESLIGOU = ("---- projeção desligada", "==== session end: projection")
TERMICO = ["none", "light", "moderate", "severe", "critical", "emergency", "shutdown"]
BURACO_5S = 6.0  # a linha de 5 s chegando mais de 1 s atrasada
QUEDAS_SEGUIDAS_S = 60.0
QUEDA_JUNTA_S = 10.0  # the signs of one fall (Wi-Fi lost, dash silent, session ended) come within this of each other
DESLIGAMENTO_S = 3.0
NEGOCIACOES_SEM_RESPOSTA = 3
NEGOCIACAO_OK = "Handshake successful"
NEGOCIACAO_FALHOU = "AaReceiver: the handshake failed"
CALADO = "WiFi read timeout (15s)"
BYE = "AapTransport stopping and sending byebye"
BYE_S = 2.0
# The detailed log's lines (~98% of a file with the switch on); nothing here reads them, so they are skipped.
DETALHE = "  [detail] "
# Sinais de que a transmissão acabou ou recomeçou entre duas linhas de 5 s: o intervalo não é buraco.
SEM_VIDEO = ("session fail", "video loop stop", "→ STREAMING")

# (o que procurar no texto, o que dizer na linha do tempo)
EVENTOS = [
    # Conectado só com a negociação respondida; discar e o servidor aceitar não basta.
    (NEGOCIACAO_OK, "Android Auto conectado"),
    (NEGOCIACAO_FALHOU, "Android Auto não respondeu à negociação"),
    (CALADO, "Android Auto calado (15 s sem mandar nada)"),
    ("AaReceiver: session ended (clean=false", "Android Auto caiu"),
    ("→ STREAMING", "transmitindo"),
    ("dash DECODED first IDR", "primeiro quadro confirmado pelo painel"),
    ("link lost — reconnecting", "Wi-Fi da moto caiu"),
    ("bike network lost", "Wi-Fi da moto perdido"),
    # O Wi-Fi no instante de um vigia do painel (sinal, velocidades, banda) e o último minuto, para dizer
    # Wi-Fi fraco de painel calado. A linha é longa de propósito: o minuto de sinal é o que interessa.
    ("link at failure:", "Wi-Fi na falha"),
    ("watchdog:", "vigia"),
    ("Decoder restart requested", "vigia do decodificador"),
    ("the app crashed", "o app travou"),
]
QUEDAS = ("link lost — reconnecting", "RX watchdog", "bike network lost", "AaReceiver: session ended (clean=false")


@dataclass
class Linha:
    arquivo: str
    hora: str
    etiqueta: str
    texto: str
    nivel: str = ""  # "W" or "E" when the line says so at the start of its text, else empty

    @property
    def segundos(self):
        h, m, s = self.hora.split(":")
        return int(h) * 3600 + int(m) * 60 + float(s)


def ler(caminhos):
    """As linhas de todos os `ride-*.log` dos caminhos (pastas ou arquivos), em ordem de nome."""
    arquivos = []
    for c in map(Path, caminhos):
        arquivos += sorted(c.glob("ride-*.log")) if c.is_dir() else [c]
    linhas = []
    for a in arquivos:
        for bruta in a.read_text(encoding="utf-8", errors="replace").splitlines():
            if DETALHE in bruta:
                continue
            m = LINHA.match(bruta)
            if m:
                nivel = NIVEL.match(m.group(3))
                texto, letra = (nivel.group(2), nivel.group(1)) if nivel else (m.group(3), "")
                linhas.append(Linha(a.name, m.group(1), m.group(2) or "", texto, letra))
    return linhas


def projecoes(linhas):
    """As linhas de cada projeção, da marca de ligar à de desligar (ou ao fim do arquivo)."""
    blocos, atual = [], None
    for x in linhas:
        if x.texto.startswith(LIGOU):
            atual = [x]
            blocos.append(atual)
        elif atual is not None:
            atual.append(x)
            if x.texto.startswith(DESLIGOU):
                atual = None
    return blocos


def _do_desligamento(x, bloco):
    """O fim da sessão do Android Auto que o próprio desligamento provoca (menos de 3 s antes da marca): não é queda."""
    fim = bloco[-1]
    return ("AaReceiver: session ended" in x.texto and fim.texto.startswith(DESLIGOU)
            and fim.segundos - x.segundos < DESLIGAMENTO_S)


def _causa_da_queda(x):
    """O nome curto do que uma linha de queda diz."""
    if "link lost — reconnecting" in x.texto or "bike network lost" in x.texto:
        return "Wi-Fi da moto"
    if "RX watchdog" in x.texto:
        return "painel calado"
    return "Android Auto caiu"


def _grupos_de_queda(bloco):
    """As linhas de queda de uma mesma queda (o Wi-Fi caiu, a rede da moto se perdeu e, até 10 s depois,
    o vigia do painel) são uma queda só; cada grupo é a lista de linhas, a primeira marcando a hora."""
    grupos = []
    for x in bloco:
        if not (any(q in x.texto for q in QUEDAS) and not _nao_e_queda(x, bloco)):
            continue
        if grupos and x.segundos - grupos[-1][-1].segundos <= QUEDA_JUNTA_S:
            grupos[-1].append(x)
        else:
            grupos.append([x])
    return grupos


def _resumo_da_queda(grupo, bloco):
    """A linha de uma queda: a causa de cada sinal e quanto depois veio; mais o Wi-Fi de antes, se o registro o tem."""
    causas = []
    for x in grupo:
        nome = _causa_da_queda(x)
        if nome not in [c for c, _ in causas]:
            causas.append((nome, x.segundos - grupo[0].segundos))
    partes = [nome if atraso < 0.5 else f"{nome} {atraso:.0f} s depois" for nome, atraso in causas]
    saida = [f"{grupo[0].hora}  queda ({', '.join(partes)})"]
    antes = [y for y in bloco if y.etiqueta == "link" and not y.texto.startswith("link at failure")
             and 0 <= grupo[0].segundos - y.segundos <= 70]
    if antes:
        saida.append(f"{grupo[0].hora}  Wi-Fi antes da queda: {antes[-1].texto.split(' | ')[0]}")
    return saida


def _fim_pedido(x, bloco):
    """O fim da sessão que o próprio REau pediu com o bye (desligar, ou uma sessão nova para os ajustes) até 2 s antes:
    não é queda. Versões antigas do app registravam esse fim como `clean=false` mesmo com o bye respondido."""
    return "AaReceiver: session ended" in x.texto and any(
        BYE in y.texto and 0 <= x.segundos - y.segundos < BYE_S for y in bloco)


def _nao_e_queda(x, bloco):
    return _do_desligamento(x, bloco) or _fim_pedido(x, bloco)


def _device(texto, campo):
    m = re.search(rf"\b{campo}=([^\s%C]+)", texto)
    return m.group(1) if m else None


def linha_do_tempo(linhas):
    saida = []
    for bloco in projecoes(linhas):
        bateria = [b for x in bloco if x.texto.startswith("device ") for b in [_device(x.texto, "battery")] if b and b != "?"]
        termico_antes = None
        quedas = {id(g[0]): g for g in _grupos_de_queda(bloco)}
        for x in bloco:
            t = x.texto
            if id(x) in quedas:
                saida += _resumo_da_queda(quedas[id(x)], bloco)
            if t.startswith(LIGOU):
                saida.append(f"{x.hora}  projeção ligada ({x.arquivo})")
            elif t.startswith(DESLIGOU):
                if bateria:
                    saida.append(f"{x.hora}  bateria {bateria[0]}% → {bateria[-1]}%")
                presa = _leitura_presa(bloco)
                if presa:
                    saida.append(f"{x.hora}  {presa}")
                video = _video(bloco)
                if video:
                    saida.append(f"{x.hora}  {video}")
                saida.append(f"{x.hora}  projeção desligada")
            elif x.etiqueta == "mark":
                saida.append(f"{x.hora}  marca do piloto {t.split()[-1]}")
            elif x.etiqueta == "settings" and t.startswith("settings in use: "):
                saida.append(f"{x.hora}  {t}")  # the settings the ride was made with, at the start of each projection
            elif x.etiqueta == "wifi" and t.startswith("connected "):
                saida.append(f"{x.hora}  painel no Wi-Fi")
            elif t.startswith("resumo "):
                saida.append(f"{x.hora}  {t}")
            elif t.startswith("device "):
                termico = _device(t, "thermal")
                if termico_antes is not None and termico != termico_antes:
                    saida.append(f"{x.hora}  estado térmico {termico_antes} → {termico}")
                termico_antes = termico
            elif not _nao_e_queda(x, bloco):
                for procura, diz in EVENTOS:
                    if procura in t:
                        if diz.startswith("Wi-Fi na falha"):
                            saida.append(f"{x.hora}  {diz}: {t[len(procura):].strip()}")
                        else:
                            saida.append(f"{x.hora}  {diz}: {t[:110]}" if diz.startswith(("vigia", "o app")) else f"{x.hora}  {diz}")
                        break
    return saida



READ_HELD = re.compile(r"aa-stats ([\d.]+)s: .* readHeld=([\d.]+)/([\d.]+)ms")


def _leitura_presa(bloco):
    """O pior tempo que a leitura do Android Auto ficou presa entregando quadros ao decodificador, numa janela
    das linhas aa-stats e numa vez só; None quando o registro é de antes do campo."""
    janelas = [(x, m) for x in bloco for m in [READ_HELD.search(x.texto)] if m]
    if not janelas:
        return None
    pior_janela = max(janelas, key=lambda j: float(j[1].group(2)))
    pior_vez = max(janelas, key=lambda j: float(j[1].group(3)))
    return (f"leitura do Android Auto presa no decodificador: pior janela {float(pior_janela[1].group(2)):.1f} ms em "
            f"{float(pior_janela[1].group(1)):g} s ({pior_janela[0].hora}), pior vez {float(pior_vez[1].group(3)):.1f} ms "
            f"({pior_vez[0].hora})")


VIDEO_5S = re.compile(r"kbps=(\d+) .*?frame=(\d+)B")
VIDEO_COMPLETA = re.compile(r"full=(true|false)")
VIDEO_IDR = re.compile(r"idr=(\d+)\(max (\d+)B/(\d+)pkt\)")
VIDEO_REDUCAO = re.compile(r"reduction (\w+)")
VIDEO_DESCARTADOS = re.compile(r"(\d+) packets discarded")
VIDEO_AVISO = re.compile(r"notice 09 06 55 (#\d+), ([\d,.]+) s into")


def _video(bloco):
    """A qualidade do vídeo de uma projeção, só com o que o registro traz: a taxa média em movimento e a pior janela,
    o quadro médio, os quadros-chave (`idr=`, quando a linha os traz), a redução da imagem, as trocas de perfil de
    navegação, o perfil 2 que limitou o bitrate, os pacotes descartados do início e o primeiro aviso 09 06 55. None sem nenhuma linha de 5 s."""
    janelas = [x for x in bloco if x.texto.startswith("video fps target=")]
    taxas = []
    for x in janelas:
        m = VIDEO_5S.search(x.texto)
        if m:
            cheia = VIDEO_COMPLETA.search(x.texto)
            taxas.append((int(m.group(1)), int(m.group(2)), bool(cheia and cheia.group(1) == "true")))
    if not taxas:
        return None
    em_movimento = [k for k, _, cheia in taxas if cheia] or [k for k, _, _ in taxas]
    partes = [f"kbps médio {sum(em_movimento) / len(em_movimento):.0f} em movimento (pior janela {max(k for k, _, _ in taxas)})",
              f"quadro médio {sum(q for _, q, _ in taxas) / len(taxas):.0f} B"]
    idr = [m for x in janelas for m in [VIDEO_IDR.search(x.texto)] if m]
    if idr:
        partes.append(f"quadros-chave {sum(int(m.group(1)) for m in idr)} (o maior {max(int(m.group(2)) for m in idr)} B "
                      f"em {max(int(m.group(3)) for m in idr)} pacotes)")
    reducao = [m.group(1) for x in bloco for m in [VIDEO_REDUCAO.search(x.texto)] if m and x.texto.startswith("video loop start")]
    if reducao:
        partes.append(f"redução {reducao[-1]}")
    trocas = sum(1 for x in bloco if x.texto.startswith("nav profile:"))
    if trocas:
        partes.append(f"{trocas} trocas de perfil")
    if any("profile 2 measured" in x.texto for x in bloco):
        partes.append("perfil 2 limitou o bitrate")
    descartados = sum(int(m.group(1)) for x in bloco for m in [VIDEO_DESCARTADOS.search(x.texto)] if m)
    if descartados:
        partes.append(f"{descartados} pacotes descartados")
    aviso = next((m for x in bloco for m in [VIDEO_AVISO.search(x.texto)] if m), None)
    if aviso:
        partes.append(f"aviso 09 06 55 {aviso.group(1)} aos {aviso.group(2)} s")
    return "vídeo: " + ", ".join(partes)


def problemas(linhas):
    saida = []
    for bloco in projecoes(linhas):
        inicio = f"{bloco[0].hora} ({bloco[0].arquivo})"
        textos = [x.texto for x in bloco]
        if any("→ STREAMING" in t for t in textos) and not any("dash DECODED first IDR" in t for t in textos):
            saida.append(f"projeção de {inicio}: transmitindo sem confirmação do primeiro quadro pelo painel")
        for x in bloco:
            if "watchdog:" in x.texto or "Decoder restart requested" in x.texto:
                saida.append(f"{x.hora}: vigia — {x.texto[:120]}")
            if "the app crashed" in x.texto:
                saida.append(f"{x.hora}: o app travou — {x.texto[:120]}")
        saida += _buracos(bloco) + _quedas(bloco) + _temperatura(bloco) + _sem_resposta(bloco)
    return saida


def _sem_resposta(bloco):
    """O Android Auto calado (a leitura desistiu em 15 s) e o que aceita a conexão e não responde."""
    saida = [f"{x.hora}: Android Auto calado, 15 s sem mandar nada (a imagem do painel parou)" for x in bloco if CALADO in x.texto]
    seguidas, inicio = 0, None
    for x in bloco:
        if NEGOCIACAO_FALHOU in x.texto:
            seguidas += 1
            inicio = inicio or x.hora
        elif NEGOCIACAO_OK in x.texto:
            seguidas, inicio = _fecha_sem_resposta(saida, seguidas, inicio)
    _fecha_sem_resposta(saida, seguidas, inicio)
    return saida


def _fecha_sem_resposta(saida, seguidas, inicio):
    if seguidas >= NEGOCIACOES_SEM_RESPOSTA:
        saida.append(f"{inicio}: Android Auto aceita e não responde ({seguidas} negociações seguidas sem resposta)")
    return 0, None


def _buracos(bloco):
    saida = []
    cinco = [x for x in bloco if x.texto.startswith("video fps target=")]
    for a, b in zip(cinco, cinco[1:]):
        # With the dash session over in between (it failed, the video stopped, a new stream began) nothing was due.
        entre = bloco[bloco.index(a):bloco.index(b)]
        if any(sinal in y.texto for y in entre for sinal in SEM_VIDEO):
            continue
        if b.segundos - a.segundos > BURACO_5S:
            saida.append(f"{b.hora}: buraco nas linhas de 5 s — {b.segundos - a.segundos:.1f} s desde a anterior")
    for x in bloco:
        m = re.search(r"gap max=([\d.]+)s", x.texto)
        if x.texto.startswith("resumo ") and m and float(m.group(1)) > 1.0:
            saida.append(f"{x.hora}: buraco de {float(m.group(1)):.1f} s sem quadro (resumo)")
    return saida


def _quedas(bloco):
    grupos = _grupos_de_queda(bloco)
    return [f"{b[0].hora}: quedas seguidas — {b[0].segundos - a[-1].segundos:.0f} s depois da anterior"
            for a, b in zip(grupos, grupos[1:]) if b[0].segundos - a[-1].segundos < QUEDAS_SEGUIDAS_S]


def _em_uso(texto):
    """Uma olhada do celular sem carregador e sem a tela apagada (o que não diz, vale): só nela o calor é do uso."""
    return _device(texto, "charging") != "yes" and _device(texto, "screen") != "off"


def _temperatura(bloco):
    olhadas = [x for x in bloco if x.texto.startswith("device ")]
    saida = []
    termicos = [_device(x.texto, "thermal") for x in olhadas]
    niveis = [TERMICO.index(t) for t in termicos if t in TERMICO]
    if niveis and max(niveis) >= TERMICO.index("moderate") and max(niveis) > niveis[0]:
        saida.append(f"temperatura subindo: estado térmico {TERMICO[niveis[0]]} → {TERMICO[max(niveis)]}")
    # Com o carregador ligado (a bateria esquenta carregando) ou a tela apagada o calor não é do uso: não conta.
    graus = [float(g) for x in olhadas if _em_uso(x.texto) for g in [_device(x.texto, "temp")] if g and g != "?"]
    if graus and max(graus) - graus[0] >= 5.0:
        saida.append(f"temperatura subindo: bateria de {graus[0]:.1f} °C a {max(graus):.1f} °C (sem carregador, tela ligada)")
    return saida


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    linhas = ler(argv)
    print("\n".join(linha_do_tempo(linhas)) or "nenhuma projeção nos arquivos")
    achados = problemas(linhas)
    print("\nPROBLEMAS:" if achados else "\nnenhum problema conhecido")
    for p in achados:
        print(f"  - {p}")
    return 1 if achados else 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.exit(main(sys.argv[1:]))
