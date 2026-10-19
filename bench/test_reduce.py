"""Testes do reduce.py, com imagens sintéticas.

    python -B bench/test_reduce.py

Os números esperados são os do app (video/FrameFit.kt, com o vidro medido na moto: círculo de raio 263 com centro
em (263, 263), o vídeo nas 300 linhas de cima): inteira 370x222 em (78, 78), preencher 500x300 em (13, 0), para uma
captura de 800x480. (Antes valia um vidro mais antigo, um círculo de raio 150 cortado em y = 186.)
"""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import reduce  # noqa: E402

VERMELHO, VERDE, AZUL, AMARELO, PRETO = (255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 0), (0, 0, 0)


def quadrantes(w, h):
    """Quatro quadrantes de cor: vermelho, verde em cima; azul, amarelo embaixo."""
    img = Image.new("RGB", (w, h))
    for i, cor in enumerate([VERMELHO, VERDE, AZUL, AMARELO]):
        x0, y0 = (i % 2) * w // 2, (i // 2) * h // 2
        img.paste(cor, (x0, y0, x0 + w // 2, y0 + h // 2))
    return img


def perto(a, b, tolerancia=40):
    return all(abs(x - y) <= tolerancia for x, y in zip(a, b))


class Retangulos(unittest.TestCase):
    def test_800x480_da_os_numeros_do_app(self):
        r = reduce.retangulos(800, 480)
        self.assertEqual(((0, 0, 800, 480), (78, 78, 370, 222)), r["inteira"])
        self.assertEqual(((0, 0, 800, 480), (13, 0, 500, 300)), r["preencher"])

    def test_os_miolos_do_bench(self):
        r = reduce.retangulos(800, 480)
        self.assertEqual(((160, 90, 480, 300), (23, 0, 480, 300)), r["miolo_480x300"])
        self.assertEqual(((210, 122, 380, 236), (22, 0, 483, 300)), r["miolo_380x236"])

    def test_outra_resolucao_guarda_as_proporcoes(self):
        r = reduce.retangulos(1280, 720)
        # 16:9, mais larga que 800x480 (5:3). Conta à mão: largura 384 cabe no círculo (192² + 179² = 68905 <= 69169),
        # 386 não (193² + 180,125² = 69694); altura 384 / (16/9) = 216.
        self.assertEqual((71, 84, 384, 216), r["inteira"][1])
        self.assertEqual((256, 135, 768, 450), r["miolo_480x300"][0], "o mesmo miolo, em proporção")

    def test_todo_destino_cabe_no_quadro_do_painel(self):
        for w, h in [(800, 480), (1280, 720), (1920, 1080), (1000, 500)]:
            for nome, (_, (x, y, dw, dh)) in reduce.retangulos(w, h).items():
                self.assertTrue(x >= 0 and y >= 0 and x + dw <= 526 and y + dh <= 300, f"{w}x{h} {nome}")
                self.assertEqual(300, y + dh, f"{w}x{h} {nome}: encostado na base do quadro (a faixa da moto fica abaixo dele)")


class Cartela(unittest.TestCase):
    def test_a_captura_vai_ao_retangulo_e_o_resto_e_preto(self):
        img = reduce.cartela(quadrantes(800, 480), "preencher")
        self.assertEqual((526, 300), img.size)
        x, y, w, h = 13, 0, 500, 300
        self.assertTrue(perto(VERMELHO, img.getpixel((x + w // 4, y + h // 4))))
        self.assertTrue(perto(VERDE, img.getpixel((x + 3 * w // 4, y + h // 4))))
        self.assertTrue(perto(AZUL, img.getpixel((x + w // 4, y + 3 * h // 4))))
        self.assertTrue(perto(AMARELO, img.getpixel((x + 3 * w // 4, y + 3 * h // 4))))
        self.assertEqual(PRETO, img.getpixel((5, 150)), "à esquerda da imagem")
        self.assertEqual(PRETO, img.getpixel((520, 150)), "à direita da imagem")

    def test_o_miolo_mostra_so_o_centro(self):
        img = reduce.cartela(quadrantes(800, 480), "miolo_380x236")
        # O centro da captura é o encontro dos quatro quadrantes: no miolo, cada um ocupa um quarto do destino.
        x, y, w, h = 22, 0, 483, 300
        self.assertTrue(perto(VERMELHO, img.getpixel((x + w // 4, y + h // 4))))
        self.assertTrue(perto(AMARELO, img.getpixel((x + 3 * w // 4, y + 3 * h // 4))))

    def test_o_miolo_deixa_de_fora_o_que_esta_em_volta(self):
        """Escrito depois do código, para a mutação: o que fica fora do miolo não pode aparecer na cartela."""
        tela = Image.new("RGB", (800, 480), VERMELHO)
        tela.paste(VERDE, (160, 90, 640, 390))  # exatamente o miolo de 480x300
        img = reduce.cartela(tela, "miolo_480x300")
        x, y, w, h = 23, 0, 480, 300
        vermelhos = sum(1 for px in range(x + 2, x + w - 2) for py in range(y + 2, y + h - 2) if img.getpixel((px, py))[0] > 128)
        self.assertEqual(0, vermelhos, "pixels vermelhos dentro do miolo")

    def test_com_contorno_o_circulo_do_vidro_aparece(self):
        img = reduce.cartela(quadrantes(800, 480), "inteira", contorno=True)
        # o círculo de raio 263 em (263, 263) passa, na linha 150, em x = 263 - raiz(263² - 113²) = 25,5
        self.assertTrue(any(img.getpixel((x, 150)) != PRETO for x in (24, 25, 26)), "a borda esquerda do vidro")
        self.assertEqual(PRETO, img.getpixel((30, 186)), "só o círculo: nenhuma linha do computador de bordo (a faixa da moto fica abaixo do quadro)")
        sem = reduce.cartela(quadrantes(800, 480), "inteira")
        self.assertTrue(all(sem.getpixel((x, 150)) == PRETO for x in (24, 25, 26)))

    def test_nome_desconhecido_e_recusado(self):
        with self.assertRaises(ValueError):
            reduce.cartela(quadrantes(800, 480), "nada")


class LinhaDeComando(unittest.TestCase):
    def test_gera_as_quatro_cartelas(self):
        with tempfile.TemporaryDirectory() as pasta:
            captura = Path(pasta) / "waze.png"
            quadrantes(800, 480).save(captura)
            saida = Path(pasta) / "saida"
            r = subprocess.run([sys.executable, "-B", str(Path(__file__).with_name("reduce.py")), str(captura), "--saida", str(saida)],
                               capture_output=True, text=True)
            self.assertEqual(0, r.returncode, r.stderr)
            feitos = sorted(p.name for p in saida.glob("*.png"))
            self.assertEqual(["waze_inteira.png", "waze_miolo_380x236.png", "waze_miolo_480x300.png", "waze_preencher.png"], feitos)
            for nome in feitos:
                with Image.open(saida / nome) as img:
                    self.assertEqual((526, 300), img.size, nome)

    def test_sem_arquivo_diz_como_usar(self):
        r = subprocess.run([sys.executable, "-B", str(Path(__file__).with_name("reduce.py"))], capture_output=True, text=True)
        self.assertNotEqual(0, r.returncode)
        self.assertIn("uso", (r.stdout + r.stderr).lower())


if __name__ == "__main__":
    unittest.main()
