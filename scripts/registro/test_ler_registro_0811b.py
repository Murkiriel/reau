"""Testes das correções do leitor feitas a partir da análise da coleta de 08/11/2026, noite (análise da coleta, §5):
(a) a união que foi a resposta ao pedido do REau não é "o Android o uniu sozinho"; (b) o aviso de cobertura do logcat do sistema fala de cada buffer (o `main` que só começa no meio
da corrida) e a enchente do HAL térmico não conta como cobertura; (c) "moto desligada" sai dos PROBLEMAS; (d) `--desde`/`--ate` valem também para o dumpsys do Wi-Fi e para os
`logcat-*.txt` do app. Trechos sintéticos.

    python -B scripts/registro/test_ler_registro_0811b.py
"""
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import ler_registro  # noqa: E402
import quedas  # noqa: E402
from test_ler_registro import LOGCAT_WIFI, escreve, linha, projecao, resumo_com_dark  # noqa: E402
from test_ler_registro_0811 import coleta, dumpsys, uniao  # noqa: E402


def lido(texto, nome="ride-20261004-100000.log"):
    d = Path(tempfile.mkdtemp())
    escreve(d, nome, texto)
    return ler_registro.ler([d])


class QuemUniuOPainel(unittest.TestCase):
    """(a) 16:57:52 de 08/11: a união foi a resposta ao pedido #4 do REau e o leitor dizia "o Android o uniu sozinho"."""

    IDADE = linha("10:01:08.000", "auth", "dash heartbeat age 18 s at the first packet")
    STREAM = linha("10:01:10.000", "session", "→ STREAMING")

    def textos(self, unido):
        bloco = ler_registro.projecoes(lido(projecao(linha("10:01:00.000", "wifi", "requestNetwork 'RE_****30' everConnected=false timeout=30000ms; request #4 (27s since "
                                                          "the first), scan of 16 networks, the dash not among them"), unido, self.IDADE, self.STREAM)))[0]
        grupos = ler_registro._grupos_de_queda(bloco)
        return list(quedas.ligado_unido(bloco, quedas.interrupcoes(bloco, grupos)).values())

    def test_a_uniao_que_foi_a_resposta_ao_pedido_do_reau_diz_o_numero_do_pedido(self):
        t = self.textos(linha("10:01:07.000", "wifi", "connected 'RE_****30' in 27523ms (network 172, the answer to REau's request #4; not-restricted=true, validated=true)"))
        self.assertEqual(1, len(t), t)
        self.assertIn("a união foi a resposta ao pedido #4 do REau", t[0])
        self.assertIn("o app não viu o painel na última varredura", t[0])
        self.assertNotIn("uniu sozinho", t[0])

    def test_a_uniao_que_o_celular_fez_sozinho_continua_dita_assim(self):
        t = self.textos(linha("10:01:07.000", "wifi", "using already-connected WiFi 'RE_****30' (network 297, joined by the phone itself (a suggestion or a saved network), "
                                                      "not the answer to REau's request #4; not-restricted=false, validated=false)"))
        self.assertEqual(1, len(t), t)
        self.assertIn("o celular o uniu sozinho", t[0])
        self.assertNotIn("resposta ao pedido", t[0])

    def test_um_registro_de_antes_que_nao_dizia_de_quem_foi_fica_como_estava(self):
        t = self.textos(linha("10:01:07.000", "wifi", "connected 'RE_****30' in 900ms"))
        # A line that says nothing of who asked does not say "alone" (11/11/2026): the union may well have been REau's own request.
        self.assertNotIn("uniu sozinho", t[0])
        self.assertIn("não diz de quem foi a união", t[0])


def logcat(*blocos):
    """Um logcat-sistema.txt de 04/10: cada bloco é (hora inicial HH:MM, hora final HH:MM, etiqueta, n) com n linhas espalhadas entre as duas horas."""
    saida = []
    for ini, fim, etiqueta, n in blocos:
        (h1, m1), (h2, m2) = (map(int, ini.split(":")), map(int, fim.split(":")))
        total = (h2 * 60 + m2) - (h1 * 60 + m1)
        for i in range(n):
            minuto = h1 * 60 + m1 + (i * total) // n
            saida.append(f"10-04 {minuto // 60:02d}:{minuto % 60:02d}:{i % 60:02d}.000  900  900 I {etiqueta}: linha {i}\n")
    return "".join(saida)


