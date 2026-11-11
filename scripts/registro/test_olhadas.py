"""Tests of olhadas.py: the readings of the picture classifier with p < 0.98 and the alarm of the rate of "not sure" (item 5, 11/11/2026)."""
import sys
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import olhadas  # noqa: E402
from test_ler_registro import linha, projecao  # noqa: E402
import ler_registro  # noqa: E402
import tempfile  # noqa: E402
from test_ler_registro import escreve  # noqa: E402


def look(hora, classe, p, motivo="p under 0.98"):
    return linha(hora, "compositor", f"picture look: {classe} p={p:.3f} ({motivo})")


def janela(hora, looks):
    return linha(hora, "stream", f"video fps target=4 sent=2.0 (10frames/5.1s) kbps=107 late=0 full=false look={looks}/3.44/7.61ms latch=153/0.31/1.42ms")


def bloco_de(*meio):
    d = tempfile.mkdtemp()
    escreve(d, "ride-20261108-100000.log", projecao(*meio))
    return ler_registro.projecoes(ler_registro.ler([d]))[0]


class LeiturasTest(unittest.TestCase):
    def test_the_lines_come_with_class_p_reason_and_skipped_count(self):
        b = bloco_de(look("10:01:00.000", "MAP", 0.942, "p under 0.98; 3 looks not written"))
        r = olhadas.leituras(b)
        self.assertEqual(1, len(r))
        self.assertEqual(("MAP", 0.942, 3), (r[0]["classe"], r[0]["p"], r[0]["puladas"]))

    def test_one_look_not_written_in_the_singular_also_counts(self):
        b = bloco_de(look("10:01:00.000", "MAP", 0.95, "p under 0.98; 1 look not written"))
        self.assertEqual(1, olhadas.leituras(b)[0]["puladas"])

    def test_the_black_picture_p_zero_is_not_a_low_reading(self):
        b = bloco_de(look("10:01:00.000", "UNSURE", 0.0, "was nothing"), look("10:01:01.000", "MAP", 0.999, "was UNSURE"),
                     look("10:01:02.000", "MAP", 0.979), look("10:01:03.000", "MAP", 0.980))
        self.assertEqual([0.979], [r["p"] for r in olhadas.baixas(olhadas.leituras(b))])  # 0.98 is not under 0.98
        t = olhadas.taxa(b)
        self.assertEqual((1, 1, 1), (t["baixas"], t["pretas"], t["incertas"]))

    def test_the_screen_of_aa_screen_before_each_look_and_the_divergence_are_told(self):
        b = bloco_de(linha("10:00:50.000", "aa", "aa-screen: nav (by the picture)"), look("10:01:00.000", "MAP", 0.95),
                     linha("10:01:10.000", "aa", "aa-screen: menu (by the picture)"), look("10:01:20.000", "MAP", 0.90),
                     look("10:01:30.000", "APPS", 0.96))
        t = olhadas.tabela(b)
        self.assertEqual(3, len(t))
        self.assertIn("tela do AA: mapa", t[0])
        self.assertNotIn("DIVERGE", t[0])
        self.assertIn("tela do AA: menu (DIVERGE do aa-screen)", t[1])
        self.assertIn("DIVERGE", t[2])  # APPS against a menu

    def test_a_look_before_any_aa_screen_line_has_no_screen_and_does_not_diverge(self):
        b = bloco_de(look("10:01:00.000", "MAP", 0.95))
        self.assertIn("tela do AA: sem aa-screen", olhadas.tabela(b)[0])
        self.assertNotIn("DIVERGE", olhadas.tabela(b)[0])

    def test_the_table_stops_at_the_limit_and_says_how_many_are_left(self):
        b = bloco_de(*[look(f"10:{m:02d}:{s:02d}.000", "MAP", 0.95) for m in range(1, 4) for s in range(0, 30)])
        t = olhadas.tabela(b)
        self.assertEqual(olhadas.LISTA_MAX + 1, len(t))
        self.assertIn(f"mais {90 - olhadas.LISTA_MAX} leituras", t[-1])


