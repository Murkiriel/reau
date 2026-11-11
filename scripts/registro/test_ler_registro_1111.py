"""Tests of the reader's gaps closed on 11/11/2026 (items 25 and 36 of the survey): the time without picture counted from the line that says the last packet, and the union that
the line does not say whose it was. Synthetic logs only."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ler_registro  # noqa: E402
import quedas  # noqa: E402
from test_ler_registro import linha  # noqa: E402
from test_ler_registro_17a import lido  # noqa: E402
from test_ler_registro import projecao  # noqa: E402


class TempoSemImagemDoUltimoPacote(unittest.TestCase):
    """08/11 13:02: the RX watchdog line (13:02:03.759) came 2,3 s before the line that carries `last dash packet 6.5 s before` (13:02:06.071); the time was counted from the
    watchdog's, 98,3 s; counted from the line that says it, ~96,0 s (91,9 s since the last video frame is the app's own `dark=`)."""

    WATCHDOG = linha("10:02:03.759", "watchdog", "RX watchdog: nothing from the dash for 4 s")
    QUEDA = linha("10:02:06.071", "wifi", "W: link lost — reconnecting in 4000ms (after 1005 s on 2452 MHz; rssi -52, last dash packet 6.5 s before)")

    def interrupcoes(self, *meio):
        bloco = ler_registro.projecoes(lido(projecao(*meio)))[0]
        return quedas.interrupcoes(bloco, ler_registro._grupos_de_queda(bloco))

    def test_the_last_packet_is_counted_from_the_line_that_says_it_not_from_the_first_line_of_the_drop(self):
        i = self.interrupcoes(self.WATCHDOG, self.QUEDA, linha("10:03:35.600", "session", "→ STREAMING"))
        self.assertEqual(1, len(i))
        self.assertAlmostEqual(96.0, i[0]["total"], places=1)  # not 98,3
        self.assertIn("sem imagem 96,0 s desde a queda das 10:02:03", quedas.texto_da_interrupcao(i[0]))

    def test_the_final_interruption_has_the_same_count(self):
        i = self.interrupcoes(self.WATCHDOG, self.QUEDA)  # no STREAMING again: it runs to the end of the projection (10:09:00.000)
        self.assertTrue(i[0]["final"])
        self.assertAlmostEqual(420.4, i[0]["total"], places=1)  # from 10:01:59.571, not from 10:02:03.759 (416,2 s)

    def test_a_single_line_drop_is_counted_as_before(self):
        i = self.interrupcoes(linha("10:02:00.200", "wifi", "W: link lost — reconnecting in 4000ms (after 1005 s on 2452 MHz; rssi -52, last dash packet 0.2 s before)"),
                              linha("10:02:30.000", "session", "→ STREAMING"))
        self.assertAlmostEqual(30.0, i[0]["total"], places=1)

    def test_a_drop_whose_lines_do_not_say_the_last_packet_counts_from_the_first_line(self):
        i = self.interrupcoes(self.WATCHDOG, linha("10:03:35.759", "session", "→ STREAMING"))
        self.assertAlmostEqual(92.0, i[0]["total"], places=1)


class UniaoSemDizerDeQuemFoi(unittest.TestCase):
    """The reader only says "the phone joined it by itself" when the line says so; a line that says nothing does not claim it."""

    def textos(self, uniao):
        bloco = ler_registro.projecoes(lido(projecao(linha("10:01:00.000", "wifi", "requestNetwork 'RE_****30' everConnected=false timeout=30000ms; request #4 (27s since "
                                                          "the first), scan of 16 networks, the dash not among them"), uniao,
                                                  linha("10:01:08.000", "auth", "dash heartbeat age 18 s at the first packet"), linha("10:01:10.000", "session", "→ STREAMING"))))[0]
        return list(quedas.ligado_unido(bloco, quedas.interrupcoes(bloco, ler_registro._grupos_de_queda(bloco))).values())

    def test_the_answer_to_the_request_says_the_number(self):
        [t] = self.textos(linha("10:01:07.000", "wifi", "connected 'RE_****30' in 27523ms (network 172, the answer to REau's request #4)"))
        self.assertIn("a união foi a resposta ao pedido #4 do REau", t)
        self.assertNotIn("o celular o uniu sozinho", t)

    def test_only_the_line_that_says_the_phone_itself_says_it(self):
        [t] = self.textos(linha("10:01:07.000", "wifi", "using already-connected WiFi 'RE_****30' (network 297, joined by the phone itself (a suggestion), not the answer to REau's request #4)"))
        self.assertIn("o celular o uniu sozinho", t)

    def test_a_line_that_says_nothing_of_who_asked_does_not_claim_it_was_alone(self):
        [t] = self.textos(linha("10:01:07.000", "wifi", "connected 'RE_****30' in 900ms"))
        self.assertNotIn("sozinho", t.replace("ou o celular sozinho", ""))
        self.assertIn("não diz de quem foi a união: pode ter sido o pedido do REau ou o celular sozinho", t)


if __name__ == "__main__":
    unittest.main()
