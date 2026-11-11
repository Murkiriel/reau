"""Tests of chaves.py: the runs grouped by the experimental keys that were on, and the comparison with the criteria written in the module (item 15, 11/11/2026).
Synthetic logs only."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import chaves  # noqa: E402
import ler_registro  # noqa: E402
from test_ler_registro import escreve, linha  # noqa: E402

TODAS = [c[0] for c in chaves.CHAVES]


def settings(hora, *ligadas, com_campos=True):
    campos = " ".join(f"{c}={'true' if c in ligadas else 'false'}" for c in TODAS) if com_campos else ""
    return linha(hora, "settings", f"settings in use: density=180 fpsCeiling=4 {campos}".rstrip())


def projecao_de(hora_ligar, hora_desligar, *meio):
    return (f"{hora_ligar}  +     0ms  ---- projeção ligada: projection ----\n" + "".join(meio) +
            f"{hora_desligar}  +     0ms  ---- projeção desligada: projection ----\n")


def resumo(hora, streaming="1h00m", dark=None):
    texto = f"resumo duration={streaming} streaming={streaming} drops dash=0 aa=0 watchdogs=0 frames=1000 fps avg=4.0 min=2.0 gap max=0.3s"
    return linha(hora, "projection", texto + (f" dark={dark}" if dark else "") + " thermal max=none battery 80→79% (-1) marks=0")


def janela(hora, kbps=300, late=0, cheia="true"):
    return linha(hora, "stream", f"video fps target=4 sent=2.0 (10frames/5.1s) kbps={kbps} late={late} full={cheia} look=20/3.0/5.0ms")


def queda(hora, atras="0.3"):
    return linha(hora, "wifi", f"W: link lost — reconnecting in 4000ms (after 70 s on 2452 MHz; rssi -83, last dash packet {atras} s before)")


def device(hora, dbm):
    return linha(hora, "device", f"device bike={dbm}dBm/2452MHz/58Mbps data=wifi")


def lido(texto, nome="ride-20261108-100000.log"):
    d = tempfile.mkdtemp()
    escreve(d, nome, texto)
    return ler_registro.ler([d])


class LinhasDeChavesTest(unittest.TestCase):
    def test_the_settings_line_gives_the_keys_that_are_true(self):
        ligadas, conhecido = chaves.chaves_da_linha(settings("10:00:00.000", "experimentRtpPacing", "experimentFocusGap").split("] ", 1)[1].strip())
        self.assertEqual({"experimentRtpPacing", "experimentFocusGap"}, ligadas)
        self.assertTrue(conhecido)

    def test_a_settings_line_without_the_fields_is_a_build_that_did_not_say(self):
        ligadas, conhecido = chaves.chaves_da_linha("settings in use: density=180 fpsCeiling=4")
        self.assertEqual((set(), False), (ligadas, conhecido))

    def test_the_on_line_names_the_keys_by_their_words(self):
        t = ("connection experiments ON: video pacing (RTP packets spaced out); lower adaptive bitrate (200 kbps target); "
             "day and night remembered between looks (dawn at sunrise, not before it)")
        self.assertEqual(({"experimentRtpPacing", "experimentAdaptiveBitrate", "experimentNightMemory"}, True), chaves.chaves_da_linha(t))

    def test_every_key_has_a_phrase_that_is_found_and_other_lines_are_none(self):
        for campo, _, trecho in chaves.CHAVES:
            self.assertEqual(({campo}, True), chaves.chaves_da_linha(f"connection experiments ON: {trecho}"))
        self.assertIsNone(chaves.chaves_da_linha("setting experimentRtpPacing: false -> true"))

    def test_the_labels(self):
        self.assertEqual("sem registro das chaves", chaves.rotulo(frozenset(), False))
        self.assertEqual("nenhuma", chaves.rotulo(frozenset(), True))
        self.assertEqual("bitrate menor + pausa do foco", chaves.rotulo(frozenset({"experimentFocusGap", "experimentAdaptiveBitrate"}), True))


class PorProjecaoTest(unittest.TestCase):
    def estados(self, texto):
        linhas = lido(texto)
        return chaves.por_projecao(linhas, ler_registro.projecoes(linhas))

    def test_the_settings_line_after_the_marker_is_the_projections(self):
        t = (projecao_de("10:00:00.000", "10:10:00.000", settings("10:00:00.004", "experimentRtpPacing")) +
             projecao_de("11:00:00.000", "11:10:00.000", settings("11:00:00.004")))
        self.assertEqual([(frozenset({"experimentRtpPacing"}), True), (frozenset(), True)], self.estados(t))

    def test_the_line_of_the_process_start_before_the_marker_is_used_when_none_comes_after(self):
        t = settings("09:59:50.000", "experimentFocusGap") + projecao_de("10:00:00.000", "10:10:00.000", linha("10:00:01.000", "wifi", "x"))
        self.assertEqual([(frozenset({"experimentFocusGap"}), True)], self.estados(t))

    def test_a_line_that_comes_late_in_the_projection_does_not_change_its_state(self):
        t = (settings("09:59:50.000") + projecao_de("10:00:00.000", "10:10:00.000", settings("10:05:00.000", "experimentRtpPacing")))
        self.assertEqual([(frozenset(), True)], self.estados(t))

    def test_the_on_line_adds_to_the_settings_line_and_marks_the_state_as_known(self):
        t = projecao_de("10:00:00.000", "10:10:00.000", settings("10:00:00.004", com_campos=False),
                        linha("10:00:00.005", "settings", "connection experiments ON: start guard (experiments go off after a death right after a start)"))
        self.assertEqual([(frozenset({"experimentStartGuard"}), True)], self.estados(t))

    def test_a_log_with_no_key_line_at_all_is_unknown(self):
        t = projecao_de("10:00:00.000", "10:10:00.000", linha("10:00:01.000", "wifi", "x"))
        self.assertEqual([(frozenset(), False)], self.estados(t))

    def test_a_new_settings_line_forgets_the_on_line_before_it(self):
        t = (projecao_de("10:00:00.000", "10:10:00.000", settings("10:00:00.004", "experimentRtpPacing"),
                         linha("10:00:00.005", "settings", "connection experiments ON: video pacing (RTP packets spaced out)")) +
             projecao_de("11:00:00.000", "11:10:00.000", settings("11:00:00.004")))
        self.assertEqual([(frozenset({"experimentRtpPacing"}), True), (frozenset(), True)], self.estados(t))


def corrida(rotulo, horas, quedas=0, firmes=0, janelas=0, janelas_late=0, kbps=(), escuro_s=0.0, conhecido=True):
    return {"rotulo": rotulo, "conhecido": conhecido, "arquivo": "ride.log", "hora": "10:00:00.000", "transmitindo_s": horas * 3600.0, "quedas": quedas, "firmes": firmes,
            "janelas": janelas, "janelas_late": janelas_late, "late": janelas_late, "kbps": list(kbps), "escuro_s": escuro_s}


def grupo(rotulo, projecoes, horas, firmes, **mais):
    """[projecoes] runs, [horas] in all and [firmes] firm drops (all on the first run). The extra numbers (windows, late, kbps, dark seconds) are PER RUN."""
    saida = []
    for k in range(projecoes):
        saida.append(corrida(rotulo, horas / projecoes, quedas=firmes if k == 0 else 0, firmes=firmes if k == 0 else 0, **mais))
    return saida


class VereditoTest(unittest.TestCase):
    def veredito(self, base, outro):
        g = chaves.agrupar(base + outro)
        return chaves.veredito(g[outro[0]["rotulo"]], g.get(chaves.NENHUMA))

    def test_worse_needs_one_and_a_half_times_the_rate_and_two_more_drops(self):
        base = grupo("nenhuma", 4, 4.0, 4)  # 1 firm drop per hour
        # in 2 h the reference would have 2: 3 is 1.5 times but only 1 more; 4 is 2 times and 2 more
        self.assertEqual("sem diferença que se veja", self.veredito(base, grupo("bitrate menor", 3, 2.0, 3))[0])
        self.assertEqual("PIOR", self.veredito(base, grupo("bitrate menor", 3, 2.0, 4))[0])

    def test_the_ratio_is_what_decides_when_the_difference_is_big_enough(self):
        base = grupo("nenhuma", 4, 10.0, 10)  # 1 per hour
        # in 10 h: 10 expected; 14 is 1.4 times (+4): not enough; 15 is exactly 1.5 times: worse
        self.assertEqual("sem diferença que se veja", self.veredito(base, grupo("bitrate menor", 4, 10.0, 14))[0])
        self.assertEqual("PIOR", self.veredito(base, grupo("bitrate menor", 4, 10.0, 15))[0])

    def test_exactly_one_and_a_half_times_with_two_more_is_worse(self):
        base = grupo("nenhuma", 4, 4.0, 4)  # 1 per hour: 4 expected in 4 h
        self.assertEqual("PIOR", self.veredito(base, grupo("bitrate menor", 4, 4.0, 6))[0])

    def test_exactly_two_thirds_with_two_fewer_is_better(self):
        base = grupo("nenhuma", 4, 4.0, 12)  # 3 per hour: 6 expected in 2 h
        self.assertEqual("MELHOR", self.veredito(base, grupo("bitrate menor", 3, 2.0, 4))[0])
        self.assertEqual("sem diferença que se veja", self.veredito(base, grupo("bitrate menor", 3, 2.0, 5))[0])

    def test_better_is_the_mirror(self):
        base = grupo("nenhuma", 4, 4.0, 8)  # 2 per hour
        # in 2 h: 4 expected; 3 is not 1/1.5 of 4 (2.67) and 2 is, with 2 fewer
        self.assertEqual("sem diferença que se veja", self.veredito(base, grupo("bitrate menor", 3, 2.0, 3))[0])
        self.assertEqual("MELHOR", self.veredito(base, grupo("bitrate menor", 3, 2.0, 2))[0])

    def test_a_big_ratio_with_a_small_difference_is_chance(self):
        base = grupo("nenhuma", 4, 10.0, 1)  # 0.1 per hour
        # 1 h: 0.1 expected, 1 seen is 10 times but under 2 more
        self.assertEqual("sem diferença que se veja", self.veredito(base, grupo("bitrate menor", 3, 1.0, 1))[0])
        self.assertEqual("PIOR", self.veredito(base, grupo("bitrate menor", 3, 1.0, 3))[0])

    def test_too_little_data_in_the_group_or_in_the_reference_gives_no_verdict(self):
        base = grupo("nenhuma", 4, 4.0, 4)
        palavra, motivo = self.veredito(base, grupo("bitrate menor", 3, 0.5, 5))  # half an hour
        self.assertEqual("dados insuficientes", palavra)
        self.assertIn("0.5 h", motivo)
        self.assertEqual("dados insuficientes", self.veredito(base, grupo("bitrate menor", 2, 3.0, 5))[0])  # two runs
        self.assertEqual("dados insuficientes", self.veredito(grupo("nenhuma", 4, 0.8, 0), grupo("bitrate menor", 3, 3.0, 5))[0])
        palavra, motivo = self.veredito(grupo("nenhuma", 4, 0.8, 0), grupo("bitrate menor", 3, 3.0, 5))
        self.assertIn("a referência", motivo)

    def test_half_an_hour_is_not_enough_even_with_many_runs_and_many_runs_is_not_enough_with_little_time(self):
        base = grupo("nenhuma", 4, 4.0, 4)
        self.assertEqual("dados insuficientes", self.veredito(base, grupo("bitrate menor", 5, 0.5, 5))[0])
        self.assertEqual("dados insuficientes", self.veredito(base, grupo("bitrate menor", 2, 5.0, 5))[0])

    def test_exactly_one_hour_and_three_runs_is_enough(self):
        base = grupo("nenhuma", 3, 1.0, 3)
        self.assertNotEqual("dados insuficientes", self.veredito(base, grupo("bitrate menor", 3, 1.0, 9))[0])

    def test_without_the_reference_group_there_is_no_verdict(self):
        g = chaves.agrupar(grupo("bitrate menor", 3, 3.0, 5))
        self.assertEqual("sem referência", chaves.veredito(g["bitrate menor"], None)[0])


class TextoTest(unittest.TestCase):
    def test_the_reference_comes_first_and_each_group_gets_its_numbers_and_verdict(self):
        cs = (grupo("bitrate menor", 3, 2.0, 4, janelas=10, janelas_late=1, kbps=[200, 220], escuro_s=60.0) +
              grupo("nenhuma", 4, 4.0, 4, janelas=10, janelas_late=1, kbps=[300], escuro_s=120.0) +
              grupo("sem registro das chaves", 3, 5.0, 9, conhecido=False))
        t = chaves.texto(cs)
        self.assertTrue(t[0].startswith("nenhuma: 4 projeções, 4,0 h transmitindo, 4 quedas (1,00/h), 4 com Wi-Fi firme (1,00/h)"), t[0])
        self.assertTrue(t[0].endswith("— referência"))
        self.assertIn("bitrate menor: 3 projeções, 2,0 h transmitindo", t[1])
        self.assertIn("— PIOR: 4 quedas com Wi-Fi firme onde a referência faria ~2.0", t[1])
        self.assertIn("sem veredito (a build não escrevia as chaves)", t[-1])

    def test_late_seconds_without_image_and_kbps_come_as_notes_only_with_enough_data(self):
        cs = (grupo("bitrate menor", 3, 3.0, 0, janelas=300, janelas_late=60, kbps=[200], escuro_s=900.0) +
              grupo("nenhuma", 3, 3.0, 0, janelas=300, janelas_late=10, kbps=[300], escuro_s=60.0))
        notas = [l for l in chaves.texto(cs) if l.startswith("    nota:")]
        self.assertEqual(3, len(notas), notas)
        self.assertIn("`late` em 20.0 % das janelas contra 3.3 % da referência", notas[0])
        self.assertIn("sem imagem 900 s por hora contra 60 s da referência", notas[1])
        self.assertIn("kbps médio em movimento 200 contra 300", notas[2])
        self.assertIn("legibilidade", notas[2])

    def test_a_late_rate_under_twice_the_reference_or_with_few_windows_is_not_noted(self):
        cs = (grupo("bitrate menor", 3, 3.0, 0, janelas=300, janelas_late=50) + grupo("nenhuma", 3, 3.0, 0, janelas=300, janelas_late=30))
        self.assertEqual([], [l for l in chaves.texto(cs) if "late" in l and l.startswith("    nota")])
        cs = (grupo("bitrate menor", 3, 3.0, 0, janelas=30, janelas_late=20) + grupo("nenhuma", 3, 3.0, 0, janelas=300, janelas_late=3))  # 90 windows < 100
        self.assertEqual([], [l for l in chaves.texto(cs) if "late" in l and l.startswith("    nota")])

    def test_no_runs_no_text(self):
        self.assertEqual([], chaves.texto([]))


class FatosEFirmezaTest(unittest.TestCase):
    """The reader builds each run's facts: firm drops (good signal), not the bike switched off, hours from the resumo, late and kbps from the windows."""

    def corridas(self, texto):
        return ler_registro.corridas(lido(texto))

    def test_a_drop_with_a_good_signal_before_it_is_firm_and_one_with_a_bad_signal_is_not(self):
        t = projecao_de("10:00:00.000", "10:10:00.000", settings("10:00:00.004", "experimentRtpPacing"),
                        device("10:02:30.000", -60), queda("10:03:00.000"),
                        linha("10:03:20.000", "wifi", "bike network available"),
                        device("10:05:30.000", -82), queda("10:06:00.000"),
                        linha("10:06:20.000", "wifi", "bike network available"),
                        queda("10:08:00.000"), linha("10:08:20.000", "wifi", "bike network available"),
                        resumo("10:09:59.000", streaming="9m00s"), janela("10:05:00.000", kbps=300, late=2), janela("10:05:05.000", kbps=100, late=0, cheia="false"))
        [c] = self.corridas(t)
        self.assertEqual("espaçar o RTP", c["rotulo"])
        self.assertEqual((3, 1), (c["quedas"], c["firmes"]))  # the third has no signal in the log: not firm
        self.assertEqual(540.0, c["transmitindo_s"])
        self.assertEqual((2, 1, 2), (c["janelas"], c["janelas_late"], c["late"]))
        self.assertEqual([300], c["kbps"])  # only the full windows (in movement)

    def test_the_signal_exactly_at_the_limit_is_firm_and_one_below_is_not(self):
        for dbm, esperado in ((-75, 1), (-76, 0)):
            t = projecao_de("10:00:00.000", "10:10:00.000", settings("10:00:00.004"), device("10:02:30.000", dbm), queda("10:03:00.000"),
                            linha("10:03:20.000", "wifi", "bike network available"))
            self.assertEqual(esperado, self.corridas(t)[0]["firmes"], dbm)

    def test_the_bike_switched_off_is_not_a_drop(self):
        hist = linha("10:02:50.000", "link", "rssi=-60 dBm tx=58 rx=58 Mbps 2.4 GHz | last 60 s, every 5 s: rssi -60 -60 -61 -60 -60 -60")
        t = projecao_de("10:00:00.000", "10:10:00.000", settings("10:00:00.004"), hist, queda("10:03:00.000"))
        [c] = self.corridas(t)
        self.assertEqual((0, 0), (c["quedas"], c["firmes"]))

    def test_the_link_history_is_used_when_there_is_no_look_of_the_dash(self):
        hist = linha("10:02:50.000", "link", "rssi=-60 dBm tx=58 rx=58 Mbps 2.4 GHz | last 60 s, every 5 s: rssi -60 -60 -61 -60 -60 -60")
        t = projecao_de("10:00:00.000", "10:10:00.000", settings("10:00:00.004"), hist, queda("10:03:00.000"),
                        linha("10:03:20.000", "wifi", "bike network available"))
        [c] = self.corridas(t)
        self.assertEqual((1, 1), (c["quedas"], c["firmes"]))

    def test_a_run_without_a_resumo_has_no_hours(self):
        t = projecao_de("10:00:00.000", "10:10:00.000", settings("10:00:00.004"))
        self.assertIsNone(self.corridas(t)[0]["transmitindo_s"])

    def test_the_dark_seconds_come_from_the_resumo(self):
        t = projecao_de("10:00:00.000", "10:10:00.000", settings("10:00:00.004"), resumo("10:09:59.000", dark="2/3m02s (max 1m08s)"))
        self.assertEqual(182.0, self.corridas(t)[0]["escuro_s"])


