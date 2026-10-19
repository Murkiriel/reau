"""Reduz uma captura de tela do Android Auto (do emulador de central, DHU) às cartelas de 526x300 do painel.

    python bench\\reduce.py captura.png [--saida pasta] [--contorno]

Para cada captura, quatro cartelas (fundo preto, como o painel recebe), com a mesma geometria do app
(video/FrameFit.kt):

    <nome>_inteira.png         a tela inteira, com os quatro cantos no vidro (800x480 -> 370x222 em (78, 78))
    <nome>_preencher.png       a tela inteira, o maior possível no quadro (800x480 -> 500x300 em (13, 0))
    <nome>_miolo_480x300.png   o miolo de 480x300 (de 800x480; em proporção para outras resoluções), no quadro
    <nome>_miolo_380x236.png   o miolo de 380x236, mais ampliado, no quadro

O vidro é o medido na moto (video/DashGlass.kt): um círculo de raio 263 com centro em
(263, 263), do qual o vídeo ocupa as 300 linhas de cima; a faixa da moto (computador de bordo) fica abaixo do quadro.
Tudo encosta na base do quadro (y = 300). --contorno desenha em cinza o círculo do vidro, para ver no PC onde a imagem
cai (só os dois cantos de cima ficam fora); para mandar ao painel de verdade, sem contorno.
"""
import argparse
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw

QUADRO_W, QUADRO_H = 526, 300
CENTRO_X, CENTRO_Y, RAIO = 263, 263, 263
BASE_UTIL = QUADRO_H  # a faixa da moto fica abaixo do quadro
CAIXA_W, CAIXA_H = QUADRO_W, BASE_UTIL  # DashGlass.DOME_BOX: o quadro inteiro
CINZA = (128, 128, 128)
NOMES = ("inteira", "preencher", "miolo_480x300", "miolo_380x236")

# Os miolos de teste, tirados de uma tela de 800x480.
MIOLOS = {"miolo_480x300": (480, 300), "miolo_380x236": (380, 236)}
REF_W, REF_H = 800, 480


def _arredonda(x):
    """Como o roundToInt do Kotlin: metade para cima."""
    return math.floor(x + 0.5)


def _inteira(w, h):
    """FrameFit.wholeVisible: o maior retângulo da forma da tela com os quatro cantos dentro do círculo."""
    aspecto = w / h
    r2 = RAIO * RAIO
    abaixo = BASE_UTIL - CENTRO_Y
    largura = CAIXA_W
    while largura > 2:
        meia = largura / 2
        acima = largura / aspecto - abaixo
        cabe = largura / aspecto <= BASE_UTIL
        if cabe and meia * meia + abaixo * abaixo <= r2 and meia * meia + acima * acima <= r2:
            break
        largura -= 2
    altura = max(1, _arredonda(largura / aspecto))
    return CENTRO_X - largura // 2, BASE_UTIL - altura, largura, altura


def _na_caixa(w, h):
    """FrameFit.fillDome: o maior possível dentro do quadro, encostado na base."""
    escala = min(CAIXA_W / w, CAIXA_H / h)
    dw = min(max(_arredonda(w * escala), 1), CAIXA_W)
    dh = min(max(_arredonda(h * escala), 1), CAIXA_H)
    return CENTRO_X - dw // 2, BASE_UTIL - dh, dw, dh


def retangulos(w, h):
    """Para uma captura de w x h: nome -> ((x, y, largura, altura) na captura, (x, y, largura, altura) no painel)."""
    inteira = (0, 0, w, h)
    saida = {"inteira": (inteira, _inteira(w, h)), "preencher": (inteira, _na_caixa(w, h))}
    for nome, (mw, mh) in MIOLOS.items():
        cw, ch = _arredonda(w * mw / REF_W), _arredonda(h * mh / REF_H)
        origem = ((w - cw) // 2, (h - ch) // 2, cw, ch)
        saida[nome] = (origem, _na_caixa(cw, ch))
    return saida


def cartela(img, nome, contorno=False):
    """A cartela [nome] da captura [img]: 526x300, fundo preto."""
    if nome not in NOMES:
        raise ValueError(f"cartela desconhecida: {nome} (há {', '.join(NOMES)})")
    (sx, sy, sw, sh), (dx, dy, dw, dh) = retangulos(*img.size)[nome]
    pedaco = img.convert("RGB").crop((sx, sy, sx + sw, sy + sh)).resize((dw, dh), Image.LANCZOS)
    saida = Image.new("RGB", (QUADRO_W, QUADRO_H))
    saida.paste(pedaco, (dx, dy))
    if contorno:
        d = ImageDraw.Draw(saida)
        d.ellipse((CENTRO_X - RAIO, CENTRO_Y - RAIO, CENTRO_X + RAIO - 1, CENTRO_Y + RAIO - 1), outline=CINZA)
    return saida


def main(argv):
    p = argparse.ArgumentParser(description="Reduz capturas do Android Auto às cartelas de 526x300 da bancada.",
                                usage="uso: reduce.py captura.png [captura2.png ...] [--saida pasta] [--contorno]")
    p.add_argument("capturas", nargs="+", type=Path)
    p.add_argument("--saida", type=Path, default=None, help="pasta das cartelas (padrão: a da captura)")
    p.add_argument("--contorno", action="store_true", help="desenha o círculo do vidro")
    args = p.parse_args(argv)
    for captura in args.capturas:
        pasta = args.saida or captura.parent
        pasta.mkdir(parents=True, exist_ok=True)
        with Image.open(captura) as img:
            for nome in NOMES:
                destino = pasta / f"{captura.stem}_{nome}.png"
                cartela(img, nome, args.contorno).save(destino)
                print(destino)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
