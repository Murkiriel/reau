#!/usr/bin/env python3
"""Teste cego de legibilidade a 200 kbps: reencoda as gravações `video/*.h264` de uma coleta com o `ffmpeg`, para o dono comparar o mapa original com o de 200 kbps (a chave
experimental "bitrate menor", item 17 do levantamento de 11/11/2026). O julgamento é do dono; esta ferramenta só prepara os pares.

Uso:  reencodar_200k.py COLETA_OU_PASTA_VIDEO SAIDA [--kbps 200] [--semente N] [--max-s S] [--aberto] [--ffmpeg CAMINHO] [--so-listar]
  COLETA_OU_PASTA_VIDEO  uma pasta de coleta (com `video/`) ou a própria `video/`; lê os `.h264` e o `.csv` ao lado (`indice_video.py`; sem o `.csv` usa 4 quadros/s).
  SAIDA                  onde escrever (criada se não existe). NUNCA dentro de `reau_registros/` nem dentro da pasta lida: a coleta é só leitura.
  --kbps                 o bitrate alvo do reencode (padrão 200: a chave experimental mira 200 kbps).
  --max-s                só os primeiros S segundos de cada gravação (padrão: inteira; uma coleta tem horas).
  --semente              a semente do sorteio de qual lado é o original (padrão 15).
  --aberto               sem o cego: grava `NOME.original.mp4` e `NOME.200k.mp4` (para ver os dois sabendo qual é qual).
  --so-listar            mostra os comandos do ffmpeg e não roda nenhum (serve também sem o ffmpeg instalado).
  --ffmpeg               o executável (padrão: o `ffmpeg` do PATH).

Por padrão é CEGO: de cada gravação saem `NNN_A.mp4` e `NNN_B.mp4`, um é o original (só empacotado em mp4, sem reencodar) e o outro o reencodado, sorteados; o sorteio vai para
`gabarito.json` na mesma pasta, que o dono só abre depois de escolher. O .h264 cru não traz o tempo dos quadros (o ritmo vai de 2 a 6 por segundo): os dois lados usam o ritmo médio do
índice (quadros ÷ segundos), então a velocidade do filme é a média, não a do momento. Sem som. O reencode é x264 com taxa limitada (`-maxrate` = o alvo), um passo.

Sem o `ffmpeg` o programa diz e sai com 3 (nada se instala aqui). Lê só; escreve só em SAIDA.
"""
import json
import random
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import indice_video  # noqa: E402

KBPS_PADRAO = 200
SEMENTE_PADRAO = 15
FPS_PADRAO = 4.0
PASTAS_PROIBIDAS = ("reau_registros",)


def gravacoes(entrada):
    """Os `.h264` de [entrada] (uma coleta com `video/`, ou a `video/`), em ordem de nome."""
    entrada = Path(entrada)
    pasta = entrada / "video" if (entrada / "video").is_dir() else entrada
    return sorted(pasta.glob("*.h264"))


def ritmo_medio(h264):
    """Quadros por segundo médios da gravação, do índice `.csv` ao lado (quadros ÷ segundos entre o primeiro e o último); `FPS_PADRAO` sem índice, ou se o índice não se lê ou tem um quadro só."""
    csv = Path(h264).with_suffix(".csv")
    if not csv.exists():
        return FPS_PADRAO
    try:
        quadros = indice_video.ler(csv).quadros
    except (ValueError, OSError):
        return FPS_PADRAO
    if len(quadros) < 2 or quadros[-1][1] <= quadros[0][1]:
        return FPS_PADRAO
    return (len(quadros) - 1) / ((quadros[-1][1] - quadros[0][1]) / 1000.0)


def saida_permitida(entrada, saida):
    """None se [saida] pode receber os arquivos; senão por que não: dentro de `reau_registros/` ou dentro da pasta lida (a coleta é só leitura)."""
    s, e = Path(saida).resolve(), Path(entrada).resolve()
    if any(p in PASTAS_PROIBIDAS for p in s.parts):
        return f"a saída {s} está dentro de reau_registros/, que é só leitura"
    if s == e or e in s.parents:
        return f"a saída {s} está dentro da pasta lida {e}"
    return None


def comando_original(ffmpeg, h264, destino, fps, max_s=None):
    """O ffmpeg que só empacota o .h264 em mp4, no ritmo médio, sem reencodar."""
    return [ffmpeg, "-y", "-loglevel", "error", "-f", "h264", "-r", f"{fps:.4f}", "-i", str(h264), *(["-t", f"{max_s:g}"] if max_s else []), "-c:v", "copy", "-an", str(destino)]


