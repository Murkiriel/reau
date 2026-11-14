"""Tests of the reader for the log lines of the experimental keys of the fifth round (11/11/2026): the tolerant dash watch, the GPS speed of the profile, the encoder's colours,
protocol 6.0, the battery's frame ceiling and a short "inactive" of the route shown as a recalculation. Synthetic logs only; the lines are the ones the app writes."""
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ler_registro  # noqa: E402
from test_ler_registro import escreve, linha, projecao  # noqa: E402


def _pasta(texto):
    d = Path(tempfile.mkdtemp())
    escreve(d, "ride-20261111-100000.log", texto)
    return d


class LinhasDaLeva5(unittest.TestCase):
    def tempo(self, *meio):
        return "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([_pasta(projecao(*meio))])))

    def test_the_tolerant_dash_watch_says_the_silence_and_the_return(self):
        t = self.tempo(
            linha("10:03:00.000", "dash", "RX quiet: no dash packets for 12.0 s; the session goes on and ends at 60 s of silence (tolerant watch, experiment)"),
            linha("10:03:20.000", "dash", "RX back: the dash spoke again after 18.5 s of silence; a keyframe is asked (tolerant watch, experiment)"),
        )
        self.assertIn("painel calado há 12.0 s: só registrado, a sessão segue (vigia tolerante, chave experimental)", t)
        self.assertIn("painel voltou depois de 18.5 s calado: quadro-chave pedido (vigia tolerante, chave experimental)", t)

    def test_the_route_card_held_as_a_recalculation_says_its_three_ends(self):
        t = self.tempo(
            linha("10:03:00.000", "dash", "route: a short inactive shown as a recalculation; holding it up to 8 s (experiment)"),
            linha("10:03:03.200", "dash", "route: the guidance is back after 3.2 s of inactive (experiment)"),
            linha("10:04:00.000", "dash", "route: a short inactive shown as a recalculation; holding it up to 8 s (experiment)"),
            linha("10:04:08.000", "dash", "route: the inactive did not end in 8 s; the route card is cleared (experiment)"),
        )
        for esperado in ("um «inativo» curto do Android Auto virou recalculando por até 8 s", "a guiagem voltou depois de 3.2 s de «inativo»: era um recalculo",
                         "o «inativo» não acabou em 8 s: o cartão da rota foi apagado"):
            self.assertIn(esperado, t)

    def test_the_gps_speed_of_the_profile_and_the_way_back(self):
        t = self.tempo(
            linha("10:03:00.000", "stream", "speed profile: reads the GPS speed (72 km/h; the route says 60 km/h)"),
            linha("10:05:00.000", "stream", "speed profile: back to the route's estimate (no GPS speed, or no route)"),
        )
        self.assertIn("perfil de velocidade passou a ler o GPS: 72 km/h (a rota diz 60 km/h); chave experimental", t)
        self.assertIn("perfil de velocidade voltou à estimativa da rota", t)

    def test_the_encoder_colours_asked_accepted_and_refused(self):
        t = self.tempo(
            linha("10:00:02.000", "stream", "encoder colour: asked BT.601 limited-range SDR; the codec says standard=BT.601-NTSC, range=limited, transfer not echoed"),
            linha("10:00:03.000", "stream", "encoder c2.vendor.avc.encoder refused the colour keys (BT.601 limited-range SDR): IllegalArgumentException; asked again without them"),
        )
        self.assertIn("cores do codificador pedidas (BT.601 limited-range SDR); o codec diz: standard=BT.601-NTSC, range=limited, transfer not echoed", t)
        self.assertIn("o codificador recusou as cores pedidas (BT.601 limited-range SDR): IllegalArgumentException; pedido de novo sem elas", t)

    def test_protocol_six_asked_and_answered(self):
        bom = self.tempo(linha("10:00:02.500", "aa", "[AA] Handshake: asked protocol 6.0, answered 1.7, status 0: the handshake completed"))
        self.assertIn("protocolo 6.0 pedido ao Android Auto, respondido «1.7, status 0»: o aperto de mão completou (chave experimental)", bom)
        ruim = self.tempo(linha("10:00:02.500", "aa", "[AA] Handshake: asked protocol 6.0, answered 1.7, status -1: the handshake did not complete; protocol 1.7 is asked from now on, until the app is closed"))
        self.assertIn("o aperto de mão NÃO completou: o 1.7 volta a ser pedido até o app fechar", ruim)

    def test_the_battery_ceiling_by_step(self):
        t = self.tempo(
            linha("10:03:00.000", "heat", "battery at 44.3 C: the frame ceiling is held under the moderate step (experiment)"),
            linha("10:20:00.000", "heat", "battery back to 40.0 C: the frame ceiling no longer follows the battery (experiment)"),
        )
        self.assertIn("teto de quadros preso pela bateria a 44.3 °C, no degrau «moderate» do estado térmico (chave experimental)", t)
        self.assertIn("teto de quadros solto: bateria a 40.0 °C (chave experimental)", t)

    def test_the_old_heat_lines_are_read_as_before(self):
        t = self.tempo(linha("10:03:00.000", "heat", "battery at 44.5 C (charging=true, thermal=none): at or over 44.0 C, rider told the phone is hot"))
        self.assertIn("aviso de celular quente: bateria a 44.5 °C, piloto avisado", t)


if __name__ == "__main__":
    unittest.main()
