"""As gravações de uma coleta (`sessions/` e `video/`), lidas ao lado do registro (item 36 do levantamento de 11/11/2026; antes eram lidas à mão com `extrair.py` e `indice_video.py`).

- `sessions/reau_AAAAMMDD_HHMMSS_mmm[.1].aasession`: a gravação das mensagens do Android Auto (formato de `SessionRecorder.kt`: `REAA`, versão, relógio, e por mensagem ms desde a
  primeira, canal, flags, tamanho, carga). A parte `.1` é a mais antiga quando o anel rolou (`session recording rolled over`). Aqui se lê só o cabeçalho de cada mensagem (sem carregar
  o arquivo): quantas mensagens, quantos segundos, quantos quadros de vídeo (canal 2, tipo 0), e se o arquivo acaba no meio de uma mensagem (a coleta pegou a gravação aberta).
- `video/reau_AAAAMMDD_HHMMSS_mmm[_pNN].h264` e o `.csv` (índice): quadros, segundos, quadros-chave, kbps, e o aviso do índice que não bate com o arquivo (`indice_video.py`).

O que o leitor conta com isso: a ocupação das duas pastas contra o teto do anel (1 GB na debug), cada gravação ligada à projeção em que nasceu (pelo nome, que é a hora do início) com a
cobertura dela (segundos gravados de segundos de projeção), o `frames=` do resumo contra os quadros do `.h264`, o que o app disse ter gravado (`session recording closed`,
`video recording stopped early`) contra o que está na pasta (um arquivo que o app diz ter gravado e que não está foi apagado pelo teto, ou não veio na coleta) e as linhas
`recordings space` (quantos arquivos o teto apagou). Nenhum conteúdo das mensagens é lido além do tipo do quadro de vídeo: nada de mapa, nome nem posição sai daqui.

`ler_sessao`, `ler_video`, `ocupacao` e `secao` abrem arquivo; o resto é puro.
"""
import os
import re
import struct
import sys
from datetime import datetime, timedelta
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
sys.path.insert(0, str(AQUI.parent / "dev" / "teste_cego"))  # indice_video.py: o índice do vídeo em dois formatos
import indice_video  # noqa: E402
import tempo  # noqa: E402

ASSINATURAS = (b"REAA", b"PGAA")  # PGAA: a assinatura das gravações de 15/10/2026, o resto do formato é o mesmo
CABECALHO = 13
CANAL_VIDEO = 2
TETO_MB = 1024  # o anel das gravações na debug (`recordings space: … left of 1048576 KB`)
OCUPACAO_AVISO_PCT = 80
NOME = re.compile(r"^reau_(\d{4})(\d\d)(\d\d)_(\d\d)(\d\d)(\d\d)_(\d{3})(?P<marca>_marca)?(?:_p(\d+))?(?P<parte>\.1)?\.(aasession|h264|csv)$")
RESUMO_QUADROS = re.compile(r"^resumo .*?\bframes=(\d+)\b")
SESSAO_FECHADA = re.compile(r"session recording closed: (\d+) messages, (\d+) bytes( in the last part)?, \S*?/([\w.]+\.aasession) — (?:covers the first (\d+) s|the last part covers (\d+) s)")
SESSAO_ROLOU = re.compile(r"session recording rolled over \(#(\d+)\): ([\w.]+\.aasession) keeps the first (\d+) s")
VIDEO_PARADO = re.compile(r"video recording (?:stopped early|part \d+ complete, the next follows): (\d+) frames, (\d+) bytes, \S*?/([\w.]+\.h264)")
ESPACO = re.compile(r"recordings space: (\d+) files? removed \((\d+) KB\), (\d+) KB left of (\d+) KB")
FOLGA_INICIO_S = 60.0


def inicio_do_nome(nome):
    """O instante (datetime) em que a gravação começou, da hora do nome do arquivo; None se o nome não é de uma gravação do REau."""
    m = NOME.match(nome)
    if not m:
        return None
    a, mes, d, h, mi, s, ms = (int(m.group(i)) for i in range(1, 8))
    try:
        return datetime(a, mes, d, h, mi, s) + timedelta(milliseconds=ms)
    except ValueError:
        return None