def comando_reencodado(ffmpeg, h264, destino, fps, kbps, max_s=None):
    """O ffmpeg que reencoda a [kbps]: x264, taxa limitada pelo alvo (buffer de 1 s), yuv420p, sem som."""
    return [ffmpeg, "-y", "-loglevel", "error", "-f", "h264", "-r", f"{fps:.4f}", "-i", str(h264), *(["-t", f"{max_s:g}"] if max_s else []),
            "-c:v", "libx264", "-preset", "slow", "-b:v", f"{kbps}k", "-maxrate", f"{kbps}k", "-bufsize", f"{kbps}k", "-pix_fmt", "yuv420p", "-an", str(destino)]


def planejar(h264s, saida, kbps, semente, aberto, ffmpeg, max_s=None):
    """[{numero, h264, fps, original, reencodado, comandos: [[…], […]]}] e o gabarito {arquivo: 'original'|'reencodado'} (vazio no modo aberto). O sorteio é por gravação, com a semente."""
    sorteio = random.Random(semente)
    saida = Path(saida)
    itens, gabarito = [], {}
    for n, h264 in enumerate(h264s, 1):
        fps = ritmo_medio(h264)
        if aberto:
            original, reencodado = saida / f"{h264.stem}.original.mp4", saida / f"{h264.stem}.{kbps}k.mp4"
        else:
            lados = [saida / f"{n:03d}_A.mp4", saida / f"{n:03d}_B.mp4"]
            sorteio.shuffle(lados)
            original, reencodado = lados
            gabarito[original.name], gabarito[reencodado.name] = "original", "reencodado"
        itens.append({"numero": n, "h264": h264, "fps": fps, "original": original, "reencodado": reencodado,
                      "comandos": [comando_original(ffmpeg, h264, original, fps, max_s), comando_reencodado(ffmpeg, h264, reencodado, fps, kbps, max_s)]})
    return itens, dict(sorted(gabarito.items()))


def _opcoes(argv):
    """(posicionais, opções) ou None se faltou o valor de uma opção ou ela é desconhecida."""
    posicionais, opcoes, args = [], {"kbps": KBPS_PADRAO, "semente": SEMENTE_PADRAO, "max_s": None, "aberto": False, "ffmpeg": "ffmpeg", "so_listar": False}, list(argv)
    while args:
        a = args.pop(0)
        if a in ("--kbps", "--semente", "--max-s", "--ffmpeg"):
            if not args:
                return None
            v = args.pop(0)
            chave = a[2:].replace("-", "_")
            try:
                opcoes[chave] = v if chave == "ffmpeg" else (float(v) if chave == "max_s" else int(v))
            except ValueError:
                return None
        elif a in ("--aberto", "--so-listar"):
            opcoes[a[2:].replace("-", "_")] = True
        elif a.startswith("--"):
            return None
        else:
            posicionais.append(a)
    return posicionais, opcoes


def main(argv):
    if any(a in ("-h", "--help") for a in argv):
        print(__doc__)
        return 0
    lido = _opcoes(argv)
    if lido is None or len(lido[0]) != 2:
        print(__doc__, file=sys.stderr)
        return 2
    (entrada, saida), o = lido
    if not Path(entrada).is_dir():
        print(f"{entrada}: não é uma pasta", file=sys.stderr)
        return 2
    erro = saida_permitida(entrada, saida)
    if erro:
        print(erro, file=sys.stderr)
        return 2
    h264s = gravacoes(entrada)
    if not h264s:
        print(f"{entrada}: nenhum .h264 (a coleta não tem video/)", file=sys.stderr)
        return 2
    ffmpeg = shutil.which(o["ffmpeg"]) or (o["ffmpeg"] if Path(o["ffmpeg"]).is_file() else None)
    if ffmpeg is None and not o["so_listar"]:
        print("o ffmpeg não está instalado (ou não está no PATH): nada foi feito. Instale-o, ou rode com --so-listar para ver os comandos.", file=sys.stderr)
        return 3
    itens, gabarito = planejar(h264s, saida, o["kbps"], o["semente"], o["aberto"], ffmpeg or o["ffmpeg"], o["max_s"])
    if o["so_listar"]:
        for item in itens:
            for c in item["comandos"]:
                print(" ".join(c))
        return 0
    Path(saida).mkdir(parents=True, exist_ok=True)
    falhas = 0
    for item in itens:
        for c in item["comandos"]:
            feito = subprocess.run(c, capture_output=True, text=True)
            if feito.returncode != 0:
                falhas += 1
                print(f"FALHOU ({item['h264'].name}): {feito.stderr.strip()[:200]}", file=sys.stderr)
    if gabarito:
        (Path(saida) / "gabarito.json").write_text(json.dumps(gabarito, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{len(itens)} gravações, {2 * len(itens) - falhas} filmes em {saida}" + ("; o gabarito está em gabarito.json (abra só depois de escolher)" if gabarito else ""))
    return 1 if falhas else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