class CoberturaPorBuffer(unittest.TestCase):
    """(b) 08/11: o buffer `main` só começava no meio da corrida e a enchente térmica fazia todo horário parecer coberto."""

    def avisos(self, texto_do_logcat, ride=None):
        raiz = Path(tempfile.mkdtemp())
        escreve(raiz, "diag/ride-20261004-100000.log", ride or projecao())
        escreve(raiz, "logcat-sistema.txt", texto_do_logcat)
        diag = raiz / "diag"
        return ler_registro.avisos_da_leitura([diag], ler_registro.ler([diag]))

    def test_o_main_que_so_comeca_no_meio_da_projecao_e_avisado_mesmo_com_a_enchente_cobrindo_o_horario(self):
        # the projection is 10:00 to 10:09; the system buffer is all there, the main one starts at 10:05; the thermal flood covers every minute
        texto = logcat(("09:00", "10:30", "ActivityManager", 300), ("10:05", "10:30", "wpa_supplicant", 40), ("09:00", "10:30", "ThermalHalWrapper", 3000))
        avisos = self.avisos(texto)
        main = [a for a in avisos if "o buffer `main`" in a]
        self.assertEqual(1, len(main), avisos)
        self.assertIn("só começa às 04/10 10:05", main[0])
        self.assertIn("1 em parte", main[0])
        self.assertIn("`wpa_supplicant`", main[0])
        self.assertEqual([], [a for a in avisos if "o buffer `system`" in a])

    def test_o_main_que_so_comeca_depois_da_projecao_deixa_a_projecao_inteira_sem_ele(self):
        texto = logcat(("09:00", "10:30", "ActivityManager", 300), ("10:20", "10:30", "CAR.SERVICE", 40))
        main = [a for a in self.avisos(texto) if "o buffer `main`" in a]
        self.assertEqual(1, len(main))
        self.assertIn("1 das 1 projeções lidas ficam inteiras sem as etiquetas dele e 0 em parte", main[0])
        self.assertIn("enquanto o `system` vai desde 04/10 09:00", main[0])

    def test_os_dois_buffers_cobrindo_a_projecao_nao_avisam(self):
        texto = logcat(("09:00", "10:30", "ActivityManager", 300), ("09:30", "10:30", "wpa_supplicant", 100))
        self.assertEqual([], self.avisos(texto))

    def test_sem_nenhuma_etiqueta_conhecida_nao_ha_o_que_dizer_do_buffer(self):
        self.assertEqual([], [a for a in self.avisos(logcat(("09:00", "10:30", "Tag", 400))) if "buffer" in a])

    def test_a_enchente_nao_conta_como_cobertura(self):
        # nothing but the flood around the projection: the old count (lines per hour) said "covered"
        avisos = self.avisos(logcat(("09:00", "10:30", "ThermalHalWrapper", 3000), ("11:00", "11:30", "Tag", 400)))
        self.assertTrue(any("não cobre 1 das 1 projeções" in a for a in avisos), avisos)

    def test_a_enchente_e_dita_com_o_numero_de_linhas_e_a_parte_do_arquivo(self):
        avisos = self.avisos(logcat(("09:00", "10:30", "ActivityManager", 300), ("09:00", "10:30", "ThermalHalWrapper", 700)))
        enchente = [a for a in avisos if "HAL térmico" in a]
        self.assertEqual(1, len(enchente), avisos)
        self.assertIn("700 linhas (70 %)", enchente[0])
        # a few lines of it (under a tenth of the file) are not a flood
        self.assertEqual([], [a for a in self.avisos(logcat(("09:00", "10:30", "ActivityManager", 300), ("09:00", "10:30", "ThermalHalWrapper", 5))) if "HAL térmico" in a])

    def test_o_aviso_vai_para_os_avisos_da_leitura_e_nao_muda_o_codigo_de_saida(self):
        raiz = Path(tempfile.mkdtemp())
        escreve(raiz, "diag/ride-20261004-100000.log", projecao())
        escreve(raiz, "logcat-sistema.txt", logcat(("09:00", "10:30", "ActivityManager", 300), ("10:05", "10:30", "wpa_supplicant", 40)))
        feito = subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), str(raiz / "diag")], capture_output=True, text=True, encoding="utf-8")
        avisos = feito.stdout.partition("AVISOS DA LEITURA")[2]
        self.assertIn("o buffer `main` do logcat do sistema só começa", avisos)
        self.assertEqual(0, feito.returncode)