class TaxaTest(unittest.TestCase):
    def dezenas(self, n, p=0.95, extra=""):
        return [look(f"10:01:{k % 60:02d}.{k:03d}", "MAP", p, "p under 0.98" + extra) for k in range(n)]

    def test_the_rate_counts_the_written_and_the_skipped_over_the_looks_made(self):
        b = bloco_de(*self.dezenas(10, extra="; 5 looks not written"), janela("10:02:00.000", 400), janela("10:02:05.000", 600))
        t = olhadas.taxa(b)
        self.assertEqual((10, 50, 60, 1000), (t["escritas"], t["puladas"], t["baixas"], t["feitas"]))
        self.assertAlmostEqual(6.0, t["pct"])
        self.assertTrue(t["alarme"])

    def test_at_exactly_the_limit_there_is_no_alarm_and_above_it_there_is(self):
        # 30 not sure of 1000 looks = 3.0 %: not above; 31 of 1000 = 3.1 %: above
        b = bloco_de(*self.dezenas(10, extra="; 2 looks not written"), janela("10:02:00.000", 1000))
        t = olhadas.taxa(b)
        self.assertEqual(30, t["baixas"])
        self.assertAlmostEqual(3.0, t["pct"])
        self.assertFalse(t["alarme"])
        b = bloco_de(*self.dezenas(11, extra=""), janela("10:02:00.000", 1000), *self.dezenas(0))
        b2 = bloco_de(*self.dezenas(10, extra="; 2 looks not written"), look("10:03:00.000", "MAP", 0.9), janela("10:02:00.000", 1000))
        self.assertEqual(31, olhadas.taxa(b2)["baixas"])
        self.assertTrue(olhadas.taxa(b2)["alarme"])
        self.assertFalse(olhadas.taxa(b)["alarme"])  # 11 of 1000 = 1.1 %

    def test_the_good_run_of_08_11_is_not_an_alarm(self):
        # 156 not sure of 11239 looks (1.4 %): the J4 on a map with a call card over it
        b = bloco_de(*self.dezenas(55, extra="; 2 looks not written")[:55], janela("10:02:00.000", 11239))
        t = olhadas.taxa(b)
        self.assertLess(t["pct"], olhadas.LIMITE_PCT)
        self.assertFalse(t["alarme"])

    def test_fewer_than_ten_not_sure_never_alarm_even_in_a_short_run(self):
        b = bloco_de(*self.dezenas(9), janela("10:02:00.000", 100))  # 9 of 100 = 9 %
        self.assertFalse(olhadas.taxa(b)["alarme"])
        b = bloco_de(*self.dezenas(10), janela("10:02:00.000", 100))
        self.assertTrue(olhadas.taxa(b)["alarme"])

    def test_without_video_windows_the_rate_is_per_minute_of_projection(self):
        # the projection helper runs 10:00:01 to 10:09:00 = ~9 min: 63 not sure is 7.0 per minute, 70 is above
        b = bloco_de(*self.dezenas(30, extra="; 1 look not written"))  # 60 -> 6.7 per minute
        t = olhadas.taxa(b)
        self.assertIsNone(t["pct"])
        self.assertAlmostEqual(60 / ((8 * 60 + 59) / 60), t["por_minuto"], places=3)
        self.assertFalse(t["alarme"])
        b = bloco_de(*self.dezenas(35, extra="; 1 look not written"))  # 70 -> 7.8 per minute
        self.assertTrue(olhadas.taxa(b)["alarme"])

    def test_the_text_names_the_unit_of_the_rate(self):
        b = bloco_de(*self.dezenas(10, extra="; 5 looks not written"), janela("10:02:00.000", 1000))
        self.assertIn("60 leituras sem certeza (p < 0,98; 10 escritas e 50 que o app disse não ter escrito) em 1000 leituras: 6,0 % (limite 3 %), a pior p=0,950",
                      olhadas.texto_da_taxa(olhadas.taxa(b)))
        b = bloco_de(*self.dezenas(35, extra="; 1 look not written"))
        self.assertIn("por minuto (limite 7)", olhadas.texto_da_taxa(olhadas.taxa(b)))


class SecaoEProblemasTest(unittest.TestCase):
    def test_a_run_without_low_readings_is_silent(self):
        b = bloco_de(look("10:01:00.000", "UNSURE", 0.0, "was nothing"), look("10:01:01.000", "MAP", 0.999, "was UNSURE"))
        self.assertEqual([], olhadas.secao([b]))
        self.assertEqual([], olhadas.problemas([b]))

    def test_the_alarm_goes_to_the_problems_with_the_run_named_and_the_section_lists_the_table(self):
        b = bloco_de(*[look(f"10:01:{k:02d}.000", "MAP", 0.9) for k in range(12)], janela("10:02:00.000", 100))
        p = olhadas.problemas([b])
        self.assertEqual(1, len(p))
        self.assertIn("ficou sem certeza demais", p[0])
        self.assertIn("ride-20261108-100000.log", p[0])
        s = olhadas.secao([b])
        self.assertEqual(1 + 12, len(s))

    def test_the_reader_puts_the_alarm_in_the_problems_and_the_table_in_its_own_section(self):
        d = tempfile.mkdtemp()
        escreve(d, "ride-20261108-100000.log", projecao(*[look(f"10:01:{k:02d}.000", "MAP", 0.9) for k in range(12)], janela("10:02:00.000", 100)))
        achados = ler_registro.problemas(ler_registro.ler([d]))
        self.assertTrue([a for a in achados if "sem certeza demais" in a], achados)
        import subprocess
        feito = subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), d, "--tudo"], capture_output=True, text=True, encoding="utf-8")
        self.assertIn("LEITURAS DO CLASSIFICADOR DE IMAGEM", feito.stdout)
        self.assertEqual(1, feito.returncode)


if __name__ == "__main__":
    unittest.main()
