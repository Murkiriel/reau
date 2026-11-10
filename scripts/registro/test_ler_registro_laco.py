"""Testes do aviso de laço de reconexão (estudo de 10/11/2026): duas ou mais sessões seguidas que chegaram a
`→ STREAMING` e caíram em menos de 60 s. Trechos sintéticos.

    python -B scripts/registro/test_ler_registro_laco.py
"""
import sys
import tempfile
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import ler_registro  # noqa: E402
from test_ler_registro import escreve, linha, projecao  # noqa: E402


def streaming(hora):
    return linha(hora, "session", "AUTHENTICATING → STREAMING")


def queda(hora):
    return linha(hora, "error", "E: session fail: Dash stopped responding — connection lost")


def problemas(*meio):
    pasta = Path(tempfile.mkdtemp())
    escreve(pasta, "ride-20261004-100000.log", projecao(*meio))
    return ler_registro.problemas(ler_registro.ler([pasta]))


def lacos(*meio):
    return [p for p in problemas(*meio) if "laço de reconexão" in p]


class LacoDeReconexao(unittest.TestCase):
    def test_duas_sessoes_curtas_seguidas_acusam_com_a_hora_e_a_duracao_de_cada_uma(self):
        achados = lacos(streaming("10:01:00.000"), queda("10:01:30.000"), streaming("10:01:40.000"), queda("10:02:10.500"))
        self.assertEqual(1, len(achados), achados)
        self.assertIn("10:01:00", achados[0])
        self.assertIn("2 sessões seguidas", achados[0])
        self.assertIn("10:01:00.000 por 30 s", achados[0])
        self.assertIn("10:01:40.000 por 30 s", achados[0])

    def test_tres_seguidas_sao_um_aviso_so(self):
        achados = lacos(streaming("10:01:00.000"), queda("10:01:20.000"), streaming("10:01:30.000"), queda("10:01:50.000"),
                        streaming("10:02:00.000"), queda("10:02:25.000"))
        self.assertEqual(1, len(achados), achados)
        self.assertIn("3 sessões seguidas", achados[0])

    def test_uma_sessao_curta_isolada_nao_acusa(self):
        # O que os registros reais trazem (40,3 s em 31/10 e 30,1 s em 05/11): uma só, no meio de sessões longas.
        self.assertEqual([], lacos(streaming("10:01:00.000"), queda("10:01:40.300")))

    def test_curtas_separadas_por_uma_longa_nao_acusam(self):
        self.assertEqual([], lacos(streaming("10:01:00.000"), queda("10:01:30.000"), streaming("10:01:40.000"), queda("10:05:40.000"),
                                   streaming("10:05:50.000"), queda("10:06:20.000")))

    def test_sessao_de_exatos_60_s_nao_e_curta(self):
        self.assertEqual([], lacos(streaming("10:01:00.000"), queda("10:02:00.000"), streaming("10:02:10.000"), queda("10:02:40.000")))

    def test_sessao_que_acabou_porque_o_piloto_desligou_nao_conta(self):
        # Sem `session fail` depois do STREAMING a sessão não é queda: a projeção acaba (desligada) e nada é somado.
        self.assertEqual([], lacos(streaming("10:01:00.000"), queda("10:01:30.000"), streaming("10:01:40.000")))

    def test_falhas_de_tentativas_que_nao_chegaram_a_transmitir_nao_formam_laco(self):
        # A espera com a rede recusada (EPERM) repete `session fail` sem nenhum STREAMING: não são sessões que transmitiram.
        eperm = [queda(f"10:0{i}:00.000") for i in range(2, 7)]
        self.assertEqual([], lacos(streaming("10:01:00.000"), queda("10:01:30.000"), *eperm))

    def test_a_falha_repetida_da_mesma_queda_nao_cria_outra_sessao(self):
        self.assertEqual([], lacos(streaming("10:01:00.000"), queda("10:01:30.000"), queda("10:01:30.500"), queda("10:01:31.000")))

    def test_o_aviso_e_um_problema_do_leitor(self):
        achados = problemas(streaming("10:01:00.000"), queda("10:01:30.000"), streaming("10:01:40.000"), queda("10:02:10.000"))
        self.assertTrue(any("possível laço de reconexão" in p for p in achados), achados)

    def test_projecoes_diferentes_nao_se_somam(self):
        # Uma sessão curta ao fim de uma projeção e outra no começo da seguinte: o painel foi desligado e ligado entre elas.
        desliga = linha("10:05:00.000", "projection", "---- projeção desligada: projection ----")
        liga = linha("10:20:01.000", "projection", "---- projeção ligada: projection ----")
        pasta = Path(tempfile.mkdtemp())
        escreve(pasta, "ride-20261004-100000.log",
                projecao(streaming("10:01:00.000"), queda("10:01:30.000"), desliga, liga, streaming("10:20:10.000"), queda("10:20:40.000"), desligada=False))
        self.assertEqual(2, len(ler_registro.projecoes(ler_registro.ler([pasta]))))
        self.assertEqual([], [p for p in ler_registro.problemas(ler_registro.ler([pasta])) if "laço de reconexão" in p])


if __name__ == "__main__":
    unittest.main()