def ler_sessao(caminho):
    """{nome, bytes, assinatura (bool), mensagens, segundos, quadros, cortada} de uma `.aasession`, lendo só os cabeçalhos das mensagens. `cortada`: o arquivo acaba no meio de
    uma mensagem. Um arquivo menor que o cabeçalho ou de outra assinatura vem com `assinatura` falso."""
    caminho = Path(caminho)
    tamanho = caminho.stat().st_size
    saida = {"nome": caminho.name, "bytes": tamanho, "assinatura": False, "mensagens": 0, "segundos": 0.0, "quadros": 0, "cortada": False}
    with caminho.open("rb") as f:
        cabecalho = f.read(CABECALHO)
        if len(cabecalho) < CABECALHO or cabecalho[:4] not in ASSINATURAS:
            return saida
        saida["assinatura"] = True
        pos = CABECALHO
        while True:
            h = f.read(10)
            if not h:
                break
            if len(h) < 10:
                saida["cortada"] = True
                break
            t, canal, _, tam = struct.unpack(">iBBi", h)
            if tam < 0 or pos + 10 + tam > tamanho:
                saida["cortada"] = True
                break
            if canal == CANAL_VIDEO and tam >= 2:
                if f.read(2) == b"\x00\x00":
                    saida["quadros"] += 1
                f.seek(tam - 2, os.SEEK_CUR)
            else:
                f.seek(tam, os.SEEK_CUR)
            pos += 10 + tam
            saida["mensagens"] += 1
            saida["segundos"] = max(saida["segundos"], t / 1000.0)
    return saida


def ler_video(h264):
    """{nome, bytes, quadros, segundos, chaves, kbps, avisos, sem_indice} de um `.h264` com o `.csv` ao lado (`indice_video`); sem o índice só o tamanho. O índice que não bate com o
    arquivo, ou que não se lê, vira aviso."""
    h264 = Path(h264)
    csv = h264.with_suffix(".csv")
    saida = {"nome": h264.name, "bytes": h264.stat().st_size, "quadros": 0, "segundos": 0.0, "chaves": 0, "kbps": 0.0, "avisos": [], "sem_indice": not csv.exists()}
    if saida["sem_indice"]:
        return saida
    try:
        indice = indice_video.ler(csv, h264)
    except (ValueError, OSError) as e:
        saida["avisos"].append(f"{csv.name}: o índice não se lê ({e})")
        return saida
    saida["quadros"] = len(indice.quadros)
    saida["chaves"] = sum(1 for q in indice.quadros if q[3])
    saida["segundos"] = (indice.quadros[-1][1] - indice.quadros[0][1]) / 1000.0 if indice.quadros else 0.0
    saida["kbps"] = saida["bytes"] * 8 / 1000.0 / saida["segundos"] if saida["segundos"] > 0 else 0.0
    saida["avisos"] = list(indice.avisos)
    return saida


def ocupacao(raiz):
    """(bytes de sessions/, bytes de video/) de uma pasta de coleta; 0 para a que não existe."""
    def pasta(p):
        return sum(f.stat().st_size for f in (Path(raiz) / p).glob("*") if f.is_file()) if (Path(raiz) / p).is_dir() else 0
    return pasta("sessions"), pasta("video")


def contas_do_app(linhas):
    """O que o app disse das gravações, em todas as linhas: {sessoes: {nome: {mensagens, bytes, cobre_s}}, rolagens: [(n, nome, s)], videos: {nome: {quadros, bytes}},
    espaco: [(apagados, kb_apagados, kb_ficou, kb_teto)]}."""
    contas = {"sessoes": {}, "rolagens": [], "videos": {}, "espaco": []}
    for x in linhas:
        t = x.texto
        m = SESSAO_FECHADA.search(t)
        if m:
            contas["sessoes"][m.group(4)] = {"mensagens": int(m.group(1)), "bytes": int(m.group(2)), "ultima_parte": bool(m.group(3)), "cobre_s": int(m.group(5) or m.group(6))}
        m = SESSAO_ROLOU.search(t)
        if m:
            contas["rolagens"].append((int(m.group(1)), m.group(2), int(m.group(3))))
        m = VIDEO_PARADO.search(t)
        if m:
            contas["videos"][m.group(3)] = {"quadros": int(m.group(1)), "bytes": int(m.group(2))}
        m = ESPACO.search(t)
        if m:
            contas["espaco"].append(tuple(int(m.group(i)) for i in range(1, 5)))
    return contas


