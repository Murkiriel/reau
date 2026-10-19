"""Gera a escrita "Lite" do ícone do REau Lite: duas imagens de 432x432 (a tela de 108 dp a 4 px por dp), transparentes,
com a palavra por cima da arte no canto inferior direito.

- Colorida (ic_launcher_lite_label.png): letras brancas com borda no cinza da janela inicial do app (BORDA).
- Monocromática (ic_launcher_lite_label_mono.png, ícone temático do Android): só o canal alfa importa; a borda é opaca
  e as letras vazadas, para a palavra se ler sobre a arte de uma cor só.

Uso, a partir da raiz do repositório:  python -I -B scripts/icon/gerar_etiqueta_lite.py
Escreve em app/src/lite/res/drawable-nodpi/ (ou na pasta dada como primeiro argumento).
"""
import os
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFont

# A raiz do repositório vem deste arquivo (scripts/icon/gerar_etiqueta_lite.py), não de um caminho da máquina.
RAIZ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FONTE = os.path.join(RAIZ, "app", "src", "main", "res", "font", "hanken_grotesk_600.ttf")
SAIDA = os.path.join(RAIZ, "app", "src", "lite", "res", "drawable-nodpi")

# Cor da borda da palavra no PNG colorido: o fundo da janela do app ao abrir. O tema do app (Theme.REau em
# app/src/main/res/values/themes.xml) herda de android:Theme.Material.NoActionBar, cuja windowBackground é
# colorBackground = background_material_dark = material_grey_850 = #303030 (platforms/android-36/data/res/values/
# colors_material.xml, linha 83). É o cinza que a janela inicial/splash mostra antes de a tela em Compose pintar.
# (A tela principal escura em Compose usa Grafite #121212, ui/theme/Color.kt:15, que é o fundo do próprio ícone.)
BORDA = (0x30, 0x30, 0x30, 255)

PX = 4                      # px por dp
SS = 4                      # supersampling para a borda suave
TAMANHO_DP = 15.0           # tamanho da fonte
BORDA_DP = 1.5              # grossura da borda
# Onde a palavra termina (dp na tela de 108): dentro do círculo que a máscara do launcher deixa ver (raio ~36 de 54,54).
DIREITA_DP, BASE_INFERIOR_DP = 74.0, 82.5


def renderizar(mono):
    """Desenha a palavra "Lite" na tela de 108 dp; `mono` troca a cor por contorno opaco com letras vazadas."""
    escala = PX * SS
    fonte = ImageFont.truetype(FONTE, round(TAMANHO_DP * escala))
    borda = round(BORDA_DP * escala)
    img = Image.new("RGBA", (108 * escala, 108 * escala), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    _, _, direita, base = d.textbbox((0, 0), "Lite", font=fonte)
    x = DIREITA_DP * escala - direita
    y = BASE_INFERIOR_DP * escala - base
    if mono:
        # Borda opaca, letras vazadas.
        contorno = Image.new("L", img.size, 0)
        ImageDraw.Draw(contorno).text((x, y), "Lite", font=fonte, fill=255, stroke_width=borda, stroke_fill=255)
        letras = Image.new("L", img.size, 0)
        ImageDraw.Draw(letras).text((x, y), "Lite", font=fonte, fill=255)
        img = Image.new("RGBA", img.size, (0, 0, 0, 0))
        img.putalpha(ImageChops.subtract(contorno, letras))
    else:
        d.text((x, y), "Lite", font=fonte, fill=(255, 255, 255, 255), stroke_width=borda, stroke_fill=BORDA)
    return img.resize((108 * PX, 108 * PX), Image.LANCZOS)


def gerar(saida=SAIDA):
    """Grava os dois PNGs em `saida` e devolve os caminhos."""
    os.makedirs(saida, exist_ok=True)
    colorido = os.path.join(saida, "ic_launcher_lite_label.png")
    mono = os.path.join(saida, "ic_launcher_lite_label_mono.png")
    renderizar(False).save(colorido, optimize=True)
    renderizar(True).save(mono, optimize=True)
    return colorido, mono


if __name__ == "__main__":
    for caminho in gerar(sys.argv[1] if len(sys.argv) > 1 else SAIDA):
        print("ok", os.path.relpath(caminho, RAIZ) if caminho.startswith(RAIZ) else caminho)
