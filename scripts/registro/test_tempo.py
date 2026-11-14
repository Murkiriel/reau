"""Tests of tempo.py, what ler_registro.py, quedas.py, varredura.py and logcat_sistema.py repeated (item 41, 11/11/2026)."""
import sys
import unittest
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tempo  # noqa: E402


class RelogioDoRegistroTest(unittest.TestCase):
    def test_dif_goes_around_midnight(self):
        self.assertEqual(10.0, tempo.dif(110.0, 100.0))
        self.assertEqual(20.0, tempo.dif(10.0, 86390.0))  # 23:59:50 to 00:00:10
        self.assertEqual(0.0, tempo.dif(5.0, 5.0))

    def test_assinado_is_negative_for_a_line_a_little_before(self):
        self.assertEqual(-10.0, tempo.assinado(90.0, 100.0))
        self.assertEqual(10.0, tempo.assinado(110.0, 100.0))
        self.assertEqual(-20.0, tempo.assinado(86390.0, 10.0))  # 23:59:50 is 20 s before 00:00:10
        self.assertEqual(20.0, tempo.assinado(10.0, 86390.0))

    def test_assinado_at_exactly_half_a_day_stays_positive(self):
        self.assertEqual(43200.0, tempo.assinado(43200.0, 0.0))


class TextosTest(unittest.TestCase):
    def test_dec_uses_a_decimal_comma(self):
        self.assertEqual("3,4", tempo.dec(3.44))
        self.assertEqual("3,44", tempo.dec(3.4449, 2))
        self.assertEqual("223,4 s", tempo.segundos_texto(223.4))

    def test_hora_ms_wraps_the_day_and_keeps_one_decimal(self):
        self.assertEqual("07:44:46,8", tempo.hora_ms(7 * 3600 + 44 * 60 + 46.8))
        self.assertEqual("00:00:05,0", tempo.hora_ms(86400 + 5))

    def test_duracao_texto(self):
        self.assertEqual("45 s", tempo.duracao_texto(45))
        self.assertEqual("3 min 05 s", tempo.duracao_texto(185))
        self.assertEqual("1 min 00 s", tempo.duracao_texto(60))

    def test_the_resume_form_goes_and_comes_back(self):
        self.assertEqual("45s", tempo.duracao_resumo(45))
        self.assertEqual("1m08s", tempo.duracao_resumo(68))
        self.assertEqual("1m00s", tempo.duracao_resumo(59.6))
        for s in (0, 45, 60, 68, 3600):
            self.assertEqual(s, tempo.duracao_s(tempo.duracao_resumo(s)))
        self.assertEqual(0, tempo.duracao_s("sem duração"))
        self.assertEqual(0, tempo.duracao_s("1h2m"))

    def test_hora8_drops_the_milliseconds(self):
        self.assertEqual("07:44:46", tempo.hora8(SimpleNamespace(hora="07:44:46.812")))


class ProcessoTest(unittest.TestCase):
    def test_the_build_of_a_process_start_line(self):
        t = "==== process start: app — app 0.3.0-dev+26.11.10-08.09 (29903203, 7eee9cb), Google Pixel, Android 16 (API 36) ===="
        self.assertEqual("0.3.0-dev+26.11.10-08.09 (29903203, 7eee9cb)", tempo.build_do_processo(t))

    def test_a_start_line_without_the_version_says_so_and_another_line_is_none(self):
        self.assertEqual("sem versão", tempo.build_do_processo("==== process start: app ===="))
        self.assertEqual("0.2.0", tempo.build_do_processo("==== process start: app — app 0.2.0, Google Pixel ===="))
        self.assertIsNone(tempo.build_do_processo("projection started"))


class InstanteTest(unittest.TestCase):
    def test_the_instant_joins_the_day_and_the_time(self):
        x = SimpleNamespace(dia="2026-11-08", hora="12:58:12.915")
        self.assertEqual(datetime(2026, 11, 8, 12, 58, 12, 915000), tempo.instante(x))

    def test_a_line_without_a_day_has_no_instant(self):
        self.assertIsNone(tempo.instante(SimpleNamespace(dia="", hora="12:58:12.915")))


if __name__ == "__main__":
    unittest.main()