class LinhaDeComandoTest(unittest.TestCase):
    def rodar(self, texto, *args):
        d = tempfile.mkdtemp()
        escreve(d, "ride-20261108-100000.log", texto)
        return subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), d, "--tudo", *args], capture_output=True, text=True, encoding="utf-8")

    def test_the_section_appears_with_the_groups_and_the_help_carries_the_criteria(self):
        t = (projecao_de("10:00:00.000", "10:30:00.000", settings("10:00:00.004"), resumo("10:29:59.000", streaming="30m00s")) +
             projecao_de("11:00:00.000", "11:30:00.000", settings("11:00:00.004", "experimentFocusGap"), resumo("11:29:59.000", streaming="30m00s")))
        saida = self.rodar(t).stdout
        self.assertIn("CORRIDAS POR CHAVES EXPERIMENTAIS", saida)
        self.assertIn("nenhuma: 1 projeção, 0,5 h transmitindo", saida)
        self.assertIn("pausa do foco: 1 projeção", saida)
        ajuda = subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), "--help"], capture_output=True, text=True, encoding="utf-8").stdout
        for pedaco in ("CRITÉRIOS", "queda com Wi-Fi firme", "-75 dBm", "1 h e 3 projeções", "1,5 vez"):
            self.assertIn(pedaco, ajuda)

    def test_logs_from_a_build_before_the_keys_say_there_is_nothing_to_compare(self):
        t = projecao_de("10:00:00.000", "10:30:00.000", settings("10:00:00.004", com_campos=False))
        self.assertIn("nenhuma das 1 projeções traz o estado das chaves", self.rodar(t).stdout)


if __name__ == "__main__":
    unittest.main()