def atribuir(gravacoes, blocos):
    """{id do bloco: [gravações]}: cada gravação vai para uma só projeção, a última que começou até `FOLGA_INICIO_S` s depois da hora do nome da gravação e ainda não acabou antes dela.
    Gravação cujo nome não traz a hora, ou que nasceu fora de qualquer projeção lida, não vai para nenhuma. Projeção cujo registro não traz o dia fica sem gravações."""
    janelas = []
    for b in blocos:
        inicio, fim = tempo.instante(b[0]), tempo.instante(b[-1])
        if inicio is not None and fim is not None:
            janelas.append((b, inicio, fim if fim >= inicio else fim + timedelta(days=1)))
    saida = {id(b): [] for b in blocos}
    for g in gravacoes:
        quando = inicio_do_nome(g["nome"])
        if quando is None:
            continue
        donos = [(inicio, b) for b, inicio, fim in janelas if inicio - timedelta(seconds=FOLGA_INICIO_S) <= quando <= fim]
        if donos:
            saida[id(max(donos, key=lambda d: d[0])[1])].append(g)
    return saida


def _mb(b):
    return f"{b / 1048576:.1f}".replace(".", ",") + " MB"


def _linha_sessao(s, conta):
    partes = [f"{_mb(s['bytes'])}", f"{s['segundos']:.0f} s", f"{s['mensagens']} mensagens", f"{s['quadros']} quadros de vídeo"]
    if s["nome"].endswith(".1.aasession"):
        partes.append("parte anterior (o anel rolou)")
    if "_marca" in s["nome"]:
        partes.append("cópia congelada pela Marca")
    if not s["assinatura"]:
        partes.append("NÃO é uma gravação (assinatura ou tamanho)")
    if s["cortada"]:
        partes.append("acaba no meio de uma mensagem (a coleta pegou a gravação aberta)")
    if conta is not None and not conta["ultima_parte"]:  # com o anel rolado o app conta as mensagens das duas partes e os bytes só da última
        if conta["bytes"] != s["bytes"]:
            partes.append(f"o app fechou com {conta['bytes']} B e a pasta tem {s['bytes']} B")
        elif conta["mensagens"] != s["mensagens"]:
            partes.append(f"o app contou {conta['mensagens']} mensagens e a leitura achou {s['mensagens']}")
    return f"{s['nome']}: " + ", ".join(partes)


def _linha_video(v, conta):
    if v["sem_indice"]:
        return f"{v['nome']}: {_mb(v['bytes'])}, sem o índice .csv ao lado"
    partes = [_mb(v["bytes"]), f"{v['quadros']} quadros", f"{v['segundos']:.0f} s", f"{v['chaves']} quadros-chave", f"{v['kbps']:.0f} kbps médio"]
    if conta is not None and conta["quadros"] != v["quadros"]:
        partes.append(f"o app contou {conta['quadros']} quadros e o índice tem {v['quadros']}")
    partes += v["avisos"]
    return f"{v['nome']}: " + ", ".join(partes)


def _cobertura(sessoes, bloco):
    """`gravada 1815 + 1090 s de 2905 s de projeção` ou None: os segundos das sessões da projeção (todas as partes) contra a duração dela."""
    total = sum(s["segundos"] for s in sessoes)
    duracao = tempo.dif(bloco[-1].segundos, bloco[0].segundos)
    if duracao <= 0:
        return None
    mais = ": mais que a projeção lida (o registro acaba antes da gravação)" if total > duracao * 1.05 else ""
    return f"{total:.0f} s gravados de {duracao:.0f} s de projeção ({100 * total / duracao:.0f} %){mais}"


