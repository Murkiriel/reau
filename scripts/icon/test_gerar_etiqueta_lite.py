"""Testes de scripts/icon/gerar_etiqueta_lite.py: tamanho dos PNGs, cor da borda e o mono só com alfa.

Rodar da raiz do repositório:  python -m unittest discover -s scripts -p "test_*.py" -v
"""
import os
import sys
import tempfile
import unittest

from PIL import Image

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, AQUI)

import gerar_etiqueta_lite as gerador  # noqa: E402


def pixels(img):
    """Os pixels RGBA como tuplas (sem `getdata`, que o Pillow está aposentando)."""
    dados = img.tobytes()
    return [tuple(dados[i:i + 4]) for i in range(0, len(dados), 4)]


class GerarEtiquetaLiteTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.pasta = tempfile.TemporaryDirectory()
        cls.colorido, cls.mono = gerador.gerar(cls.pasta.name)

    @classmethod
    def tearDownClass(cls):
        cls.pasta.cleanup()

    def test_os_dois_pngs_tem_432_por_432_com_alfa(self):
        for caminho in (self.colorido, self.mono):
            with Image.open(caminho) as img:
                self.assertEqual((432, 432), img.size)
                self.assertEqual("RGBA", img.mode)

    def test_no_colorido_o_opaco_vai_da_cor_da_borda_ao_branco(self):
        # As bordas suaves misturam a borda com o branco: todo pixel opaco é um cinza entre as duas cores (com uma
        # folga pequena para o repique do filtro Lanczos ao reduzir a imagem).
        with Image.open(self.colorido) as img:
            opacos = {(r, g, b) for r, g, b, a in pixels(img) if a == 255}
        self.assertIn(gerador.BORDA[:3], opacos)
        self.assertIn((255, 255, 255), opacos)
        piso = gerador.BORDA[0] - 24
        for r, g, b in opacos:
            self.assertTrue(r == g == b and r >= piso, (r, g, b))

    def test_o_colorido_nao_tem_mais_borda_preta(self):
        with Image.open(self.colorido) as img:
            self.assertNotIn((0, 0, 0, 255), set(pixels(img)))

    def test_o_mono_tem_contorno_opaco_e_letras_vazadas(self):
        with Image.open(self.mono) as img:
            alfas = [a for _, _, _, a in pixels(img)]
        self.assertIn(255, alfas)
        self.assertIn(0, alfas)

    def test_a_palavra_fica_no_canto_inferior_direito(self):
        with Image.open(self.colorido) as img:
            esquerda, topo, direita, base = img.getchannel("A").getbbox()
        self.assertGreater(esquerda, 432 * 4 // 10)
        self.assertGreater(topo, 432 * 6 // 10)
        self.assertLessEqual(direita, 432 * 74 // 108 + 8)


if __name__ == "__main__":
    unittest.main()