class MotoDesligada(unittest.TestCase):
    """(c) as quedas que o próprio leitor chama de "painel desligou (moto desligada?)" saíam nos PROBLEMAS como defeito."""

    FIRME = "rssi=-58 dBm tx=72 rx=72 Mbps 2.4 GHz powersave=on screen=on | last 60 s, every 5 s: rssi -50 -58 -58 -58 -58 | tx 72 72 72 72 72"
    FRACO = "rssi=-88 dBm tx=6 rx=6 Mbps 2.4 GHz powersave=on screen=on | last 60 s, every 5 s: rssi -60 -72 -85 -88 -88 | tx 72 40 12 6 6"

    def ride(self, historico, *depois):
        return projecao(linha("10:04:30.000", "link", historico),
                        linha("10:05:00.000", "wifi", "W: link lost — reconnecting in 4000ms (after 59 s on 2447 MHz; rssi -42)"),
                        linha("10:05:00.003", "system", "bike network lost"), *depois)

    def coleta(self, historico, *depois):
        raiz = Path(tempfile.mkdtemp())
        escreve(raiz, "diag/ride-20261004-100000.log", self.ride(historico, *depois))
        escreve(raiz, "logcat-sistema.txt", LOGCAT_WIFI)
        return raiz

    def do_sistema(self, raiz):
        return [a for a in ler_registro.do_sistema([raiz / "diag"], ler_registro.ler([raiz / "diag"])) if "o Wi-Fi do painel caiu" in a]

    def test_a_queda_da_moto_desligada_leva_a_marca_e_a_de_defeito_nao(self):
        moto = self.do_sistema(self.coleta(self.FIRME))
        self.assertEqual(1, len(moto), moto)
        self.assertIn("o Wi-Fi do painel caiu (moto desligada) — o sistema disse", moto[0])
        defeito = self.do_sistema(self.coleta(self.FRACO))
        self.assertEqual(1, len(defeito), defeito)
        self.assertNotIn("moto desligada", defeito[0])

    def test_o_programa_poe_a_moto_desligada_em_secao_propria_e_o_codigo_de_saida_nao_a_conta(self):
        feito = subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), str(self.coleta(self.FIRME) / "diag")], capture_output=True, text=True, encoding="utf-8")
        self.assertIn("nenhum problema conhecido", feito.stdout)
        secao = feito.stdout.partition("MOTO DESLIGADA (")[2]
        self.assertIn("o Wi-Fi do painel caiu (moto desligada)", secao)
        self.assertEqual(0, feito.returncode)

    def test_a_queda_que_nao_e_da_moto_continua_nos_problemas(self):
        feito = subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), str(self.coleta(self.FRACO) / "diag")], capture_output=True, text=True, encoding="utf-8")
        problemas = feito.stdout.partition("PROBLEMAS:")[2]
        self.assertIn("o Wi-Fi do painel caiu — o sistema disse", problemas)
        self.assertNotIn("MOTO DESLIGADA (", feito.stdout)

    def test_o_dumpsys_marca_a_queda_da_moto(self):
        # the dumpsys has the NETWORK_DISCONNECTION_EVENT of the drop of 10:05:00 (04/10)
        raiz = self.coleta(self.FIRME)
        escreve(raiz, "sistema/dumpsys-wifi.txt", "mConnectionEvents:\nmWifiLogProto.numSavedNetworks=1\nStaEventList:\n"
                "10-04 10:05:00.060 NETWORK_DISCONNECTION_EVENT local_gen=false reason=3:DEAUTH_LEAVING 2447 -54\n")
        texto = "\n".join(ler_registro.do_dumpsys_wifi([raiz / "diag"], ler_registro.ler([raiz / "diag"])))
        self.assertIn("o Wi-Fi do painel caiu (moto desligada)", texto)

    def test_o_painel_sem_imagem_do_resumo_que_veio_de_uma_queda_da_moto_leva_a_marca(self):
        # 17:05:50 to 17:12:26 of 08/11: the dash vanished with a steady Wi-Fi, was back minutes later (6 min 34 s dark), and the resumo counts it
        ride = projecao(
            linha("10:01:00.000", "session", "→ STREAMING"),
            linha("10:01:40.000", "link", self.FIRME),
            linha("10:02:00.000", "wifi", "W: link lost — reconnecting in 4000ms (after 59 s on 2447 MHz; rssi -42)"),
            linha("10:02:00.003", "system", "bike network lost"),
            linha("10:02:00.500", "stream", "video loop stop (0 frames held back before a keyframe)"),
            linha("10:08:30.000", "wifi", "connected 'RE_****30' in 900ms"),
            linha("10:08:34.000", "session", "→ STREAMING"),
            linha("10:09:00.000", "projection", resumo_com_dark("1/6m34s (max 6m34s)")))
        p = [x for x in ler_registro.problemas(lido(ride)) if "painel sem imagem" in x]
        self.assertEqual(1, len(p), p)
        self.assertIn("painel sem imagem (moto desligada) — 1 interrupção", p[0])

    def test_o_painel_sem_imagem_de_uma_interrupcao_sem_queda_continua_sem_a_marca(self):
        ride = projecao(
            linha("10:01:00.000", "session", "→ STREAMING"),
            linha("10:04:00.000", "stream", "video loop stop (0 frames held back before a keyframe)"),
            linha("10:07:00.000", "session", "→ STREAMING"),
            linha("10:09:00.000", "projection", resumo_com_dark("1/3m00s (max 3m00s)")))
        p = [x for x in ler_registro.problemas(lido(ride)) if "painel sem imagem" in x]
        self.assertEqual(1, len(p), p)
        self.assertNotIn("moto desligada", p[0])