def secao(raiz, linhas, blocos):
    """As linhas da seção "GRAVAÇÕES DA COLETA" de uma pasta de coleta [raiz] (a que tem `sessions/` e/ou `video/`). Vazio se não tem nenhuma das duas. Só as gravações que nasceram
    durante as projeções lidas (com `FOLGA_INICIO_S` s de folga) são listadas uma a uma; as outras são contadas."""
    raiz = Path(raiz)
    pastas = {p: raiz / p for p in ("sessions", "video") if (raiz / p).is_dir()}
    if not pastas:
        return []
    contas = contas_do_app(linhas)
    s_bytes, v_bytes = ocupacao(raiz)
    total = s_bytes + v_bytes
    pct = 100 * total / (TETO_MB * 1048576)
    saida = [f"ocupação: sessions/ {_mb(s_bytes)} + video/ {_mb(v_bytes)} = {_mb(total)} de {TETO_MB} MB do anel ({pct:.0f} %)" +
             (f": perto do teto, o app apaga as mais antigas (coletar a cada 2 dias de uso)" if pct >= OCUPACAO_AVISO_PCT else "")]
    sessoes = [ler_sessao(f) for f in sorted(pastas.get("sessions", raiz).glob("*.aasession"))] if "sessions" in pastas else []
    videos = [ler_video(f) for f in sorted(pastas.get("video", raiz).glob("*.h264"))] if "video" in pastas else []
    de_sessoes, de_videos = atribuir(sessoes, blocos), atribuir(videos, blocos)
    ligadas = set()
    for bloco in blocos:
        mes = de_sessoes.get(id(bloco), [])
        vs = de_videos.get(id(bloco), [])
        if not mes and not vs:
            continue
        ligadas.update(g["nome"] for g in mes + vs)
        titulo = f"projeção de {bloco[0].hora[:8]} ({bloco[0].arquivo}):"
        if mes:
            cobertura = _cobertura(mes, bloco)
            saida.append(f"{titulo} {len(mes)} {'arquivo' if len(mes) == 1 else 'arquivos'} de sessão, {cobertura or 'sem duração'}")
            saida += ["    sessão " + _linha_sessao(s, contas["sessoes"].get(s["nome"])) for s in mes]
        elif pastas.get("sessions"):
            saida.append(f"{titulo} nenhuma sessão gravada na pasta (apagada pelo teto, ou não veio na coleta)")
        if vs:
            quadros = sum(v["quadros"] for v in vs)
            resumo = next((int(m.group(1)) for x in bloco for m in [RESUMO_QUADROS.match(x.texto)] if m), None)
            comparado = f"; o resumo da projeção diz frames={resumo}" + ("" if resumo == quadros else f" e os índices somam {quadros} ({quadros - resumo:+d})") if resumo is not None else ""
            saida.append(f"{titulo} {len(vs)} {'arquivo' if len(vs) == 1 else 'arquivos'} de vídeo, {quadros} quadros{comparado}")
            saida += ["    vídeo " + _linha_video(v, contas["videos"].get(v["nome"])) for v in vs]
    de_antes = sum(1 for g in sessoes + videos if g["nome"] not in ligadas)
    if de_antes:
        saida.append(f"{de_antes} arquivos de gravação de antes das projeções lidas (ou de outro dia): não listados")
    if contas["rolagens"]:
        saida.append("anel das sessões rolou: " + "; ".join(f"#{n} ({nome} guarda os primeiros {s} s da parte)" for n, nome, s in contas["rolagens"]))
    faltam = sorted(n for n in set(contas["sessoes"]) | set(contas["videos"]) if not (raiz / "sessions" / n).exists() and not (raiz / "video" / n).exists())
    if faltam:
        saida.append(f"{len(faltam)} gravações que o app diz ter fechado não estão na pasta (apagadas pelo teto ou fora desta coleta): {', '.join(faltam[:6])}{', …' if len(faltam) > 6 else ''}")
    if contas["espaco"]:
        saida.append(f"teto de pasta: {sum(e[0] for e in contas['espaco'])} arquivos apagados ({sum(e[1] for e in contas['espaco']) / 1024:.0f} MB) em {len(contas['espaco'])} vezes; "
                     f"a última deixou {contas['espaco'][-1][2] / 1024:.0f} MB de {contas['espaco'][-1][3] / 1024:.0f} MB")
    return saida


def raizes_da_coleta(caminhos):
    """As pastas de coleta (as que têm `sessions/` ou `video/`) a que os caminhos levam: o próprio caminho, ou a pasta de cima se ele é a `diag`. Sem repetir."""
    achadas = []
    for c in map(Path, caminhos):
        for candidata in ([c, c.parent] if c.name == "diag" else [c]):
            if candidata.is_dir() and any((candidata / p).is_dir() for p in ("sessions", "video")) and candidata not in achadas:
                achadas.append(candidata)
                break
    return achadas