class JanelaVaiAteOsOutrosArquivos(unittest.TestCase):
    """(d) `--desde` só cortava os `ride-*.log`: as uniões do dumpsys e os Davey dos `logcat-*.txt` de dias antes entravam nos PROBLEMAS."""

    DESDE = datetime(2026, 11, 8, 16, 21)

    def test_as_unioes_do_reau_do_dumpsys_antes_da_janela_saem(self):
        raiz = coleta(dumpsys(uniao("3:10:5.000", 10492), uniao("12:30:5.000", 10492)))
        linhas = ler_registro.ler([raiz / "diag"])
        sem_janela = [a for a in ler_registro.do_dumpsys_wifi([raiz / "diag"], linhas) if "sem nenhum registro do REau" in a]
        self.assertEqual(2, len(sem_janela), sem_janela)
        com = [a for a in ler_registro.do_dumpsys_wifi([raiz / "diag"], linhas, (datetime(2026, 11, 8, 10, 0), None)) if "sem nenhum registro do REau" in a]
        self.assertEqual(1, len(com), com)
        self.assertTrue(com[0].startswith("08/11 12:30:05"), com[0])
        ate = [a for a in ler_registro.do_dumpsys_wifi([raiz / "diag"], linhas, (None, datetime(2026, 11, 8, 10, 0))) if "sem nenhum registro do REau" in a]
        self.assertEqual(1, len(ate), ate)
        self.assertTrue(ate[0].startswith("08/11 03:10:05"), ate[0])

    def test_as_unioes_de_outros_apps_antes_da_janela_saem_dos_avisos(self):
        raiz = coleta(dumpsys(uniao("0:41:8.185", 10223), uniao("12:35:5.000", 10223)))
        linhas = ler_registro.ler([raiz / "diag"])
        todos = ler_registro.avisos_do_dumpsys_wifi([raiz / "diag"], linhas)
        self.assertIn("2 uniões", todos[0])
        so_a_tarde = ler_registro.avisos_do_dumpsys_wifi([raiz / "diag"], linhas, (datetime(2026, 11, 8, 10, 0), None))
        self.assertIn("1 união", so_a_tarde[0])
        self.assertTrue(so_a_tarde[0].startswith("08/11 12:35:05"), so_a_tarde)

    def test_o_davey_do_logcat_do_app_antes_da_janela_sai(self):
        raiz = Path(tempfile.mkdtemp())
        escreve(raiz, "diag/ride-20261108-100000.log", projecao())
        # `logcat-<hora>.txt` of the app: two Davey! of the REau's process (pid 900), one at 04:58 and one at 10:05 of 08/11
        base = ("11-08 04:58:24.000  900  900 I app: start proc [0,900,10492,com.reau.app,\n11-08 04:58:24.100  900  900 I Looper: Davey! duration=1090ms\n"
                "11-08 10:05:00.100  900  900 I Looper: Davey! duration=950ms\n")
        escreve(raiz, "diag/logcat-20261108-100000.txt", base)
        linhas = ler_registro.ler([raiz / "diag"])
        antes = [a for a in ler_registro.do_sistema([raiz / "diag"], linhas) if "travou a tela" in a]
        depois = [a for a in ler_registro.do_sistema([raiz / "diag"], linhas, (datetime(2026, 11, 8, 8, 0), None)) if "travou a tela" in a]
        self.assertGreater(len(antes), len(depois), (antes, depois))
        self.assertFalse(any("04:58" in a for a in depois), depois)

    def test_o_programa_passa_o_desde_e_o_ate_adiante(self):
        raiz = coleta(dumpsys(uniao("3:10:5.000", 10492), uniao("12:30:5.000", 10492)))
        def roda(*opcoes):
            return subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), str(raiz / "diag"), *opcoes], capture_output=True, text=True, encoding="utf-8").stdout
        self.assertEqual(2, roda("--tudo").count("sem nenhum registro do REau"))
        so_a_tarde = roda("--desde", "2026-11-08T10:00")
        self.assertEqual(1, so_a_tarde.count("sem nenhum registro do REau"))
        self.assertIn("08/11 12:30:05", so_a_tarde)


if __name__ == "__main__":
    unittest.main()
