"""Testes do ler_registro.py.

    python -B scripts\\registro\\test_ler_registro.py
"""
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import ler_registro  # noqa: E402

CABECALHO = "10:00:00.000  +     0ms  ==== process start: app — Google Pixel, Android 16 (API 36) ====\n"


def linha(hora, etiqueta, texto):
    return f"{hora}  +     0ms  [{etiqueta}] {texto}\n"


def projecao(*meio, desligada=True):
    texto = CABECALHO + "10:00:01.000  +  1000ms  ---- projeção ligada: projection ----\n" + "".join(meio)
    if desligada:
        texto += "10:09:00.000  +  1000ms  ---- projeção desligada: projection ----\n"
    return texto


BOA = projecao(
    linha("10:00:02.000", "aa", "[AA] AaReceiver: connected to the head unit server on :5277, starting the handshake"),
    # "Android Auto conectado" comes from the answered handshake, not the dial.
    linha("10:00:02.500", "aa", "[AA] Handshake: Status OK sent (8). Handshake successful."),
    linha("10:00:03.000", "wifi", "connected 'RE_****34' in 900ms"),
    linha("10:00:04.000", "session", "AUTHENTICATING → STREAMING"),
    linha("10:00:05.000", "dash", "dash DECODED first IDR (09 06 55) — video accepted ✓"),
    linha("10:00:10.000", "stream", "video fps target=16 sent=15.9 (80frames/5.0s) composed=16.0 dash=15.0 kbps=900"),
    linha("10:00:15.000", "stream", "video fps target=16 sent=15.9 (80frames/5.0s) composed=16.0 dash=15.0 kbps=900"),
    linha("10:00:20.000", "device", "device battery=80% charging=no temp=31.0C volt=4.100V thermal=none headroom=0.40 [start]"),
    linha("10:05:00.000", "mark", "MARCA DO PILOTO 1"),
    linha("10:08:50.000", "device", "device battery=76% charging=no temp=33.0C volt=4.000V thermal=none headroom=0.45 [30 s]"),
    linha("10:09:00.000", "projection", "resumo duration=9m00s streaming=8m56s drops dash=0 aa=0 watchdogs=0 frames=8500 fps avg=15.9 min=15.0 gap max=0.3s thermal max=none battery 80→76% (-4) marks=1"),
)


class Ler(unittest.TestCase):
    def arquivo(self, texto, nome="ride-20261004-100000.log"):
        d = Path(tempfile.mkdtemp())
        (d / nome).write_text(texto, encoding="utf-8")
        return d / nome

    def problemas(self, texto):
        return ler_registro.problemas(ler_registro.ler([self.arquivo(texto)]))

    def test_uma_projecao_boa_nao_acusa_nada_e_conta_a_historia(self):
        linhas = ler_registro.ler([self.arquivo(BOA)])
        self.assertEqual([], ler_registro.problemas(linhas))
        tempo = "\n".join(ler_registro.linha_do_tempo(linhas))
        for esperado in ("projeção ligada", "Android Auto conectado", "painel no Wi-Fi", "transmitindo",
                         "primeiro quadro confirmado", "marca do piloto 1", "bateria 80% → 76%", "projeção desligada", "resumo"):
            self.assertIn(esperado, tempo)

    def test_transmitindo_sem_confirmacao_do_primeiro_quadro(self):
        texto = BOA.replace("dash DECODED first IDR (09 06 55) — video accepted ✓", "algo outro")
        self.assertTrue(any("sem confirmação do primeiro quadro" in p for p in self.problemas(texto)))

    def test_o_aviso_de_10_s_do_painel_novo_conta_como_aviso_e_nao_como_quadro_decodificado(self):
        novo = "dash notice 09 06 55 at +10.2 s of the burst (the dash's own 10 s mark; not proof of a decoded frame)"
        texto = BOA.replace("dash DECODED first IDR (09 06 55) — video accepted ✓", novo)
        self.assertFalse(any("sem confirmação do primeiro quadro" in p for p in self.problemas(texto)))
        self.assertNotIn("primeiro quadro confirmado pelo painel", "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)]))))
        self.assertIn("aviso de 10 s do painel (09 06 55)", "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)]))))

    def test_o_nivel_no_comeco_do_texto_fica_a_parte_e_nao_atrapalha_nada(self):
        texto = projecao(
            linha("10:01:00.000", "aa", "[AA] AaReceiver: connected to the head unit server on :5277, starting the handshake"),
            linha("10:03:00.000", "wifi", "W: link lost — reconnecting in 4000ms"),
            linha("10:03:04.000", "error", "E: RX watchdog: dash silent 4s → link lost"),
            linha("10:03:05.000", "error", "E: session fail: Dash stopped responding — connection lost"),
            linha("10:03:06.000", "ui", "the rider did something"),
        )
        linhas = ler_registro.ler([self.arquivo(texto)])
        niveis = [(x.etiqueta, x.nivel, x.texto.split(":")[0].split(" — ")[0]) for x in linhas if x.etiqueta in ("wifi", "error", "ui")]
        self.assertEqual([("wifi", "W", "link lost"), ("error", "E", "RX watchdog"), ("error", "E", "session fail"),
                          ("ui", "", "the rider did something")], niveis)
        tempo = "\n".join(ler_registro.linha_do_tempo(linhas))
        self.assertIn("Wi-Fi da moto caiu", tempo)
        self.assertIn("vigia: RX watchdog: dash silent 4s", tempo)

    def test_a_linha_dos_ajustes_em_uso_abre_a_linha_do_tempo_da_projecao(self):
        texto = projecao(
            linha("10:00:01.500", "settings", "settings in use: aaSlowPace=true detailedLog=false reduction=DEFAULT"),
            linha("10:01:00.000", "aa", "[AA] AaReceiver: connected to the head unit server on :5277, starting the handshake"),
        )
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertIn("settings in use: aaSlowPace=true detailedLog=false reduction=DEFAULT", tempo)

    def test_a_linha_dos_experimentos_de_conexao_ligados_tambem_entra_na_linha_do_tempo(self):
        texto = projecao(
            linha("10:00:01.500", "settings", "settings in use: experimentRtpPacing=true experimentWifiBind=false"),
            linha("10:00:01.600", "settings", "connection experiments ON: video pacing (RTP packets spaced out)"),
            linha("10:01:00.000", "aa", "[AA] AaReceiver: connected to the head unit server on :5277, starting the handshake"),
        )
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertIn("connection experiments ON: video pacing (RTP packets spaced out)", tempo)

    # ── quedas, buracos, temperatura e vídeo: o que o leitor já contou errado ─────────────────────────────────────

    def test_uma_queda_do_wifi_e_uma_so_mesmo_com_o_vigia_logo_depois(self):
        # Wi-Fi caiu e perdido e, 4 s depois, o vigia do painel: o leitor contava três "quedas seguidas".
        texto = projecao(
            linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms"),
            linha("10:01:00.003", "system", "bike network lost"),
            linha("10:01:03.900", "error", "E: RX watchdog: dash silent 4s → link lost"),
        )
        linhas = ler_registro.ler([self.arquivo(texto)])
        self.assertFalse(any("quedas seguidas" in x for x in ler_registro.problemas(linhas)))
        tempo = [x for x in ler_registro.linha_do_tempo(linhas) if "queda (" in x]
        self.assertEqual(1, len(tempo), tempo)
        self.assertIn("Wi-Fi da moto", tempo[0])
        self.assertIn("painel calado", tempo[0])
        self.assertTrue(tempo[0].startswith("10:01:00.000"), tempo[0])

    def test_duas_quedas_separadas_continuam_sendo_seguidas(self):
        texto = projecao(
            linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms"),
            linha("10:01:03.900", "error", "E: RX watchdog: dash silent 4s → link lost"),
            linha("10:01:40.000", "wifi", "W: link lost — reconnecting in 4000ms"),
        )
        seguidas = [x for x in self.problemas(texto) if "quedas seguidas" in x]
        self.assertEqual(1, len(seguidas), seguidas)
        self.assertIn("36 s depois da anterior", seguidas[0])  # contado do fim da primeira queda (10:01:03.9)

    def test_o_wifi_de_antes_da_queda_entra_na_linha_do_tempo(self):
        texto = projecao(
            linha("10:00:30.000", "link", "rssi=-56 dBm tx=72 rx=72 Mbps 2.4 GHz powersave=off screen=on | last 60 s, every 5 s: rssi -55 -56 | tx 72 72"),
            linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms"),
        )
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        # No `bike=` in the registro: the [link] look is the phone's own Wi-Fi, and it is said so.
        self.assertIn("Wi-Fi do celular antes da queda (o do painel não está no registro): rssi=-56 dBm tx=72 rx=72 Mbps 2.4 GHz", tempo)
        self.assertNotIn("Wi-Fi do painel antes da queda", tempo)

    # ── o painel desligando com a moto não é problema ─────────────────────────────────────────────────────────────

    FIRME = "rssi=-58 dBm tx=72 rx=72 Mbps 2.4 GHz powersave=on screen=on | last 60 s, every 5 s: rssi -50 -58 -58 -58 -58 | tx 72 72 72 72 72"

    def sumiu_de_uma_vez(self, firme):
        return projecao(
            linha("10:00:30.000", "link", firme),
            linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms"),
            linha("10:01:00.003", "system", "bike network lost"),
            linha("10:01:04.200", "error", "E: RX watchdog: dash silent 4s → link lost"),
        )

    def test_wifi_firme_que_some_de_uma_vez_e_o_painel_desligando_e_nao_um_problema(self):
        linhas = ler_registro.ler([self.arquivo(self.sumiu_de_uma_vez(self.FIRME))])
        self.assertEqual([], ler_registro.problemas(linhas))
        tempo = [x for x in ler_registro.linha_do_tempo(linhas) if "painel desligou" in x]
        self.assertEqual(1, len(tempo), tempo)
        self.assertTrue(tempo[0].startswith("10:01:00.000"), tempo[0])

    def test_o_vigia_nao_e_problema_em_nenhuma_queda_nem_depois_de_wifi_fraco_ou_que_oscilava(self):
        # Item 17a (08/11/2026): the RX watchdog was told in 4 of 16 drops, by how the Wi-Fi history looked; every drop is treated the same now (the drop is in the timeline).
        for historico in ("rssi -60 -72 -85 -88 | tx 72 40 12 6", "rssi -80 -70 -60 -62 | tx 6 24 52 58", "rssi -50 -58 -58 -58 -58 | tx 72 72 72 72 72"):
            firme = f"rssi=-88 dBm tx=6 rx=6 Mbps 2.4 GHz powersave=on screen=on | last 60 s, every 5 s: {historico}"
            linhas = ler_registro.ler([self.arquivo(self.sumiu_de_uma_vez(firme))])
            p = ler_registro.problemas(linhas)
            self.assertFalse(any("vigia" in x or "watchdog" in x for x in p), (historico, p))
            self.assertIn("vigia: RX watchdog", "\n".join(ler_registro.linha_do_tempo(linhas)))

    def test_sem_o_historico_do_sinal_no_registro_nao_se_afirma_que_o_painel_desligou(self):
        texto = projecao(linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms"),
                         linha("10:01:04.200", "error", "E: RX watchdog: dash silent 4s → link lost"))
        linhas = ler_registro.ler([self.arquivo(texto)])
        self.assertFalse(any("vigia" in x for x in ler_registro.problemas(linhas)))
        self.assertFalse(any("painel desligou" in x for x in ler_registro.linha_do_tempo(linhas)))

    def test_o_aviso_de_sem_09_06_55_que_so_cita_o_vigia_nao_e_queda(self):
        texto = projecao(linha("10:01:00.000", "dash", "W: no 09 06 55 after 15s, but the dash answers: the stream goes on (the RX watchdog watches it)"))
        linhas = ler_registro.ler([self.arquivo(texto)])
        self.assertFalse(any("queda" in x for x in ler_registro.linha_do_tempo(linhas)))

    def test_buraco_nas_linhas_de_5_s_so_com_o_painel_no_ar(self):
        # O painel caiu e voltou 3 minutos depois: sem vídeo no meio não é buraco.
        texto = projecao(
            linha("10:00:10.000", "stream", "video fps target=4 sent=3.9 (20frames/5.1s)"),
            linha("10:00:11.000", "error", "E: session fail: Dash stopped responding — connection lost"),
            linha("10:00:12.000", "stream", "video loop stop (0 frames held back before a keyframe)"),
            linha("10:03:10.000", "session", "→ STREAMING"),
            linha("10:03:15.000", "stream", "video fps target=4 sent=3.9 (20frames/5.1s)"),
        )
        self.assertFalse(any("buraco" in x for x in self.problemas(texto)), self.problemas(texto))

    def test_temperatura_com_carregador_ou_tela_apagada_nao_conta(self):
        texto = projecao(
            linha("10:00:20.000", "device", "device battery=80% charging=yes temp=31.0C thermal=none screen=on [start]"),
            linha("10:04:00.000", "device", "device battery=82% charging=yes temp=39.0C thermal=none screen=on [30 s]"),
            linha("10:06:00.000", "device", "device battery=82% charging=no temp=40.0C thermal=none screen=off [30 s]"),
        )
        self.assertFalse(any("temperatura" in x for x in self.problemas(texto)), self.problemas(texto))

    def test_temperatura_sem_carregador_e_com_a_tela_ligada_conta_e_diz_que_condicao(self):
        texto = projecao(
            linha("10:00:20.000", "device", "device battery=80% charging=no temp=31.0C thermal=none screen=on [start]"),
            linha("10:04:00.000", "device", "device battery=78% charging=no temp=40.0C thermal=none screen=on [30 s]"),
        )
        achados = [x for x in self.problemas(texto) if "temperatura" in x]
        self.assertEqual(1, len(achados), achados)
        self.assertIn("31.0", achados[0])
        self.assertIn("40.0", achados[0])
        self.assertIn("sem carregador, tela ligada", achados[0])

    def test_o_resumo_do_video_diz_a_taxa_o_quadro_o_quadro_chave_a_reducao_e_as_trocas_de_perfil(self):
        texto = projecao(
            linha("10:00:05.000", "stream", "video loop start: 4 fps (ceiling 4), keyframe 1s fixed, 400 kbps (400 chosen) CBR, clock 0% slow, reduction SOFT_SHARP"),
            linha("10:00:10.000", "stream", "video fps target=4 sent=3.9 (20frames/5.1s) composed=3.9 kbps=300 set=400k frame=9000B source=61 drawn=20 repeated=0 compose=3.7ms encode=13.9ms late=0 full=true thermal=OK kf=1s idr=5(max 14000B/12pkt) sync=0 auto=4fps [at ceiling]"),
            linha("10:00:15.000", "stream", "video fps target=2 sent=2.0 (10frames/5.1s) composed=2.0 kbps=100 set=400k frame=6000B source=0 drawn=0 repeated=10 compose=3.0ms encode=12.9ms late=0 full=false thermal=OK kf=1s idr=5(max 9000B/9pkt) sync=0 auto=4fps [idle]"),
            linha("10:00:16.000", "stream", "nav profile: FULL -> REDUCED (data), pace 2 fps, encoder 4 fps"),
            linha("10:00:17.000", "stream", "nav profile: REDUCED -> FULL (data), pace 4 fps, encoder 4 fps"),
            linha("10:00:18.000", "stream", "profile 2 measured 310 kbps, over half of 400: the bitrate is set to half live, no new encoder"),
            linha("10:00:19.000", "dash", "notice 09 06 55 #1, 8,1 s into the stream, STREAMING: answered with 06 11"),
            linha("10:00:20.000", "stream", "video loop stop (0 frames held back before a keyframe, 0 keyframes withheld for lack of SPS/PPS, 31 packets discarded)"),
        )
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        linha_video = next(x for x in tempo.splitlines() if "vídeo:" in x)
        for parte in ("kbps médio 300 em movimento (pior janela 300)", "quadro médio 7500 B", "quadros-chave 10 (o maior 14000 B em 12 pacotes)",
                      "redução SOFT_SHARP", "2 trocas de perfil", "perfil 2 limitou o bitrate", "31 pacotes descartados",
                      "aviso 09 06 55 #1 aos 8,1 s"):
            self.assertIn(parte, linha_video)

    def test_o_resumo_do_video_so_diz_o_que_o_registro_tem(self):
        texto = projecao(linha("10:00:10.000", "stream", "video fps target=16 sent=15.9 (80frames/5.0s) composed=16.0 dash=15.0 kbps=900 set=1024k frame=7500B full=true"))
        linha_video = next(x for x in ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])) if "vídeo:" in x)
        self.assertIn("kbps médio 900", linha_video)
        for ausente in ("quadros-chave", "redução", "trocas de perfil", "descartados", "aviso 09 06 55"):
            self.assertNotIn(ausente, linha_video)

    def test_sem_nenhuma_linha_de_video_nao_ha_resumo(self):
        self.assertFalse(any("vídeo:" in x for x in ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(projecao())]))))

    def test_vigias(self):
        texto = projecao(linha("10:00:30.000", "error", "RX watchdog: dash silent 6s → link lost"),
                         linha("10:01:00.000", "aa", "[AA] Decoder restart requested: stalled 3000ms"))
        p = self.problemas(texto)
        self.assertFalse(any("RX watchdog" in x for x in p), p)  # the echo of a drop: told in the timeline, never as a problem
        self.assertTrue(any("vigia" in x and "Decoder restart" in x for x in p), p)

    def test_o_wifi_no_instante_do_vigia_entra_na_linha_do_tempo_inteiro(self):
        falha = ("link at failure: rssi=-88 dBm tx=6 rx=6 Mbps 5 GHz powersave=off screen=on | "
                 "last 60 s, every 5 s: rssi -58 -60 -61 -70 -78 -85 -88 | tx 72 72 72 58 24 6 6")
        texto = projecao(linha("10:00:29.900", "link", falha),
                         linha("10:00:30.000", "error", "RX watchdog: dash silent 6s → link lost"))
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertIn("Wi-Fi na falha: rssi=-88 dBm", tempo)
        self.assertIn("rssi -58 -60 -61 -70 -78 -85 -88 | tx 72 72 72 58 24 6 6", tempo)

    def test_buraco_nas_linhas_de_5_s(self):
        texto = projecao(linha("10:00:10.000", "stream", "video fps target=16 sent=15.9"),
                         linha("10:00:15.000", "stream", "video fps target=16 sent=15.9"),
                         linha("10:00:22.500", "stream", "video fps target=16 sent=15.9"))
        p = self.problemas(texto)
        self.assertTrue(any("buraco" in x and "7.5 s" in x for x in p), p)

    def test_o_resumo_com_painel_sem_imagem_por_mais_de_10_s_e_listado(self):
        resumo = ("resumo duration=50m00s streaming=40m00s drops dash=2 aa=0 retries dash=0 watchdogs=2 (dash-rx=2) frames=9000 "
                  "fps avg=3.7 min=0.0 gap max=0.3s dark=2/3m02s (max 1m08s) thermal max=none battery 80→60% (-20) marks=0")
        texto = BOA.replace("resumo duration=9m00s streaming=8m56s drops dash=0 aa=0 watchdogs=0 frames=8500 fps avg=15.9 min=15.0 gap max=0.3s thermal max=none battery 80→76% (-4) marks=1", resumo)
        self.assertTrue(any("painel sem imagem — 2 interrupções, 3m02s no total, a maior de 1m08s" in p for p in self.problemas(texto)))

    def test_o_resumo_com_interrupcao_curta_ou_sem_dark_nao_acusa(self):
        curto = ("resumo duration=50m00s streaming=40m00s drops dash=1 aa=0 retries dash=0 watchdogs=1 (dash-rx=1) frames=9000 "
                 "fps avg=3.7 min=0.0 gap max=0.3s dark=1/8s (max 8s) thermal max=none battery 80→60% (-20) marks=0")
        texto = BOA.replace("resumo duration=9m00s streaming=8m56s drops dash=0 aa=0 watchdogs=0 frames=8500 fps avg=15.9 min=15.0 gap max=0.3s thermal max=none battery 80→76% (-4) marks=1", curto)
        self.assertFalse(any("painel sem imagem" in p for p in self.problemas(texto)))
        self.assertFalse(any("painel sem imagem" in p for p in self.problemas(BOA)))

    def test_buraco_no_resumo(self):
        texto = BOA.replace("gap max=0.3s", "gap max=2.4s")
        self.assertTrue(any("buraco" in x and "2.4 s" in x for x in self.problemas(texto)))

    def test_quedas_seguidas(self):
        texto = projecao(linha("10:01:00.000", "wifi", "link lost — reconnecting in 2000ms"),
                         linha("10:01:40.000", "error", "RX watchdog: dash silent 6s → link lost"))
        self.assertTrue(any("quedas seguidas" in x for x in self.problemas(texto)))

    def test_quedas_longe_uma_da_outra_nao_sao_seguidas(self):
        texto = projecao(linha("10:01:00.000", "wifi", "link lost — reconnecting in 2000ms"),
                         linha("10:05:00.000", "wifi", "link lost — reconnecting in 2000ms"))
        self.assertFalse(any("quedas seguidas" in x for x in self.problemas(texto)))

    def test_o_fim_da_sessao_que_o_desligamento_provoca_nao_e_queda(self):
        texto = projecao(
            linha("10:08:50.000", "wifi", "link lost — reconnecting in 2000ms"),
            linha("10:08:59.000", "aa", "[AA] AaReceiver: session ended (clean=false, userExit=false)"))
        linhas = ler_registro.ler([self.arquivo(texto)])
        self.assertNotIn("Android Auto caiu", "\n".join(ler_registro.linha_do_tempo(linhas)))
        self.assertFalse(any("quedas seguidas" in x for x in ler_registro.problemas(linhas)))

    def test_o_fim_que_o_reau_pede_com_o_bye_nao_e_queda(self):
        # Uma sessão nova do Android Auto para os ajustes: versões antigas do app registravam o fim como clean=false.
        texto = projecao(
            linha("10:03:00.000", "aa", "[AA] AapTransport stopping and sending byebye"),
            linha("10:03:00.150", "aa", "[AA] AaReceiver: session ended (clean=false, userExit=false)"),
            linha("10:03:30.000", "wifi", "link lost — reconnecting in 2000ms"))
        linhas = ler_registro.ler([self.arquivo(texto)])
        self.assertNotIn("Android Auto caiu", "\n".join(ler_registro.linha_do_tempo(linhas)))
        self.assertFalse(any("quedas seguidas" in x for x in ler_registro.problemas(linhas)))

    def test_o_fim_sem_bye_ainda_e_queda(self):
        texto = projecao(
            linha("10:02:00.000", "aa", "[AA] AapTransport stopping and sending byebye"),
            linha("10:03:00.150", "aa", "[AA] AaReceiver: session ended (clean=false, userExit=false)"))
        linhas = ler_registro.ler([self.arquivo(texto)])
        self.assertIn("Android Auto caiu", "\n".join(ler_registro.linha_do_tempo(linhas)))

    def test_temperatura_subindo(self):
        texto = projecao(
            linha("10:00:20.000", "device", "device battery=80% charging=no temp=31.0C thermal=none [start]"),
            linha("10:04:00.000", "device", "device battery=78% charging=no temp=38.5C thermal=moderate [change: thermal]"))
        p = self.problemas(texto)
        self.assertTrue(any("temperatura subindo" in x and "moderate" in x for x in p), p)
        # 31 → 38.5 °C is a rise of 7.5: warm, not alarming by itself (the absolute 38 °C of before fired on 38 → 38).
        self.assertFalse(any("bateria" in x for x in p), p)

    def test_temperatura_morna_que_subiu_pouco_nao_acusa(self):
        texto = projecao(
            linha("10:00:20.000", "device", "device battery=80% charging=no temp=31.0C thermal=none screen=on [start]"),
            linha("10:04:00.000", "device", "device battery=78% charging=no temp=37.0C thermal=none screen=on [30 s]"))
        self.assertFalse(any("temperatura" in x for x in self.problemas(texto)), self.problemas(texto))

    # ── o Wi-Fi do painel que reinicia, a autenticação, o pedido que expira e a imagem que some (20/10/2026) ─────────────────

    def queda_que_volta(self, depois_s, antes="bike=-55dBm/2447MHz/58Mbps", volta="bike=-50dBm/2427MHz/72Mbps"):
        h = lambda s: f"10:{(1 + int(s) // 60):02d}:{int(s) % 60:02d}.000"
        return projecao(
            linha("10:00:30.000", "link", self.FIRME),
            linha("10:00:50.000", "device", f"device battery=80% charging=no temp=31.0C thermal=none screen=on {antes} [30 s]"),
            linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms (after 59 s on 2447 MHz; rssi -55)"),
            linha("10:01:00.003", "system", "bike network lost"),
            linha(h(depois_s), "wifi", "connected 'RE_****30' in 900ms"),
            linha(h(depois_s), "system", "bike network available"),
            linha(h(depois_s + 20), "device", f"device battery=80% charging=no temp=31.0C thermal=none screen=on {volta} [30 s]"),
        )

    def test_wifi_do_painel_que_volta_em_menos_de_2_min_e_reinicio_e_nao_moto_desligada(self):
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(self.queda_que_volta(22))])))
        self.assertIn("Wi-Fi do painel reiniciou (voltou em 22 s, 2447 → 2427 MHz)", tempo)
        self.assertIn("Wi-Fi do painel reiniciou", tempo)
        self.assertNotIn("moto desligada", tempo)

    def test_o_canal_de_antes_e_de_depois_vem_do_registro(self):
        texto = self.queda_que_volta(30)
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertIn("2447 → 2427 MHz", tempo)
        igual = self.queda_que_volta(30, volta="bike=-50dBm/2447MHz/72Mbps")
        self.assertIn("mesmo canal 2447 MHz", "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(igual)]))))

    def test_a_linha_de_canal_do_app_que_vem_antes_do_connected_e_lida(self):
        texto = projecao(
            linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms (after 59 s on 2447 MHz; rssi -55)"),
            linha("10:01:00.003", "system", "bike network lost"),
            linha("10:01:22.000", "wifi", "the dash's Wi-Fi is back on another channel (2447 → 2427 MHz): its access point restarted"),
            linha("10:01:22.010", "wifi", "connected 'RE_****30' in 900ms"),
            linha("10:01:22.011", "system", "bike network available"),
        )
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertIn("Wi-Fi do painel reiniciou (voltou em 22 s, 2447 → 2427 MHz)", tempo)

    def test_volta_no_mesmo_canal_ou_sem_canal_so_diz_que_voltou(self):
        igual = self.queda_que_volta(30, volta="bike=-50dBm/2447MHz/72Mbps")
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(igual)])))
        self.assertIn("Wi-Fi do painel caiu e voltou (voltou em 30 s, mesmo canal 2447 MHz)", tempo)
        self.assertNotIn("reiniciou", tempo)

    def test_o_vigia_de_silencio_com_o_wifi_de_pe_nao_e_queda_de_wifi_que_voltou(self):
        texto = projecao(
            linha("10:00:30.000", "link", self.FIRME),
            linha("10:01:00.000", "error", "E: RX watchdog: dash silent 4s → link lost"),
            linha("10:01:20.000", "wifi", "connected 'RE_****30' in 900ms"),
        )
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertNotIn("reiniciou", tempo)
        self.assertNotIn("caiu e voltou", tempo)

    def test_wifi_do_painel_que_nao_volta_em_5_min_continua_moto_desligada(self):
        texto = projecao(
            linha("10:00:30.000", "link", self.FIRME),
            linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms"),
            linha("10:01:00.003", "system", "bike network lost"),
            linha("10:07:30.000", "wifi", "connected 'RE_****30' in 900ms"))
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertIn("painel desligou (moto desligada?)", tempo)
        self.assertNotIn("reiniciou", tempo)

    def test_painel_que_volta_em_ate_5_min_nao_e_moto_desligada_mesmo_em_outro_canal(self):
        # 01/11/2026: the dash came back after 3 min 54 s (2422 → 2417 MHz); with the Wi-Fi steady until it went it was read as the bike switched off.
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(self.queda_que_volta(235))])))
        self.assertIn("Wi-Fi do painel reiniciou (voltou em 235 s, 2447 → 2427 MHz)", tempo)
        self.assertNotIn("moto desligada", tempo)
        limite = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(self.queda_que_volta(ler_registro.VOLTA_S))])))
        self.assertNotIn("moto desligada", limite)
        self.assertEqual(300.0, ler_registro.VOLTA_S)

    def test_o_painel_que_volta_com_o_vigia_logo_depois_nao_vira_problema_nem_moto_desligada(self):
        texto = self.queda_que_volta(235).replace(
            linha("10:01:00.003", "system", "bike network lost"),
            linha("10:01:00.003", "system", "bike network lost") + linha("10:01:04.000", "error", "E: RX watchdog: dash silent 4s → link lost"))
        self.assertFalse(any("vigia" in x for x in self.problemas(texto)), self.problemas(texto))
        self.assertIn("Wi-Fi do painel reiniciou", "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)]))))

    def test_a_volta_pelo_wifi_ja_conectado_tambem_e_volta(self):
        texto = projecao(
            linha("10:00:30.000", "link", self.FIRME),
            linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms (after 59 s on 2422 MHz; rssi -55)"),
            linha("10:01:00.003", "system", "bike network lost"),
            linha("10:02:33.000", "wifi", "using already-connected WiFi 'RE_****30'"),
            linha("10:02:33.100", "wifi", "the dash's Wi-Fi is back on another channel (2422 → 2467 MHz): its access point restarted"))
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertIn("Wi-Fi do painel reiniciou (voltou em 93 s, 2422 → 2467 MHz)", tempo)

    # ── EPERM repetido na mesma rede (31/10/2026) ──────────────────────────────────────────────────────────────────────────

    EPERM = "E: session fail: SocketException: Binding socket to network {} failed: EPERM (Operation not permitted) — network blocked by Android"

    def test_eperm_repetido_na_mesma_rede_e_defeito(self):
        texto = projecao(*[linha(f"10:0{m}:00.000", "error", self.EPERM.format(454)) for m in (1, 2, 3)])
        p = [x for x in self.problemas(texto) if "EPERM" in x]
        self.assertEqual(1, len(p), p)
        self.assertIn("network=454", p[0])
        self.assertIn("3 vezes", p[0])
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertIn("EPERM ao ligar o socket à rede 454", tempo)

    def test_eperm_em_redes_diferentes_uma_vez_cada_nao_e_repeticao(self):
        texto = projecao(linha("10:01:00.000", "error", self.EPERM.format(454)), linha("10:05:00.000", "error", self.EPERM.format(455)))
        self.assertEqual([], [x for x in self.problemas(texto) if "EPERM" in x])

    def test_a_rede_descartada_pelo_app_entra_na_linha_do_tempo_e_tres_seguidas_sao_problema(self):
        descarta = "W: binding the socket to network {} was refused (EPERM, #{} in a row): the network is dropped and {}"
        dois = projecao(
            linha("10:01:00.000", "wifi", descarta.format(454, 1, "asked for again")),
            linha("10:01:05.000", "wifi", descarta.format(455, 2, "not asked for again for 15 s")))
        self.assertEqual([], [x for x in self.problemas(dois) if "EPERM" in x])
        self.assertIn("rede 454 recusada pelo Android (EPERM, 1ª seguida)", "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(dois)]))))
        tres = projecao(
            linha("10:01:00.000", "wifi", descarta.format(454, 1, "asked for again")),
            linha("10:01:05.000", "wifi", descarta.format(455, 2, "not asked for again for 15 s")),
            linha("10:01:30.000", "wifi", descarta.format(456, 3, "not asked for again for 30 s")))
        p = [x for x in self.problemas(tres) if "EPERM" in x]
        self.assertEqual(1, len(p), p)
        self.assertIn("3 redes seguidas", p[0])

    # ── EPERM em rede unida pelo celular e a espera de 30 s (06/11/2026 17:47:21) ──────────────────────────────────────────

    DESCARTA_CELULAR = ("W: binding the socket to network 274 was refused (EPERM, #1 in a row, joined by the phone itself (a suggestion or a saved network), "
                        "not the answer to REau's request #39; not-restricted=false, validated=false): the network is dropped and asked for again")
    UNIDA = "using already-connected WiFi 'RE_****30' (network 297, joined by the phone itself (a suggestion or a saved network), not the answer to REau's request #40; not-restricted=false, validated=false)"
    ESPERA_30 = "projection: the dash is tried again in 30s"

    def test_o_eperm_de_06_11_com_a_espera_de_30_s_e_defeito(self):
        texto = projecao(
            linha("17:47:21.261", "wifi", self.DESCARTA_CELULAR),
            linha("17:47:21.276", "wifi", self.UNIDA),
            linha("17:47:21.282", "projection", self.ESPERA_30))
        p = [x for x in self.problemas(texto) if "EPERM" in x]
        self.assertEqual(1, len(p), p)
        self.assertIn("só foi tentado 30 s depois", p[0])

    def test_com_o_corte_da_espera_dois_segundos_depois_nao_e_defeito_e_a_linha_do_tempo_diz_o_dono(self):
        texto = projecao(
            linha("17:47:21.261", "wifi", self.DESCARTA_CELULAR),
            linha("17:47:21.276", "wifi", self.UNIDA),
            linha("17:47:21.282", "projection", self.ESPERA_30),
            linha("17:47:23.280", "wifi", "network 297 took the place of the refused one 2000 ms ago and is still up: the projection is told, it tries the dash now instead of waiting"),
            linha("17:47:23.285", "projection", "projection: the bike's Wi-Fi is back or the dash was seen: the dash is tried now, not when the wait ends"))
        self.assertEqual([], [x for x in self.problemas(texto) if "EPERM" in x])
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertIn("rede 274 recusada pelo Android (EPERM, 1ª seguida) (rede que o próprio celular uniu, não pedido do REau)", tempo)
        self.assertIn("outra rede ficou no lugar da recusada: o painel é tentado já, sem esperar", tempo)

    def test_eperm_sem_outra_rede_unida_ou_com_espera_curta_nao_e_este_defeito(self):
        sem_rede = projecao(linha("17:47:21.261", "wifi", self.DESCARTA_CELULAR), linha("17:47:21.282", "projection", self.ESPERA_30))
        self.assertEqual([], [x for x in self.problemas(sem_rede) if "EPERM" in x])
        curta = projecao(
            linha("17:47:21.261", "wifi", self.DESCARTA_CELULAR), linha("17:47:21.276", "wifi", self.UNIDA),
            linha("17:47:21.282", "projection", "projection: the dash is tried again in 2s"))
        self.assertEqual([], [x for x in self.problemas(curta) if "EPERM" in x])

    # ── o painel travado: visível na varredura e recusando (01/11/2026) ──────────────────────────────────────────────────

    TRAVADO = ("W: dash visible but refusing for 35 s (3 tries, -58 dBm) — dash Wi-Fi likely hung; rider told to cycle ignition")

    def test_a_linha_do_app_de_painel_travado_e_problema_e_entra_na_linha_do_tempo(self):
        texto = projecao(linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms"), linha("10:01:35.000", "wifi", self.TRAVADO))
        p = [x for x in self.problemas(texto) if "painel travado" in x]
        self.assertEqual(1, len(p), p)
        self.assertIn("35 s", p[0])
        self.assertIn("3 tentativas", p[0])
        self.assertIn("ignição", p[0])
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertIn("painel visível mas recusando", tempo)

    def recusas(self, *sinais, quando=("10:01:09", "10:01:45", "10:02:30")):
        """A queda de 10:01:00 e, a cada hora de `quando`, um pedido que expirou com o painel na varredura no sinal dado."""
        linhas = [linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms"), linha("10:01:00.003", "system", "bike network lost")]
        for hora, (dbm, visto) in zip(quando, sinais):
            linhas.append(linha(hora + ".000", "wifi", "W: unavailable after 5916ms (mid-session, #1 of this absence) — retrying in 4000ms; "
                                f"scan of 13 networks, the dash: 'RE_****30' {dbm} dBm 2422 MHz seen {visto} s ago, approvedBefore=true"))
        return projecao(*linhas)

    def test_registro_antigo_com_o_painel_na_varredura_a_sinal_bom_por_mais_de_30_s_e_painel_travado(self):
        p = [x for x in self.problemas(self.recusas((-58, 10), (-54, 20))) if "painel travado" in x]
        self.assertEqual(1, len(p), p)
        self.assertIn("registro antigo", p[0])
        self.assertIn("-54 dBm", p[0])

    def test_o_sinal_fraco_ou_a_recusa_curta_nao_e_painel_travado(self):
        self.assertEqual([], [x for x in self.problemas(self.recusas((-82, 5), (-85, 5))) if "painel travado" in x])
        curta = self.recusas((-58, 5), (-58, 5), quando=("10:01:05", "10:01:20"))
        self.assertEqual([], [x for x in self.problemas(curta) if "painel travado" in x])

    def test_a_varredura_sem_o_painel_e_moto_desligada_e_nao_painel_travado(self):
        texto = projecao(
            linha("10:00:30.000", "link", self.FIRME),
            linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms"),
            linha("10:01:00.003", "system", "bike network lost"),
            linha("10:01:40.000", "wifi", "W: unavailable after 5916ms (mid-session, #1 of this absence) — retrying in 4000ms; scan of 13 networks, the dash not among them"),
            linha("10:02:40.000", "wifi", "W: unavailable after 5916ms (mid-session, #5 of this absence) — retrying in 4000ms; scan of 13 networks, the dash not among them"))
        self.assertEqual([], [x for x in self.problemas(texto) if "painel travado" in x])

    def test_a_linha_do_app_dispensa_a_conclusao_pelo_registro_antigo(self):
        texto = self.recusas((-58, 10), (-54, 20))
        com_linha = texto.replace("10:09:00.000  +  1000ms  ---- projeção desligada", linha("10:02:35.000", "wifi", self.TRAVADO) + "10:09:00.000  +  1000ms  ---- projeção desligada")
        p = [x for x in self.problemas(com_linha) if "painel travado" in x]
        self.assertEqual(1, len(p), p)
        self.assertNotIn("registro antigo", p[0])

    # ── o primeiro quadro numa projeção curta ──────────────────────────────────────────────────────────────────────────────

    def transmitindo_por(self, segundos):
        return (CABECALHO + "10:00:01.000  +  1000ms  ---- projeção ligada: projection ----\n"
                + linha("10:00:03.000", "session", "AUTHENTICATING → STREAMING")
                + f"10:00:{3 + segundos:02d}.000  +  1000ms  ---- projeção desligada: projection ----\n")

    def test_projecao_com_menos_de_10_s_transmitindo_nao_acusa_o_primeiro_quadro(self):
        # 31/10/2026, 10:11:29: switched off at 8 s, before the dash's own 10 s mark.
        self.assertFalse(any("primeiro quadro" in x for x in self.problemas(self.transmitindo_por(8))), self.problemas(self.transmitindo_por(8)))

    def test_projecao_de_10_s_ou_mais_sem_aviso_ainda_acusa_o_primeiro_quadro(self):
        self.assertTrue(any("primeiro quadro" in x for x in self.problemas(self.transmitindo_por(10))))
        self.assertTrue(any("primeiro quadro" in x for x in self.problemas(self.transmitindo_por(30))))

    def test_o_resumo_do_que_o_app_enviava_antes_do_silencio_entra_na_linha_do_tempo(self):
        texto = projecao(linha("10:01:00.000", "wifi", "send tail before the Wi-Fi was lost (on this network for 59 s): the last 10 s of RTP (up to the last packet, 0 s ago) 8.0 pkt/s 9.0 kB/s"))
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertIn("o que o app enviava antes do silêncio: the Wi-Fi was lost (on this network for 59 s)", tempo)

    def test_autenticacao_que_expira_e_pedido_de_rede_com_o_painel_na_varredura_entram_nos_problemas(self):
        texto = projecao(
            linha("10:01:00.000", "error", "E: session fail: Auth timed out — the dash answered but never accepted (no 07 01 01). Check SSID matches 'RE_****30'."),
            linha("10:02:00.000", "wifi", "W: unavailable after 30079ms (initial connect) — giving up, seenInScan=true quick=0 joined=false"))
        p = self.problemas(texto)
        self.assertTrue(any("autenticação do painel falhou — Auth timed out" in x for x in p), p)
        self.assertTrue(any("pedido de rede expirou com o painel na varredura" in x for x in p), p)

    def test_imagem_que_sumiu_por_mais_de_10_s_sem_o_dark_e_calculada_do_video_parar_ao_voltar(self):
        texto = projecao(
            linha("10:01:00.000", "session", "→ STREAMING"),
            linha("10:02:00.000", "video", "video loop stop"),
            linha("10:03:10.000", "session", "→ STREAMING"),
            linha("10:03:20.000", "video", "video loop stop"),
            linha("10:03:25.000", "session", "→ STREAMING"))
        p = [x for x in self.problemas(texto) if "painel sem imagem" in x]
        self.assertEqual(1, len(p), p)
        self.assertIn("70 s", p[0])

    def test_o_aviso_de_10_s_em_qualquer_estado_nao_e_falta_de_quadro_confirmado(self):
        texto = BOA.replace("dash DECODED first IDR (09 06 55) — video accepted ✓",
                            "dash sent 09 06 55 while AUTHENTICATING, before any video: not the first frame")
        self.assertFalse(any("sem confirmação do primeiro quadro" in p for p in self.problemas(texto)))

    # ── o Android Auto que fecha junto com a tela (12:01:07 e 12:13:36 de 20/10/2026, trechos reais) ───────────────────────

    def fechou_com_a_tela(self):
        return projecao(
            linha("10:01:07.075", "aa", "[AA] AapRead: the link ended after 34 s; the last message came 45 ms before; phone: charger out, screen on, doze off"),
            linha("10:01:07.079", "aa", "[AA] AaReceiver: session ended (clean=false, userExit=false)"),
            linha("10:01:07.605", "system", "screen off"),
            linha("10:01:08.238", "aa", "[AA] AaReceiver: connected to the head unit server on :5277, starting the handshake"),
            linha("10:01:08.754", "aa", "[AA] Handshake: Status OK sent (8). Handshake successful."),
            linha("10:01:08.859", "system", "screen on"),
            linha("10:01:09.907", "aa", "[AA] H.264 SPS parsed: 800x480"),
        )

    def test_android_auto_que_fecha_junto_com_a_tela_nao_e_queda_e_diz_quanto_levou_para_voltar(self):
        linhas = ler_registro.ler([self.arquivo(self.fechou_com_a_tela())])
        tempo = "\n".join(ler_registro.linha_do_tempo(linhas))
        self.assertIn("Android Auto fechou junto com a tela (reconectou em 1,2 s; imagem nova em 2,8 s)", tempo)
        self.assertNotIn("queda (Android Auto caiu)", tempo)
        self.assertEqual([], [p for p in ler_registro.problemas(linhas) if "quedas seguidas" in p or "queda" in p])

    def test_o_fim_com_a_mudanca_de_tela_so_na_olhada_do_celular_tambem_conta(self):
        texto = projecao(
            linha("10:03:36.754", "aa", "[AA] AaReceiver: session ended (clean=false, userExit=false)"),
            linha("10:03:39.064", "device", "device battery=80% charging=no temp=31.0C thermal=none screen=on interactive=yes [change: screen]"),
        )
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertIn("Android Auto fechou junto com a tela", tempo)

    def test_android_auto_que_cai_longe_de_qualquer_evento_de_tela_continua_queda(self):
        texto = projecao(
            linha("10:01:07.079", "aa", "[AA] AaReceiver: session ended (clean=false, userExit=false)"),
            linha("10:01:12.000", "system", "screen off"),
        )
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertIn("queda (Android Auto caiu)", tempo)
        self.assertNotIn("junto com a tela", tempo)

    def test_a_idade_do_painel_e_o_custo_da_chave_entram_na_linha_do_tempo_e_a_chave_tardia_nos_problemas(self):
        texto = projecao(
            linha("10:01:00.000", "auth", "dash heartbeat age 12 s at the first packet"),
            linha("10:01:15.000", "auth", "key arrived 14.3 s after the first greeting, after 6 resends"),
        )
        linhas = ler_registro.ler([self.arquivo(texto)])
        tempo = "\n".join(ler_registro.linha_do_tempo(linhas))
        self.assertIn("auth: idade do painel: dash heartbeat age 12 s at the first packet", tempo)
        self.assertIn("auth: chave: key arrived 14.3 s after the first greeting, after 6 resends", tempo)
        self.assertTrue(any("só veio depois de 6 reenvios da saudação (14.3 s)" in p for p in ler_registro.problemas(linhas)))

    def test_a_chave_que_veio_logo_nao_e_problema(self):
        texto = projecao(linha("10:01:00.000", "auth", "key arrived 0.2 s after the first greeting, after 0 resends"),
                         linha("10:02:00.000", "auth", "key arrived 3.1 s after the first greeting, after 1 resend"))
        self.assertFalse(any("chave do painel" in p for p in self.problemas(texto)))

    def test_travamento(self):
        texto = projecao(linha("10:02:00.000", "error", "the app crashed: IllegalStateException at X.kt:10 (crash-1.txt)"))
        self.assertTrue(any("travou" in x for x in self.problemas(texto)))
        self.assertIn("o app travou", "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)]))))

    # ── the reader must not say "Android Auto conectado" for a handshake Android Auto never answered ──

    def test_conectado_so_depois_da_negociacao_respondida(self):
        texto = projecao(
            linha("10:00:02.000", "aa", "[AA] AaReceiver: connected to the head unit server on :5277, starting the handshake"),
            linha("10:00:12.000", "aa", "[AA] W: AaReceiver: the handshake failed"),
        )
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertNotIn("Android Auto conectado", tempo)
        self.assertIn("Android Auto não respondeu à negociação", tempo)

    def test_tres_negociacoes_seguidas_sem_resposta(self):
        falhas = [linha(f"10:0{i}:00.000", "aa", "[AA] W: AaReceiver: the handshake failed") for i in range(2, 5)]
        p = self.problemas(projecao(*falhas))
        self.assertTrue(any("aceita e não responde" in x and "3" in x for x in p), p)

    def test_duas_sem_resposta_e_uma_boa_nao_acusam(self):
        texto = projecao(
            linha("10:02:00.000", "aa", "[AA] W: AaReceiver: the handshake failed"),
            linha("10:03:00.000", "aa", "[AA] W: AaReceiver: the handshake failed"),
            linha("10:04:00.000", "aa", "[AA] Handshake: Status OK sent (8). Handshake successful."),
            linha("10:05:00.000", "aa", "[AA] W: AaReceiver: the handshake failed"),
        )
        self.assertFalse(any("aceita e não responde" in x for x in self.problemas(texto)))

    def test_android_auto_calado(self):
        texto = projecao(linha("10:02:00.000", "aa", "[AA] W: AapRead: WiFi read timeout (15s) - connection lost."))
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertIn("Android Auto calado (15 s sem mandar nada)", tempo)
        self.assertTrue(any("calado" in x for x in self.problemas(texto)))

    def test_uma_pasta_inteira_em_ordem(self):
        d = Path(tempfile.mkdtemp())
        (d / "ride-20261004-100000.log").write_text(BOA, encoding="utf-8")
        (d / "logcat-20261004-100000.txt").write_text("não é registro", encoding="utf-8")
        linhas = ler_registro.ler([d])
        self.assertTrue(linhas)
        self.assertTrue(all(x.arquivo.startswith("ride-") for x in linhas))

    # ── with the detailed log on, ~98% of the lines are [detail] and nothing here reads them ──

    def test_as_linhas_detail_ficam_de_fora_e_as_outras_todas_em_ordem(self):
        detalhe = [linha(f"10:00:0{i}.500", "detail", f"aa rx ch={i} type=7 flags=0x0F size=6") for i in range(2, 6)]
        texto = projecao(
            detalhe[0],
            linha("10:00:03.000", "wifi", "connected 'RE_****34' in 900ms"),
            detalhe[1], detalhe[2],
            linha("10:00:04.000", "session", "AUTHENTICATING → STREAMING"),
            detalhe[3],
            linha("10:05:00.000", "mark", "MARCA DO PILOTO 1"),
        )
        linhas = ler_registro.ler([self.arquivo(texto)])
        self.assertEqual([
            ("", "==== process start: app — Google Pixel, Android 16 (API 36) ===="),
            ("", "---- projeção ligada: projection ----"),
            ("wifi", "connected 'RE_****34' in 900ms"),
            ("session", "AUTHENTICATING → STREAMING"),
            ("mark", "MARCA DO PILOTO 1"),
            ("", "---- projeção desligada: projection ----"),
        ], [(x.etiqueta, x.texto) for x in linhas])
        self.assertFalse(any(x.etiqueta == "detail" for x in linhas))

    # ── how long the Android Auto read thread waits inside the decoder ──

    def test_a_leitura_presa_no_decodificador_mostra_a_pior_janela(self):
        def stats(hora, total, maior):
            return linha(hora, "aa", f"[AA] aa-stats 5.0s: msgs=80 (ch2=75) nav=0 units=75 bytes=50KB key=1 pieces=0 "
                                     f"assembled=0 lost=0 focus=0 sensor=0 decoded=74 decode=6/9ms "
                                     f"readHeld={total}/{maior}ms | total units=75 key=1 assembled=0 lost=0 decoded=74")
        texto = projecao(stats("10:00:10.000", "12.0", "3.1"), stats("10:00:15.000", "140.5", "80.2"),
                         stats("10:00:20.000", "30.0", "95.0"))
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))
        self.assertIn("leitura do Android Auto presa no decodificador: pior janela 140.5 ms em 5 s (10:00:15.000), "
                      "pior vez 95.0 ms (10:00:20.000)", tempo)

    def test_sem_readheld_no_registro_nao_diz_nada_da_leitura(self):
        tempo = "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(BOA)])))
        self.assertNotIn("presa no decodificador", tempo)

    def test_um_registro_de_verdade_do_emulador(self):
        amostra = AQUI / "amostra_emulador.txt"
        linhas = ler_registro.ler([amostra])
        tempo = "\n".join(ler_registro.linha_do_tempo(linhas))
        self.assertIn("projeção ligada", tempo)
        self.assertIn("transmitindo", tempo)
        self.assertIn("primeiro quadro confirmado", tempo)
        self.assertIn("resumo", tempo)
        ler_registro.problemas(linhas)  # não quebra num registro real


# Pieces of a real log (19/10/2026, serviço de acessibilidade ligado, Android Auto 17.8): the walk that turns the server on, from "projeção ligada" on.
SERVIDOR_1944 = """19:44:13.606  ---- projeção ligada: projection ----
19:44:13.683  +    76ms  [aa-server] AaServerStarter: start (the port is closed) on Android Auto 17.8.663874-release; texts from Android Auto: 7 of 7, the table for the rest
19:44:13.683  +    77ms  [aa-server] AaServerStarter: start walk begins (last Stop touched never in this process, last projection end never in this process)
19:44:13.754  +   148ms  [aa-server] AaServerStarter: +60 ms opened the settings
19:44:15.147  +  1541ms  [aa-server] AaServerStarter: +1460 ms clicked more options
19:44:15.698  +  2092ms  [aa-server] AaServerStarter: +2002 ms clicked start
19:44:15.809  +  2203ms  [aa-server] AaServerStarter: +2125 ms back
19:44:15.913  +  2307ms  [aa-server] AaServerStarter: start walk ended: TAPPED_START in 2232 ms (menu: Ajuda e feedback | Configurações do desenvolvedor | Iniciar servidor da unidade principal | Sair do modo de desenvolvedor)
19:44:20.916  +  7310ms  [aa-server] AaServerStarter: tapped start, port still closed after 5 s
19:44:35.341  + 21735ms  [aa-server] rider opened Android Auto settings (installed: true) → ANDROID_AUTO_SETTINGS
19:44:35.552  + 21946ms  [aa] [AA] AaReceiver: connected at last after 9 dial(s) refused in 19.9 s
"""

SERVIDOR_2226 = """22:26:37.274  ---- projeção ligada: projection ----
22:26:37.318  +    44ms  [aa-server] AaServerStarter: start (the port is closed) on Android Auto 17.8.663874-release; texts from Android Auto: 7 of 7, the table for the rest
22:26:37.318  +    44ms  [aa-server] AaServerStarter: start walk begins (last Stop touched 6603306 ms ago, last projection end 6605489 ms ago)
22:26:37.344  +    70ms  [aa-server] AaServerStarter: +14 ms opened the settings
22:26:38.654  +  1380ms  [aa-server] AaServerStarter: +1331 ms clicked more options
22:26:39.812  +  2538ms  [aa-server] AaServerStarter: +2472 ms clicked start
22:26:39.923  +  2649ms  [aa-server] AaServerStarter: +2604 ms back
22:26:40.000  +  2726ms  [aa-server] AaServerStarter: start walk ended: TAPPED_START in 2683 ms (menu: Ajuda e feedback | Configurações do desenvolvedor | Iniciar servidor da unidade principal | Sair do modo de desenvolvedor)
22:26:45.005  +  7731ms  [aa-server] AaServerStarter: tapped start, port still closed after 5 s
22:26:47.433  + 10159ms  [aa-server] AaServerStarter: start (the rider asked) on Android Auto 17.8.663874-release; texts from Android Auto: 7 of 7, the table for the rest
22:26:47.434  + 10160ms  [aa-server] AaServerStarter: start walk begins (last Stop touched 6613422 ms ago, last projection end 6615605 ms ago)
22:26:47.468  + 10194ms  [aa-server] AaServerStarter: +25 ms opened the settings
22:26:48.418  + 11144ms  [aa] [AA] AaReceiver: connected at last after 5 dial(s) refused in 9.1 s
22:26:48.501  + 11227ms  [aa-server] AaServerStarter: +1051 ms clicked more options
22:26:49.018  + 11744ms  [aa-server] AaServerStarter: +1577 ms clicked stop (restart)
22:26:50.527  + 13253ms  [aa-server] AaServerStarter: +3086 ms clicked more options
22:26:51.079  + 13805ms  [aa-server] AaServerStarter: +3628 ms clicked start
22:26:51.491  + 14217ms  [aa-server] AaServerStarter: +4053 ms back
22:26:52.655  + 15381ms  [aa-server] AaServerStarter: +5219 ms back
22:26:52.867  + 15593ms  [aa-server] AaServerStarter: start walk ended: TAPPED_START in 5434 ms, restarted (menu: Ajuda e feedback | Configurações do desenvolvedor | Iniciar servidor da unidade principal | Sair do modo de desenvolvedor)
22:26:52.868  + 15594ms  [aa-server] AaServerStarter: server answering already during the walk
"""


# The 13:00 projection of 19/10/2026: a first walk that found no developer menu (no click), and a second one, minutes later, that clicked.
SERVIDOR_1300 = """13:00:25.197  ---- projeção ligada: projection ----
13:02:44.956  +139759ms  [aa-server] AaServerStarter: the accessibility service connected after a try it had refused; trying again by itself
13:02:44.982  +139785ms  [aa-server] AaServerStarter: start (the accessibility service connected) on Android Auto 17.8.663874-release; texts from Android Auto: 7 of 7, the table for the rest
13:02:44.982  +139785ms  [aa-server] AaServerStarter: start walk begins (last Stop touched never in this process, last projection end never in this process)
13:02:45.061  +139864ms  [aa-server] AaServerStarter: +65 ms opened the settings
13:02:45.947  +140750ms  [aa-server] AaServerStarter: +958 ms clicked more options
13:02:46.479  +141282ms  [aa-server] AaServerStarter: +1494 ms back
13:02:47.061  +141864ms  [aa-server] AaServerStarter: +2075 ms clicked more options
13:02:48.403  +143206ms  [aa-server] AaServerStarter: +3414 ms back
13:02:48.981  +143784ms  [aa-server] AaServerStarter: +3997 ms back
13:02:49.278  +144081ms  [aa-server] AaServerStarter: start walk ended: DEVELOPER_MODE_OFF in 4297 ms (menu: Ajuda e feedback | Enviar feedback)
13:05:06.354  +281157ms  [aa-server] rider opened Android Auto settings (installed: true) → ANDROID_AUTO_SETTINGS
13:06:24.571  +359374ms  [aa-server] AaServerStarter: start (the rider asked) on Android Auto 17.8.663874-release; texts from Android Auto: 7 of 7, the table for the rest
13:06:24.571  +359374ms  [aa-server] AaServerStarter: start walk begins (last Stop touched never in this process, last projection end never in this process)
13:06:24.600  +359403ms  [aa-server] AaServerStarter: +22 ms opened the settings
13:06:25.646  +360449ms  [aa-server] AaServerStarter: +1072 ms clicked more options
13:06:26.174  +360977ms  [aa-server] AaServerStarter: +1592 ms clicked start
13:06:26.281  +361084ms  [aa-server] AaServerStarter: +1708 ms back
13:06:26.377  +361179ms  [aa-server] AaServerStarter: start walk ended: TAPPED_START in 1806 ms (menu: Ajuda e feedback | Configurações do desenvolvedor | Iniciar servidor da unidade principal | Sair do modo de desenvolvedor)
13:06:26.382  +361185ms  [aa] [AA] AaReceiver: connected at last after 180 dial(s) refused in 359.1 s
"""


class ServidorDoAA(unittest.TestCase):
    """O servidor do Android Auto por Ligar: os intervalos da caminhada e a causa."""

    def ler(self, texto):
        d = Path(tempfile.mkdtemp())
        (d / "ride-20261019-192329.log").write_text(CABECALHO + texto, encoding="utf-8")
        return ler_registro.ler([d])

    def tempo(self, texto):
        return "\n".join(ler_registro.linha_do_tempo(self.ler(texto)))

    def test_os_intervalos_da_caminhada_de_1944(self):
        tempo = self.tempo(SERVIDOR_1944)
        self.assertIn("servidor do AA: toque → início da caminhada +77 ms → clique em Iniciar +2015 ms → Voltar +111 ms "
                      "→ fim da caminhada +104 ms → primeira conexão +19639 ms", tempo)

    def test_a_visita_que_subiu_o_servidor_e_a_que_nao_subiu(self):
        base = SERVIDOR_1944.split("19:44:35.341")[0]
        subiu = base + "19:44:21.000  + 7400ms  [aa-server] AaServerStarter: the visit brought the server up\n"
        nao = base + "19:44:21.000  + 7400ms  [aa-server] AaServerStarter: the visit did not bring the port up\n"
        self.assertIn("a visita à tela do AA subiu o servidor", self.tempo(subiu))
        self.assertIn("a visita à tela do AA não subiu o servidor", self.tempo(nao))
        self.assertNotIn("visita", self.tempo(base))

    def test_a_porta_fechada_e_o_servidor_que_so_atendeu_quando_a_tela_do_AA_abriu(self):
        achados = "\n".join(ler_registro.problemas(self.ler(SERVIDOR_1944)))
        self.assertIn("servidor do AA — toque ok, porta fechada em 5 s", achados)
        self.assertIn("servidor do AA — servidor atendeu 211 ms depois de a tela do AA abrir", achados)

    def test_a_segunda_caminhada_que_reabre_a_tela_e_atendida_antes_do_clique(self):
        achados = "\n".join(ler_registro.problemas(self.ler(SERVIDOR_2226)))
        self.assertIn("servidor atendeu 950 ms depois de a tela do AA abrir", achados)
        self.assertIn("Voltar +111 ms", self.tempo(SERVIDOR_2226))

    def test_uma_caminhada_que_deu_certo_nao_acusa_nada(self):
        certo = (
            "20:10:58.000  ---- projeção ligada: projection ----\n"
            "20:10:59.063  + 20890ms  [aa-server] AaServerStarter: start walk begins (last Stop touched 994034 ms ago, last projection end 996027 ms ago)\n"
            "20:10:59.097  + 20924ms  [aa-server] AaServerStarter: +25 ms opened the settings\n"
            "20:11:00.095  + 21922ms  [aa-server] AaServerStarter: +1030 ms clicked more options\n"
            "20:11:02.802  + 24629ms  [aa-server] AaServerStarter: +3727 ms clicked start\n"
            "20:11:02.866  + 24693ms  [aa] [AA] AaReceiver: connected at last after 10 dial(s) refused in 22.6 s\n"
            "20:11:02.949  + 24776ms  [aa-server] AaServerStarter: +3885 ms back\n"
            "20:11:03.725  + 25552ms  [aa-server] AaServerStarter: start walk ended: TAPPED_START in 4663 ms (menu: Ajuda e feedback | Configurações do desenvolvedor | Iniciar servidor da unidade principal | Sair do modo de desenvolvedor)\n"
            "20:30:00.000  ---- projeção desligada: projection ----\n"
        )
        self.assertEqual([], [a for a in ler_registro.problemas(self.ler(certo)) if "servidor do AA" in a])

    def test_menu_lido_pela_metade_e_so_menu_curto(self):
        def com(menu):
            return (
                "12:54:00.000  ---- projeção ligada: projection ----\n"
                "12:54:38.000  [aa-server] AaServerStarter: start walk begins (last Stop touched never in this process)\n"
                f"12:54:43.309  [aa-server] AaServerStarter: start walk ended: DEVELOPER_MODE_OFF in 4954 ms (menu: {menu})\n"
                "12:59:00.000  ---- projeção desligada: projection ----\n"
            )
        curto = "\n".join(ler_registro.problemas(self.ler(com("Ajuda e feedback | Enviar feedback"))))
        self.assertIn("menu pela metade (AA frio?)", curto)
        cheio = "\n".join(ler_registro.problemas(self.ler(com("Ajuda e feedback | Enviar feedback | Mais um | E outro"))))
        self.assertNotIn("menu pela metade", cheio)

    def test_sem_linhas_aa_server_a_edicao_lite_nao_acusa_nada(self):
        lite = (
            "10:00:01.000  ---- projeção ligada: projection ----\n"
            "10:00:02.000  [aa] [AA] AaReceiver: connected at last after 4 dial(s) refused in 6.0 s\n"
            "10:09:00.000  ---- projeção desligada: projection ----\n"
        )
        self.assertEqual("", ler_registro._servidor_aa(ler_registro.projecoes(self.ler(lite))[0])[0])
        self.assertEqual([], [a for a in ler_registro.problemas(self.ler(lite)) if "servidor do AA" in a])
        self.assertNotIn("servidor do AA", self.tempo(lite))

    def test_a_primeira_caminhada_sem_clique_nao_pega_o_clique_de_uma_caminhada_de_minutos_depois(self):
        tempo = self.tempo(SERVIDOR_1300)
        historia = next(l for l in tempo.splitlines() if "servidor do AA:" in l)
        self.assertNotIn("+-", historia)                 # nenhum intervalo negativo
        self.assertNotIn("clique em Iniciar", historia)  # a primeira caminhada saiu sem clicar (menu pela metade)
        self.assertIn("fim da caminhada +", historia)
        self.assertIn("menu pela metade (AA frio?)", historia)


def device(hora, bike):
    return linha(hora, "device", f"device battery=75% charging=no temp=33.0C volt=3.9V thermal=none mem=21/256MB phone=2850MB low=no "
                                 f"screen=on interactive=yes doze=no saver=off exempt=yes bike={bike} data=wifi/ok [30 s]")


class WifiDoPainelEEspera(unittest.TestCase):
    """The dash's Wi-Fi before a fall comes from `bike=`, and a long wait for the dash is named."""

    def tempo(self, texto):
        d = Path(tempfile.mkdtemp())
        (d / "ride-20261004-100000.log").write_text(texto, encoding="utf-8")
        return ler_registro.linha_do_tempo(ler_registro.ler([d])), ler_registro.problemas(ler_registro.ler([d]))

    def test_o_wifi_antes_da_queda_e_o_do_painel_e_nao_o_de_casa_do_celular(self):
        home = "rssi=-61 dBm tx=72 rx=72 Mbps 2.4 GHz powersave=on screen=on | last 60 s, every 5 s: rssi -61 -61 | tx 72 72"
        tempo, _ = self.tempo(projecao(
            linha("10:00:30.000", "link", home),
            device("10:00:48.000", "-74dBm/2427MHz/58Mbps"),
            linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms"),
        ))
        texto = "\n".join(tempo)
        self.assertIn("Wi-Fi do painel antes da queda: -74 dBm, 2427 MHz, 58 Mbps (12 s antes)", texto)
        self.assertNotIn("-61", texto)  # the home Wi-Fi of the phone is not told as the dash's

    def test_sem_bike_perto_da_queda_diz_que_o_do_painel_nao_esta_no_registro(self):
        tempo, _ = self.tempo(projecao(
            device("10:00:10.000", "-74dBm/2427MHz/58Mbps"),  # more than 90 s before: the dash's signal of then, not "before the fall"
            device("10:01:50.000", "none"),
            linha("10:02:00.000", "wifi", "W: link lost — reconnecting in 4000ms"),
        ))
        texto = "\n".join(tempo)
        self.assertNotIn("Wi-Fi do painel antes da queda", texto)
        self.assertNotIn("-74 dBm", texto)

    def test_o_laco_de_espera_tem_duracao_e_numero_de_pedidos(self):
        pedidos = [linha(f"10:{1 + i // 4:02d}:{(i % 4) * 14:02d}.000", "wifi",
                         f"requestNetwork 'RE_****30' everConnected=true timeout=10000ms; request #{i + 1} (0s since the first)")
                   for i in range(60)]  # 60 requests, 14 s apart: up to 10:15:02
        tempo, problemas = self.tempo(projecao(*pedidos, linha("10:16:00.000", "wifi", "connected 'RE_****30' in 900ms")))
        self.assertTrue(any("espera pelo painel: 60 pedidos de rede em 15 min 00 s" in x for x in tempo), tempo)
        self.assertTrue(any("laço de espera pelo painel — 60 pedidos de rede em 15 min 00 s" in x for x in problemas), problemas)

    def test_uma_espera_curta_nao_e_problema_e_a_muito_curta_nem_aparece(self):
        pedido = lambda hora, n: linha(hora, "wifi", f"requestNetwork 'RE_****30' everConnected=true timeout=10000ms; request #{n} (1s since the first)")
        tempo, problemas = self.tempo(projecao(
            pedido("10:01:00.000", 1), pedido("10:01:14.000", 2), pedido("10:02:28.000", 3),
            linha("10:02:30.000", "wifi", "connected 'RE_****30' in 900ms"),
            pedido("10:05:00.000", 4), pedido("10:05:14.000", 5),
            linha("10:05:16.000", "wifi", "connected 'RE_****30' in 900ms"),
        ))
        self.assertTrue(any("espera pelo painel: 3 pedidos de rede em 1 min 30 s" in x for x in tempo), tempo)
        self.assertFalse(any("2 pedidos" in x for x in tempo), tempo)  # 14 s: nothing to tell
        self.assertEqual([], [p for p in problemas if "laço de espera" in p])

    def test_um_pedido_respondido_com_o_wifi_ja_conectado_fecha_o_laco(self):
        pedido = lambda hora, n: linha(hora, "wifi", f"requestNetwork 'RE_****30' everConnected=true timeout=10000ms; request #{n} (1s since the first)")
        _, problemas = self.tempo(projecao(
            pedido("10:01:00.000", 1), pedido("10:01:14.000", 2),
            linha("10:01:20.000", "wifi", "using already-connected WiFi 'RE_****30'"),
            pedido("10:08:00.000", 3), pedido("10:08:14.000", 4),
            linha("10:08:20.000", "wifi", "using already-connected WiFi 'RE_****30'"),
        ))
        self.assertEqual([], [p for p in problemas if "laço de espera" in p], problemas)

    def test_uma_espera_que_passa_da_meia_noite_tem_duracao_positiva(self):
        d = Path(tempfile.mkdtemp())
        pedidos = "".join(f"23:{57 + i // 4:02d}:{(i % 4) * 14 + 1:02d}.000  +   1ms  [wifi] requestNetwork 'RE_****30' everConnected=true "
                          f"timeout=10000ms; request #{i + 1} (1s since the first)\n" for i in range(8))
        (d / "ride-20261019-235000.log").write_text(
            "23:50:00.000  ---- projeção ligada: projection ----\n" + pedidos
            + "00:04:00.000  +   1ms  [wifi] requestNetwork 'RE_****30' everConnected=true timeout=10000ms; request #9 (1s since the first)\n"
            "00:05:00.000  +   1ms  [wifi] connected 'RE_****30' in 900ms\n00:09:00.000  ---- projeção desligada: projection ----\n", encoding="utf-8")
        problemas = ler_registro.problemas(ler_registro.ler([d]))
        self.assertTrue(any("laço de espera pelo painel — 9 pedidos de rede em 7 min 59 s" in p for p in problemas), problemas)

    def test_o_relogio_que_passa_da_meia_noite_muda_o_dia(self):
        d = Path(tempfile.mkdtemp())
        (d / "ride-20261019-235000.log").write_text(
            "23:59:59.000  +   1ms  [aa] a\n00:00:05.000  +   1ms  [aa] b\n", encoding="utf-8")
        linhas = ler_registro.ler([d])
        self.assertEqual(["2026-10-19", "2026-10-20"], [x.dia for x in linhas])
        self.assertEqual(6.0, (ler_registro.instante(linhas[1]) - ler_registro.instante(linhas[0])).total_seconds())


LOGCAT_ANDROID_AUTO = (
    "--------- beginning of crash\n"
    "10-04 10:05:01.200 26372 26528 E AndroidRuntime: FATAL EXCEPTION: Thread-2\n"
    "10-04 10:05:01.200 26372 26528 E AndroidRuntime: Process: com.google.android.projection.gearhead:projection, PID: 26372\n"
    "10-04 10:05:01.200 26372 26528 E AndroidRuntime: java.lang.NullPointerException: Attempt to invoke virtual method "
    "'boolean java.net.ServerSocket.isClosed()' on a null object reference\n"
    "10-04 10:05:01.200 26372 26528 E AndroidRuntime: \tat xaz.run(SourceFile:489)\n"
    # Another app's crash, and a line with personal data: neither is told.
    "10-04 10:06:00.000  4000  4010 E AndroidRuntime: FATAL EXCEPTION: main\n"
    "10-04 10:06:00.000  4000  4010 E AndroidRuntime: Process: com.example.other, PID: 4000\n"
    "10-04 10:06:00.000  4000  4010 E AndroidRuntime: java.lang.IllegalStateException: boom\n"
    "10-04 10:06:01.000  1000  1001 I SomeTag: account owner@example.invalid signed in to HomeWifi\n"
)
LOGCAT_DO_APP = (
    "10-04 10:03:15.365  1337  1437 I am_cpu  : [26890,10480,com.reau.app,300065,150,30]\n"
    "10-04 10:03:20.000 26890 26890 I Choreographer: Skipped 301 frames!  The application may be doing too much work on its main thread.\n"
    "10-04 10:03:21.000 26890 26890 I Choreographer: Skipped 45 frames!  The application may be doing too much work on its main thread.\n"
    "10-04 10:03:22.000 26890 26890 I Choreographer: Skipped 120 frames!  The application may be doing too much work on its main thread.\n"
    "10-04 10:03:23.500 26890 26912 I HWUI    : Davey! duration=1234ms; Flags=0, FrameTimelineVsyncId=1\n"
    "10-04 10:03:24.500 26890 26912 I HWUI    : Davey! duration=800ms; Flags=0, FrameTimelineVsyncId=2\n"
    # The same lines from another process are not the app's.
    "10-04 10:03:25.000  5555  5555 I Choreographer: Skipped 999 frames!  The application may be doing too much work on its main thread.\n"
    "10-04 10:03:26.000  5555  5560 I HWUI    : Davey! duration=9999ms; Flags=0, FrameTimelineVsyncId=3\n"
)


class LogcatDoSistema(unittest.TestCase):
    def pasta(self, logcat, bye="10:05:00.000"):
        d = Path(tempfile.mkdtemp())
        (d / "ride-20261004-100000.log").write_text(
            projecao(linha(bye, "aa", "[AA] AapTransport stopping and sending byebye")), encoding="utf-8")
        (d / "logcat-sistema-atual.txt").write_text(logcat, encoding="utf-8")
        return d

    def achados(self, logcat, **k):
        d = self.pasta(logcat, **k)
        return ler_registro.problemas(ler_registro.ler([d])) + ler_registro.do_sistema([d], ler_registro.ler([d]))

    def test_o_android_auto_que_travou_vem_com_o_intervalo_desde_o_byebye(self):
        achados = self.achados(LOGCAT_ANDROID_AUTO)
        travou = [a for a in achados if "travou" in a]
        self.assertEqual(1, len(travou), achados)
        self.assertEqual("10:05:01: o Android Auto (gearhead:projection) travou — java.lang.NullPointerException (1,2 s depois do byebye do REau)",
                         travou[0])

    def test_a_mensagem_da_excecao_nao_sai_porque_e_texto_livre(self):
        logcat = LOGCAT_ANDROID_AUTO.replace("Attempt to invoke virtual method", "account owner@example.invalid HomeWifi")
        texto = "\n".join(self.achados(logcat))
        self.assertIn("java.lang.NullPointerException", texto)
        self.assertNotIn("owner@example.invalid", texto)
        self.assertNotIn("HomeWifi", texto)

    def test_o_dia_que_o_ano_nao_tem_nao_derruba_o_leitor_e_dezembro_para_janeiro_acha_o_ano_certo(self):
        # 02-29 in 2026 (not a leap year): the line has no instant, and is told without an interval.
        feb = LOGCAT_ANDROID_AUTO.replace("10-04 10:05:01.200", "02-29 10:05:01.200")
        d = Path(tempfile.mkdtemp())
        # A registro whose name has no day that exists (the filter by day then has nothing to go by) and a logcat line of 02-29.
        (d / "ride-20260229-100000.log").write_text(projecao(linha("10:05:00.000", "aa", "[AA] AapTransport stopping and sending byebye")), encoding="utf-8")
        (d / "logcat-sistema-atual.txt").write_text(feb, encoding="utf-8")
        self.assertTrue(any("sem byebye do REau" in a for a in ler_registro.do_sistema([d], ler_registro.ler([d]))))
        # The registros of 31/12/2026, the crash at 01-01 00:00:01 (2027) a second after the byebye.
        d = Path(tempfile.mkdtemp())
        (d / "ride-20261231-235500.log").write_text(
            "23:55:00.000  ---- projeção ligada: projection ----\n23:59:59.800  +   1ms  [aa] [AA] AapTransport stopping and sending byebye\n"
            "00:00:09.000  ---- projeção desligada: projection ----\n", encoding="utf-8")
        (d / "logcat-sistema.txt").write_text(
            "01-01 00:00:01.000 100 101 E AndroidRuntime: FATAL EXCEPTION: Thread-2\n"
            "01-01 00:00:01.000 100 101 E AndroidRuntime: Process: com.google.android.projection.gearhead:car, PID: 100\n"
            "01-01 00:00:01.000 100 101 E AndroidRuntime: java.lang.IllegalStateException: x\n", encoding="utf-8")
        achados = ler_registro.do_sistema([d], ler_registro.ler([d]))
        self.assertTrue(any("(1,2 s depois do byebye do REau)" in a for a in achados), achados)

    def test_sem_byebye_perto_so_diz_a_hora(self):
        achados = self.achados(LOGCAT_ANDROID_AUTO, bye="10:02:00.000")
        self.assertTrue(any("(sem byebye do REau nos 60 s antes)" in a for a in achados), achados)

    def test_so_o_android_auto_e_o_app_saem_e_nada_pessoal(self):
        texto = "\n".join(self.achados(LOGCAT_ANDROID_AUTO + LOGCAT_DO_APP))
        for pessoal in ("owner@example.invalid", "HomeWifi", "com.example.other", "IllegalStateException"):
            self.assertNotIn(pessoal, texto)

    def test_davey_e_quadros_pulados_so_do_processo_do_app_e_de_100_para_cima(self):
        achados = self.achados(LOGCAT_DO_APP)
        self.assertIn("10:03:23: o app travou quadros (2× Davey!, o pior de 1234 ms)", achados)
        self.assertIn("10:03:20: o app pulou quadros (2× com 100 ou mais, o pior com 301)", achados)

    def test_sem_o_processo_do_app_no_logcat_nao_acusa_o_que_nao_e_dele(self):
        sem_pid = LOGCAT_DO_APP.split("\n", 1)[1]  # without the am_cpu line there is no telling whose Davey it is
        self.assertEqual([], [a for a in self.achados(sem_pid) if "quadros" in a])

    def test_o_logcat_de_uma_pasta_diag_e_achado_na_pasta_de_cima(self):
        raiz = Path(tempfile.mkdtemp())
        (raiz / "diag").mkdir()
        (raiz / "diag" / "ride-20261004-100000.log").write_text(
            projecao(linha("10:05:00.000", "aa", "[AA] AapTransport stopping and sending byebye")), encoding="utf-8")
        (raiz / "logcat-sistema-atual.txt").write_text(LOGCAT_ANDROID_AUTO, encoding="utf-8")
        achados = ler_registro.do_sistema([raiz / "diag"], ler_registro.ler([raiz / "diag"]))
        self.assertTrue(any("travou" in a for a in achados), achados)

    def test_sem_logcat_do_sistema_nao_diz_nada_e_o_logcat_nao_e_lido_como_registro(self):
        d = Path(tempfile.mkdtemp())
        (d / "ride-20261004-100000.log").write_text(BOA, encoding="utf-8")
        self.assertEqual([], ler_registro.do_sistema([d], ler_registro.ler([d])))
        (d / "logcat-sistema-atual.txt").write_text(LOGCAT_ANDROID_AUTO, encoding="utf-8")
        # Given the file itself, the registro reader leaves it alone.
        self.assertEqual([], ler_registro.ler([d / "logcat-sistema-atual.txt"]))


class EsperaPeloTick(unittest.TestCase):
    """The `tickwait=` field of the `video fps` line comes out in the `vídeo:` summary."""

    def video(self, *janelas):
        d = Path(tempfile.mkdtemp())
        (d / "ride-20261004-100000.log").write_text(projecao(*[
            linha(f"10:00:{10 + 5 * i:02d}.000", "stream", "video fps target=4 sent=3.9 (20frames/5.1s) composed=3.9 kbps=300 set=400k "
                  f"frame=9000B source=61 drawn=20 repeated=0 compose=2.7ms encode=12.0ms late=0 {j}full=true thermal=OK")
            for i, j in enumerate(janelas)]), encoding="utf-8")
        return "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([d])))

    def test_a_espera_media_o_pior_p95_e_o_maximo_saem_no_resumo_do_video(self):
        texto = self.video("tickwait=100/200/250ms ", "tickwait=140/300/400ms ", "tickwait=- ")
        self.assertIn("espera pelo tick média 120 ms (pior p95 300, máx 400)", texto)

    def test_sem_o_campo_o_resumo_nao_diz_nada_da_espera(self):
        self.assertNotIn("espera pelo tick", self.video("", ""))


LOGCAT_DO_APP_DIAG = (
    "--------- beginning of main\n"
    "10-04 10:03:00.000  5000  5001 I HWUI    : Davey! duration=900ms; Flags=0, FrameTimelineVsyncId=1\n"
    "10-04 10:04:59.500  5000  5001 I HWUI    : Davey! duration=1500ms; Flags=0, FrameTimelineVsyncId=2\n"
    "10-04 10:05:00.100  5000  5000 I Choreographer: Skipped 120 frames!  The application may be doing too much work on its main thread.\n"
    "10-04 10:05:30.000  5000  5000 I Choreographer: Skipped 50 frames!  The application may be doing too much work on its main thread.\n"
    "10-04 10:06:59.900  5000  5000 I Choreographer: Skipped 301 frames!  The application may be doing too much work on its main thread.\n"
    "10-04 10:06:59.950  5000  5001 I HWUI    : Davey! duration=5040ms; Flags=0, FrameTimelineVsyncId=3\n"
)


class LogcatDoApp(unittest.TestCase):
    """The app's own logcat (diag/logcat-*.txt) is read too, and each stall says the walk it fell in."""

    def pasta(self, logcat=LOGCAT_DO_APP_DIAG):
        raiz = Path(tempfile.mkdtemp())
        (raiz / "diag").mkdir()
        (raiz / "diag" / "ride-20261004-100000.log").write_text(projecao(
            linha("10:05:00.000", "aa-server", "AaServerStarter: start walk ended: TAPPED_START in 2000 ms (menu: x | y)"),
            linha("10:07:00.000", "aa-server", "AaServerStarter: stop walk ended: TAPPED_STOP in 1500 ms"),
        ), encoding="utf-8")
        (raiz / "diag" / "logcat-20261004-100000.txt").write_text(logcat, encoding="utf-8")
        return raiz / "diag"

    def achados(self, **k):
        d = self.pasta(**k)
        return ler_registro.do_sistema([d], ler_registro.ler([d]))

    def test_cada_travada_diz_a_caminhada_em_que_caiu(self):
        achados = self.achados()
        self.assertIn("04/10 10:04:59: o app travou a tela — Davey! de 1500 ms (durante a caminhada de iniciar o servidor)", achados)
        self.assertIn("04/10 10:05:00: o app travou a tela — 120 quadros pulados (durante a caminhada de iniciar o servidor)", achados)
        self.assertIn("04/10 10:06:59: o app travou a tela — 301 quadros pulados (durante a caminhada de parar o servidor)", achados)
        self.assertIn("04/10 10:06:59: o app travou a tela — Davey! de 5040 ms (durante a caminhada de parar o servidor)", achados)

    def test_fora_de_caminhada_sai_sem_fase_e_menos_de_100_quadros_nao_sai(self):
        achados = self.achados()
        self.assertIn("04/10 10:03:00: o app travou a tela — Davey! de 900 ms", achados)  # no phase text at the end
        self.assertFalse(any("50 quadros" in a for a in achados), achados)

    def test_o_logcat_do_sistema_nao_e_lido_como_o_do_app(self):
        d = self.pasta(logcat="")
        (d / "logcat-sistema-atual.txt").write_text(LOGCAT_DO_APP, encoding="utf-8")  # no am_cpu line: not the app's by pid
        self.assertEqual([], [a for a in ler_registro.do_sistema([d], ler_registro.ler([d])) if "travou a tela" in a])

    def test_logcat_do_app_sem_travadas_deixa_o_total_do_logcat_do_sistema(self):
        d = self.pasta(logcat="10-04 10:03:00.000  5000  5001 I SomeTag: nothing to tell\n")
        (d.parent / "logcat-sistema-atual.txt").write_text(LOGCAT_DO_APP, encoding="utf-8")
        achados = ler_registro.do_sistema([d], ler_registro.ler([d]))
        self.assertTrue(any("o app travou quadros (2× Davey!" in a for a in achados), achados)

    def test_so_entram_os_dias_dos_registros_lidos(self):
        outro_dia = LOGCAT_DO_APP_DIAG.replace("10-04 ", "10-03 ")
        d = self.pasta(logcat=outro_dia + LOGCAT_DO_APP_DIAG)
        travadas = [a for a in ler_registro.do_sistema([d], ler_registro.ler([d])) if "travou a tela" in a]
        self.assertTrue(travadas and all(a.startswith("04/10 ") for a in travadas), travadas)
        self.assertEqual(5, len(travadas), travadas)  # the five of the day of the registro; none of the day before

    def test_so_os_primeiros_trinta_e_as_das_caminhadas_primeiro(self):
        muitas = "".join(f"10-04 10:03:{n % 60:02d}.000  5000  5001 I HWUI    : Davey! duration=900ms; Flags=0\n" for n in range(40))
        achados = self.achados(logcat=muitas + LOGCAT_DO_APP_DIAG)
        travadas = [a for a in achados if "travou a tela" in a]
        self.assertEqual(30, len(travadas))
        self.assertTrue(any("(mais " in a for a in achados), achados)
        self.assertEqual(4, sum("durante a caminhada" in a for a in travadas))  # those of the walks are never the ones cut


# ── 03/11/2026: alarmes falsos corrigidos e regras novas (varredura de 02/11) ─────────────────────────────────────────────────

RESUMO_SEM_DARK = "resumo duration=9m00s streaming=8m56s drops dash=0 aa=0 watchdogs=0 frames=8500 fps avg=15.9 min=15.0 gap max=0.3s thermal max=none battery 80→76% (-4) marks=1"


def resumo_com_dark(dark):
    return RESUMO_SEM_DARK.replace("gap max=0.3s", f"gap max=0.3s dark={dark}")


class AlarmesFalsos(unittest.TestCase):
    def arquivo(self, texto, nome="ride-20261103-100000.log"):
        d = Path(tempfile.mkdtemp())
        (d / nome).write_text(texto, encoding="utf-8")
        return d / nome

    def problemas(self, texto):
        return ler_registro.problemas(ler_registro.ler([self.arquivo(texto)]))

    def tempo(self, texto):
        return "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))

    # 1. painel sem imagem no fim da corrida

    def moto_desligada_no_fim(self, dark, *antes):
        # The bike is switched off at 10:08:00: the Wi-Fi goes, the video stops, and the rider turns the projection off at 10:09:00.
        return projecao(
            linha("10:01:00.000", "session", "→ STREAMING"),
            *antes,
            linha("10:08:00.000", "wifi", "W: link lost — reconnecting in 4000ms"),
            linha("10:08:04.000", "stream", "video loop stop (0 frames held back before a keyframe)"),
            linha("10:09:00.000", "projection", resumo_com_dark(dark)))

    def test_a_interrupcao_final_com_a_moto_desligada_nao_e_painel_sem_imagem(self):
        # dark=1/56s: 10:08:04 to 10:09:00, the video never came back before the projection ended.
        self.assertFalse(any("painel sem imagem" in p for p in self.problemas(self.moto_desligada_no_fim("1/56s (max 56s)"))))

    def test_a_interrupcao_final_sai_da_conta_mas_a_do_meio_da_corrida_continua(self):
        antes = (linha("10:04:00.000", "stream", "video loop stop (0 frames held back before a keyframe)"),
                 linha("10:07:00.000", "session", "→ STREAMING"))  # 3 min of dark in the middle of the ride
        p = [x for x in self.problemas(self.moto_desligada_no_fim("2/3m56s (max 3m00s)", *antes)) if "painel sem imagem" in x]
        self.assertEqual(1, len(p), p)
        self.assertIn("1 interrupção, 3m00s no total, a maior de 3m00s", p[0])

    def test_quando_a_final_era_a_maior_o_maior_vem_das_outras_interrupcoes(self):
        antes = (linha("10:04:00.000", "stream", "video loop stop (0 frames held back before a keyframe)"),
                 linha("10:04:30.000", "session", "→ STREAMING"))  # 30 s in the middle
        # The final is 2 min 30 s (10:06:30 to 10:09:00) and is the max in the summary: the max left is the 30 s of the middle.
        texto = projecao(
            linha("10:01:00.000", "session", "→ STREAMING"), *antes,
            linha("10:06:30.000", "stream", "video loop stop (0 frames held back before a keyframe)"),
            linha("10:09:00.000", "projection", resumo_com_dark("2/3m00s (max 2m30s)")))
        p = [x for x in self.problemas(texto) if "painel sem imagem" in x]
        self.assertEqual(1, len(p), p)
        self.assertIn("1 interrupção, 30s no total, a maior de 30s", p[0])

    def test_a_parada_do_video_depois_do_resumo_nao_e_interrupcao_final(self):
        # The stop of the rider's Desligar comes after the resumo: the dark of the middle (1 min 8 s) is still told.
        texto = projecao(
            linha("10:01:00.000", "session", "→ STREAMING"),
            linha("10:08:59.000", "projection", resumo_com_dark("1/1m08s (max 1m08s)")),
            linha("10:08:59.500", "stream", "video loop stop (0 frames held back before a keyframe)"))
        self.assertTrue(any("painel sem imagem — 1 interrupção, 1m08s no total, a maior de 1m08s" in p for p in self.problemas(texto)))

    # 2. laço de espera com o painel nunca na varredura

    def pedido(self, hora, n, varredura):
        return linha(hora, "wifi", f"requestNetwork 'RE_****30' everConnected=false timeout=30000ms; request #{n} (0s since the first), "
                                   f"screen interactive=true locked=true, scan of 16 networks, {varredura}, approvedBefore=true")

    NADA = "the dash not among them"
    VISTO = "the dash: 'RE_****30' -57 dBm 2422 MHz seen 3 s ago"

    def laco(self, *varreduras, conecta=True):
        pedidos = [self.pedido(f"10:{1 + 40 * i // 60:02d}:{(40 * i) % 60:02d}.000", i + 1, v) for i, v in enumerate(varreduras)]
        fim = (linha("10:08:00.000", "wifi", "connected 'RE_****30' in 900ms"),) if conecta else ()
        return projecao(*pedidos, *fim)

    def test_o_laco_sem_o_painel_na_varredura_nao_e_problema_e_diz_moto_desligada(self):
        texto = self.laco(*[self.NADA] * 7)  # 7 requests in 7 min: the bike was off
        self.assertEqual([], [p for p in self.problemas(texto) if "laço de espera" in p])
        self.assertIn("espera pelo painel: 7 pedidos de rede em 7 min 00 s (o painel não apareceu na varredura: moto desligada?)", self.tempo(texto))

    def test_o_laco_com_o_painel_na_varredura_continua_sendo_problema(self):
        texto = self.laco(*[self.VISTO] * 7)
        p = [x for x in self.problemas(texto) if "laço de espera" in x]
        self.assertEqual(1, len(p), p)
        self.assertNotIn("moto desligada", self.tempo(texto))

    def test_o_painel_visto_so_no_ultimo_pedido_e_a_moto_que_ligou_no_fim(self):
        texto = self.laco(*[self.NADA] * 6, self.VISTO)
        self.assertEqual([], [p for p in self.problemas(texto) if "laço de espera" in p])
        self.assertNotIn("moto desligada", self.tempo(texto))  # it did show, once: the line does not say it never did

    def test_o_pedido_que_expira_com_o_painel_visto_conta_no_laco(self):
        texto = projecao(
            self.pedido("10:01:00.000", 1, self.NADA),
            linha("10:01:30.000", "wifi", "W: unavailable after 30000ms (initial connect) — giving up, seenInScan=true quick=0 joined=false"),
            self.pedido("10:02:00.000", 2, self.NADA), self.pedido("10:05:00.000", 3, self.NADA))
        self.assertTrue(any("laço de espera" in p for p in self.problemas(texto)))

    # 3. temperatura

    def olhada(self, hora, graus, carregando="no", tela="on"):
        return linha(hora, "device", f"device battery=80% charging={carregando} temp={graus}C thermal=none screen={tela} [30 s]")

    def temperaturas(self, *olhadas, extra=()):
        return [p for p in self.problemas(projecao(*olhadas, *extra)) if "temperatura" in p]

    def test_38_graus_parados_nao_alarmam(self):
        self.assertEqual([], self.temperaturas(self.olhada("10:01:00.000", "38.0"), self.olhada("10:05:00.000", "38.0")))

    def test_a_subida_que_so_o_carregador_causa_nao_alarma(self):
        # 31 °C in use, the charger goes in, 40 °C charging, the charger goes out and the next looks are still the charge's heat.
        achados = self.temperaturas(
            self.olhada("10:01:00.000", "31.0"),
            linha("10:02:00.000", "system", "charger in (ac)"),
            self.olhada("10:03:00.000", "40.0", carregando="yes"),
            linha("10:20:00.000", "system", "charger out"),
            self.olhada("10:20:30.000", "40.0"), self.olhada("10:25:00.000", "39.0"))
        self.assertEqual([], achados)

    def test_depois_dos_10_min_do_carregador_o_trecho_novo_conta_do_zero(self):
        achados = self.temperaturas(
            self.olhada("10:01:00.000", "40.0", carregando="yes"),
            linha("10:02:00.000", "system", "charger out"),
            self.olhada("10:13:00.000", "36.0"), self.olhada("10:14:00.000", "36.5"))
        self.assertEqual([], achados)

    def test_subir_8_graus_sem_carregador_alarma(self):
        achados = self.temperaturas(self.olhada("10:01:00.000", "31.0"), self.olhada("10:05:00.000", "39.0"))
        self.assertEqual(1, len(achados), achados)
        self.assertIn("31.0", achados[0])
        self.assertIn("39.0", achados[0])

    def test_o_carregador_no_meio_nao_junta_dois_trechos(self):
        achados = self.temperaturas(
            self.olhada("10:01:00.000", "31.0"), self.olhada("10:02:00.000", "33.0"),
            self.olhada("10:03:00.000", "41.0", carregando="yes"),
            self.olhada("10:30:00.000", "39.0"))  # 31 → 39 would be 8 if the stretches were joined
        self.assertEqual([], achados)

    def test_passar_de_44_graus_alarma_mesmo_carregando(self):
        achados = self.temperaturas(self.olhada("10:01:00.000", "40.0", carregando="yes"), self.olhada("10:05:00.000", "45.0", carregando="yes", tela="off"))
        self.assertEqual(1, len(achados), achados)
        self.assertIn("passou de 44 °C", achados[0])
        self.assertIn("45.0", achados[0])
        self.assertIn("(carregando)", achados[0])

    def test_44_graus_exatos_nao_alarmam(self):
        self.assertEqual([], self.temperaturas(self.olhada("10:01:00.000", "44.0", carregando="yes")))

    # 4. Davey da abertura do app

    def pasta_com_logcat(self, logcat, ride_extra=()):
        raiz = Path(tempfile.mkdtemp())
        (raiz / "diag").mkdir()
        (raiz / "diag" / "ride-20261004-100000.log").write_text(projecao(*ride_extra), encoding="utf-8")
        (raiz / "diag" / "logcat-20261004-100000.txt").write_text(logcat, encoding="utf-8")
        return raiz / "diag"

    def test_o_davey_da_abertura_do_app_nao_entra_e_o_de_depois_entra(self):
        d = self.pasta_com_logcat(
            "10-04 10:00:01.000  5000  5001 I HWUI    : Davey! duration=786ms; Flags=0, FrameTimelineVsyncId=1\n"
            "10-04 10:00:01.200  5000  5000 I Choreographer: Skipped 120 frames!  The application may be doing too much work on its main thread.\n"
            "10-04 10:00:09.000  5000  5001 I HWUI    : Davey! duration=1208ms; Flags=0, FrameTimelineVsyncId=2\n")
        achados = ler_registro.do_sistema([d], ler_registro.ler([d]))
        self.assertEqual(["04/10 10:00:09: o app travou a tela — Davey! de 1208 ms"], achados)

    def test_o_quadro_lento_da_abertura_que_o_proprio_app_diz_tambem_sai(self):
        # A second cold start later in the file (the activity recreated): the app says so in its own `slow frame … during cold start` line.
        d = self.pasta_com_logcat(
            "10-04 10:05:30.100  5000  5001 I HWUI    : Davey! duration=750ms; Flags=0\n"
            "10-04 10:06:30.100  5000  5001 I HWUI    : Davey! duration=760ms; Flags=0\n",
            ride_extra=(linha("10:05:30.000", "ui", "slow frame: 750 ms during cold start"),))
        achados = ler_registro.do_sistema([d], ler_registro.ler([d]))
        self.assertEqual(["04/10 10:06:30: o app travou a tela — Davey! de 760 ms"], achados)

    def test_sem_o_dia_no_nome_do_arquivo_nada_e_filtrado(self):
        d = Path(tempfile.mkdtemp())
        (d / "ride-sem-dia.log").write_text(projecao(), encoding="utf-8")
        (d / "logcat-sem-dia.txt").write_text("10-04 10:00:01.000  5000  5001 I HWUI    : Davey! duration=786ms; Flags=0\n", encoding="utf-8")
        self.assertEqual([], [a for a in ler_registro.do_sistema([d], ler_registro.ler([d])) if "travou a tela" not in a])


class RegrasNovas(unittest.TestCase):
    def arquivo(self, texto):
        d = Path(tempfile.mkdtemp())
        (d / "ride-20261103-100000.log").write_text(texto, encoding="utf-8")
        return d / "ride-20261103-100000.log"

    def problemas(self, texto):
        return ler_registro.problemas(ler_registro.ler([self.arquivo(texto)]))

    def tempo(self, texto):
        return "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([self.arquivo(texto)])))

    @staticmethod
    def tela(hora, texto):
        return linha(hora, "aa-screen", f"aa-screen: {texto}")

    # 5. tela por tempo

    def test_a_tela_por_tempo_soma_mapa_menu_e_apps_dos_eventos_aa_screen(self):
        texto = projecao(
            self.tela("10:00:10.000", "nav (by the picture: a map, from unknown)"),
            self.tela("10:00:11.000", "nav confirmed by the picture (a map)"),
            self.tela("10:03:10.000", "menu (by the picture: the app menu, from nav)"),
            self.tela("10:04:10.000", "apps (a select in the menu)"),
            self.tela("10:06:10.000", "no display of Android Auto's read in 3000 ms, the picture says the screen"),
            self.tela("10:06:10.500", "nav (by the picture: a map, from apps)"))
        # 10:00:10 to 10:03:10 map (3 min), menu 1 min, apps 2 min, map again 10:06:10.5 to 10:09:00 (2 min 49.5 s).
        linha_telas = [x for x in self.tempo(texto).splitlines() if "tela por tempo" in x]
        self.assertEqual(1, len(linha_telas), linha_telas)
        self.assertIn("mapa 5 min 49 s (66%)", linha_telas[0])
        self.assertIn("apps 2 min 00 s (23%)", linha_telas[0])
        self.assertIn("menu 1 min 00 s (11%)", linha_telas[0])
        self.assertTrue(linha_telas[0].startswith("10:09:00.000"), linha_telas)

    def test_sem_eventos_aa_screen_nao_ha_linha_de_tela_por_tempo(self):
        self.assertNotIn("tela por tempo", self.tempo(BOA))

    # 6. apps por mais de 2 min com a rota ativa e sem tecla

    NAV = [linha(f"10:{3 + i:02d}:00.000", "aa", "[AA] Nav: position step=30m time=none destination=5400m") for i in range(4)]

    def apps_por_imagem(self, *meio):
        return projecao(self.tela("10:02:00.000", "nav (by the picture: a map, from unknown)"),
                        self.tela("10:03:00.000", "apps (by the picture: an app, from nav)"), *meio,
                        self.tela("10:08:00.000", "nav (by the picture: a map, from apps)"))

    def test_apps_por_imagem_com_a_rota_chegando_e_sem_tecla_alarma(self):
        p = [x for x in self.problemas(self.apps_por_imagem(*self.NAV)) if 'tela "apps"' in x]
        self.assertEqual(1, len(p), p)
        self.assertTrue(p[0].startswith("10:03:00.000: tela \"apps\" por 5 min 00 s, 5 min 00 s deles sem nenhuma tecla"), p)
        self.assertIn("4 mensagens Nav:", p[0])

    def test_uma_tecla_do_painel_divide_o_trecho_mas_a_parte_longa_ainda_alarma(self):
        tecla = linha("10:03:30.000", "dash", "button JoystickIn 0x18 -> map center once -> nothing [call=IDLE]")
        p = [x for x in self.problemas(self.apps_por_imagem(*self.NAV, tecla)) if 'tela "apps"' in x]
        self.assertEqual(1, len(p), p)
        self.assertIn("4 min 30 s deles sem nenhuma tecla", p[0])

    def test_teclas_espalhadas_que_deixam_so_trechos_curtos_nao_alarmam(self):
        teclas = [linha(h, "dash", "button NextTrack 0x09 -> media right once -> Scroll(delta=1) [call=IDLE]")
                  for h in ("10:04:00.000", "10:05:00.000", "10:06:00.000", "10:07:00.000")]
        self.assertEqual([], [x for x in self.problemas(self.apps_por_imagem(*self.NAV, *teclas)) if 'tela "apps"' in x])

    def test_o_toque_no_celular_e_entrada_e_o_aviso_do_painel_nao_e_tecla(self):
        toques = [linha(h, "aa", "[AA] touch down sent to Android Auto") for h in ("10:04:00.000", "10:05:00.000", "10:06:00.000", "10:07:00.000")]
        self.assertEqual([], [x for x in self.problemas(self.apps_por_imagem(*self.NAV, *toques)) if 'tela "apps"' in x])
        aviso = linha("10:03:30.000", "dash", "button Unknown 0x0B -> the dash's notice, ignored [call=IDLE]")
        self.assertEqual(1, len([x for x in self.problemas(self.apps_por_imagem(*self.NAV, aviso)) if 'tela "apps"' in x]))

    def test_apps_pedido_pelo_dono_ou_sem_a_rota_ou_curto_nao_alarmam(self):
        pelo_dono = projecao(self.tela("10:03:00.000", "apps (a select in the menu)"), *self.NAV)
        self.assertEqual([], [x for x in self.problemas(pelo_dono) if 'tela "apps"' in x])
        self.assertEqual([], [x for x in self.problemas(self.apps_por_imagem()) if 'tela "apps"' in x])  # no Nav: arriving
        curto = projecao(self.tela("10:03:00.000", "apps (by the picture: an app, from nav)"),
                         linha("10:04:00.000", "aa", "[AA] Nav: position step=30m time=none destination=5400m"),
                         self.tela("10:04:30.000", "nav (by the picture: a map, from apps)"))
        self.assertEqual([], [x for x in self.problemas(curto) if 'tela "apps"' in x])

    # 7. quadros-chave por segundo por ritmo

    @staticmethod
    def janelas(ritmo, n, idr, inicio=10):
        return [linha(f"10:{(inicio + 5 * i) // 60:02d}:{(inicio + 5 * i) % 60:02d}.000", "stream",
                      f"video fps target={ritmo} sent={ritmo}.0 ({ritmo * 5}frames/5.0s) composed={ritmo}.0 kbps=300 frame=8000B "
                      f"kf=1s idr={idr}(max 12000B/9pkt) sync=0")
                for i in range(n)]

    def test_os_quadros_chave_por_segundo_saem_por_ritmo_no_resumo_do_video(self):
        texto = projecao(*self.janelas(4, 4, 5), *self.janelas(6, 3, 7, inicio=40))
        video = [x for x in self.tempo(texto).splitlines() if "vídeo:" in x][0]
        self.assertIn("quadros-chave por segundo 4 fps 1,00, 6 fps 1,40", video)

    def test_a_6_fps_acima_de_1_2_por_segundo_alarma_e_ate_1_2_nao(self):
        p = [x for x in self.problemas(projecao(*self.janelas(6, 3, 7))) if "quadros-chave" in x]
        self.assertEqual(1, len(p), p)
        self.assertIn("quadros-chave a 1,40/s a 6 fps em 3 janelas", p[0])
        self.assertEqual([], [x for x in self.problemas(projecao(*self.janelas(6, 3, 6))) if "quadros-chave" in x])  # 1,2 exact

    def test_a_4_fps_ou_com_uma_janela_so_nao_alarma(self):
        self.assertEqual([], [x for x in self.problemas(projecao(*self.janelas(4, 5, 9))) if "quadros-chave a" in x])
        self.assertEqual([], [x for x in self.problemas(projecao(*self.janelas(6, 1, 9))) if "quadros-chave a" in x])

    # 8. painel visto na varredura e o app tarde para tentar

    def pedido(self, hora, n, varredura):
        return linha(hora, "wifi", f"requestNetwork 'RE_****30' everConnected=false timeout=30000ms; request #{n} (0s since the first), "
                                   f"screen interactive=true locked=true, scan of 16 networks, {varredura}, approvedBefore=true")

    def test_o_painel_visto_ha_mais_de_10_s_no_pedido_depois_de_um_sem_ele_alarma(self):
        texto = projecao(self.pedido("10:01:00.000", 1, "the dash not among them"),
                         self.pedido("10:02:08.700", 7, "the dash: 'RE_****30' -57 dBm 2422 MHz seen 11 s ago"))
        p = [x for x in self.problemas(texto) if "painel visto na varredura" in x]
        self.assertEqual(1, len(p), p)
        self.assertTrue(p[0].startswith("10:02:08.700: painel visto na varredura há 11 s e o app só pediu a rede agora (pedido #7, 69 s depois do anterior)"), p)

    def test_o_painel_visto_ha_10_s_ou_menos_ou_sem_pedido_anterior_sem_ele_nao_alarma(self):
        visto = lambda n: f"the dash: 'RE_****30' -57 dBm 2422 MHz seen {n} s ago"
        recente = projecao(self.pedido("10:01:00.000", 1, "the dash not among them"), self.pedido("10:02:00.000", 2, visto(10)))
        self.assertEqual([], [x for x in self.problemas(recente) if "painel visto na varredura" in x])
        primeiro = projecao(self.pedido("10:02:00.000", 1, visto(30)))
        self.assertEqual([], [x for x in self.problemas(primeiro) if "painel visto na varredura" in x])
        seguido = projecao(self.pedido("10:01:00.000", 1, visto(3)), self.pedido("10:02:00.000", 2, visto(25)))  # visible all along: refusing, not late
        self.assertEqual([], [x for x in self.problemas(seguido) if "painel visto na varredura" in x])


class MarcaGuardaAGravacao(unittest.TestCase):
    """Plano 22, item 2: a Marca do piloto congela uma cópia da gravação da sessão; o leitor mostra o nome, e a linha da cópia não vira outra marca."""

    GUARDADA = ("mark 2: the session recording is kept as reau_20261101_191551_marca (84312 KB in 2 parts, "
                "replacing the copy of an earlier mark in the same stretch of the ring, 1850 ms): the ring will not overwrite it")

    def tempo(self, *meio):
        d = Path(tempfile.mkdtemp())
        (d / "ride-20261103-100000.log").write_text(projecao(*meio), encoding="utf-8")
        return "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([d / "ride-20261103-100000.log"])))

    def test_a_linha_da_copia_diz_o_nome_do_par_e_nao_conta_como_marca(self):
        tempo = self.tempo(linha("10:05:00.000", "mark", "MARCA DO PILOTO 2"), linha("10:05:01.000", "mark", self.GUARDADA))
        self.assertEqual(1, tempo.count("marca do piloto 2"), tempo)
        self.assertIn("marca 2: gravação da sessão guardada como reau_20261101_191551_marca (84312 KB in 2 parts)", tempo)
        self.assertNotIn("marca do piloto reau", tempo)
        self.assertNotIn("marca do piloto 1850", tempo)

    def test_a_copia_que_nao_foi_feita_aparece_como_nao_guardada(self):
        for texto in ("mark 1: the session recording was not copied: 90000 KB to copy and the phone has less than that and 100 MB free",
                      "mark 1: the session recording could not be copied: java.io.IOException: No space left on device",
                      "mark 1: no session recording is open to keep a copy of"):
            tempo = self.tempo(linha("10:05:01.000", "mark", texto))
            self.assertIn("marca 1: gravação da sessão NÃO guardada", tempo, texto)

    def test_uma_linha_de_marca_desconhecida_passa_como_veio(self):
        tempo = self.tempo(linha("10:05:01.000", "mark", "mark: the oldest kept session recording reau_x_marca (1 KB) removed: over the ceiling of 3 copies / 250 MB"))
        self.assertIn("the oldest kept session recording", tempo)


class AvisoDeCelularQuente(unittest.TestCase):
    """Plano 22, item 4: o aviso de bateria >= 44 °C vai à linha do tempo (a etiqueta `heat`), sem virar problema nem marca."""

    def tempo(self, *meio):
        d = Path(tempfile.mkdtemp())
        (d / "ride-20261103-100000.log").write_text(projecao(*meio), encoding="utf-8")
        linhas = ler_registro.ler([d / "ride-20261103-100000.log"])
        return "\n".join(ler_registro.linha_do_tempo(linhas)), ler_registro.problemas(linhas)

    def test_o_aviso_e_o_esfriar_aparecem_na_linha_do_tempo(self):
        tempo, _ = self.tempo(
            linha("10:05:00.000", "heat", "W: battery at 44.3 C (charging=true, thermal=none): at or over 44.0 C, rider told the phone is hot"),
            linha("10:20:00.000", "heat", "battery back under 41.0 C (40.8 C): a new warning is possible"),
        )
        self.assertIn("aviso de celular quente: bateria a 44.3 °C, piloto avisado", tempo)
        self.assertIn("bateria esfriou (40.8 °C, abaixo de 41.0 °C): novo aviso possível", tempo)

    def test_nao_conta_como_marca_do_piloto(self):
        tempo, _ = self.tempo(linha("10:05:00.000", "heat", "W: battery at 45.0 C (charging=false, thermal=none): at or over 44.0 C, rider told the phone is hot"))
        self.assertNotIn("marca do piloto", tempo)


ETAPAS_ANTIGA = ("video fps target=4 sent=3.9 (20frames/5.0s) composed=3.9 kbps=300 set=400k frame=9000B source=152 drawn=20 repeated=0 compose=3.4ms "
                 "encode=14.0ms late=0 tickwait=100/200/250ms full=true thermal=OK kf=1s idr=5(max 14000B/12pkt) sync=0 auto=4fps [at ceiling]")
ETAPAS_NOVA = (ETAPAS_ANTIGA + " compose_max={cmax}ms compose_p95={cp95}ms encode_max={emax}ms encode_p95={ep95}ms look={look} latch={latch} wake={wake}")


class EtapasDoVideo(unittest.TestCase):
    """Plano 23, item 4: os campos do fim da linha `video fps` (pior quadro e p95 de composição e codificação, look, updateTexImage, acordares)
    saem no resumo `vídeo:`; a linha antiga continua lida como sempre."""

    def video(self, *textos):
        d = Path(tempfile.mkdtemp())
        (d / "ride-20261103-100000.log").write_text(projecao(*[
            linha(f"10:00:{10 + 5 * i:02d}.000", "stream", t) for i, t in enumerate(textos)]), encoding="utf-8")
        linhas = ler_registro.ler([d / "ride-20261103-100000.log"])
        return "\n".join(ler_registro.linha_do_tempo(linhas))

    def nova(self, **campos):
        padrao = dict(cmax="7.6", cp95="5.0", emax="19.2", ep95="17.0", look="3/0.90/1.40ms", latch="152/0.20/0.90ms", wake="152(30.4/s)")
        return ETAPAS_NOVA.format(**{**padrao, **campos})

    def test_o_pior_quadro_e_o_pior_p95_de_composicao_e_codificacao(self):
        texto = self.video(self.nova(), self.nova(cmax="9.1", cp95="4.0", emax="15.0", ep95="18.5"))
        self.assertIn("composição pior quadro 9,1 ms (pior p95 5,0)", texto)
        self.assertIn("codificação pior quadro 19,2 ms (pior p95 18,5)", texto)

    def test_o_look_e_o_latch_somam_as_vezes_e_pesam_a_media(self):
        texto = self.video(self.nova(look="1/2.00/2.00ms", latch="100/0.10/0.50ms"), self.nova(look="3/1.00/3.50ms", latch="300/0.30/0.90ms"))
        self.assertIn("leitura da imagem (look) 4 vezes, média 1,25 ms (máx 3,50)", texto)
        self.assertIn("updateTexImage 400 vezes, média 0,25 ms (máx 0,90)", texto)

    def test_as_janelas_sem_look_nao_entram_na_media_mas_o_campo_zero_e_dito(self):
        texto = self.video(self.nova(look="0"), self.nova(look="2/1.00/1.00ms"))
        self.assertIn("leitura da imagem (look) 2 vezes, média 1,00 ms (máx 1,00)", texto)
        self.assertIn("leitura da imagem (look) nenhuma vez", self.video(self.nova(look="0")))

    def test_os_acordares_por_segundo_contra_os_desenhos(self):
        # 152 acordares e 20 desenhados em 5,0 s: 30,4 contra 4,0, 7,6 acordares por desenho.
        self.assertIn("30,4 acordares/s do compositor contra 4,0 desenhos/s (7,6 por desenho)", self.video(self.nova(), self.nova()))

    def test_a_linha_antiga_nao_diz_nada_das_etapas_e_o_resto_do_resumo_segue(self):
        texto = self.video(ETAPAS_ANTIGA, ETAPAS_ANTIGA)
        for palavra in ("pior quadro", "look", "updateTexImage", "acordares"):
            self.assertNotIn(palavra, texto)
        self.assertIn("espera pelo tick média 100 ms (pior p95 200, máx 250)", texto)
        self.assertIn("quadros-chave 10", texto)

    def test_o_colchete_de_depois_do_corte_da_escada_e_lido_como_o_antigo(self):
        # 14/11/2026 (plano 27, passo G1): o texto depois de `auto=4fps` deixou de ser o estado da escada de fps (`at ceiling`, `healthy 2/3`...) e passou a
        # dizer só o que a janela mostrou: `ok`, `not judged` ou `short: ...`. O leitor não lê o colchete: o resumo é o mesmo com os dois.
        novos = ("ok", "not judged", "short: sent 3.1", "short: sent 3.1, kbps 1200, thermal")
        for antiga in (ETAPAS_ANTIGA, self.nova()):
            self.assertIn("auto=4fps [at ceiling]", antiga)
            depois = [antiga.replace("[at ceiling]", f"[{c}]") for c in novos]
            self.assertEqual(self.video(*[antiga] * len(novos)), self.video(*depois))
        self.assertIn("quadros-chave 20", self.video(*[self.nova().replace("[at ceiling]", f"[{c}]") for c in novos]))

    def test_uma_janela_sem_quadro_medido_traz_traco_e_nao_derruba_o_resumo(self):
        texto = self.video(self.nova(cmax="-", cp95="-", emax="-", ep95="-").replace("compose_max=-ms", "compose_max=-").replace("compose_p95=-ms", "compose_p95=-")
                           .replace("encode_max=-ms", "encode_max=-").replace("encode_p95=-ms", "encode_p95=-"))
        self.assertNotIn("pior quadro", texto)
        self.assertIn("vídeo:", texto)


# Written together at run time: a fixture with a whole hardware address would look like personal data to the privacy guard.
MAC_A = ":".join(["aa", "bb", "cc", "dd", "ee", "ff"])
MAC_B = ":".join(["11", "22", "33", "44", "55", "66"])
LOGCAT_WIFI = (
    f"10-04 10:04:50.000  900  900 I wpa_supplicant: wlan0: CTRL-EVENT-SIGNAL-CHANGE above=1 signal=-60\n"
    f"10-04 10:04:57.800  900  900 I wpa_supplicant: wlan0: CTRL-EVENT-DISCONNECTED bssid={MAC_A} reason=3 locally_generated=1\n"
    f"10-04 10:04:57.900 1337 1400 D WifiClientModeImpl: handleNetworkDisconnect: Disconnecting from \"RE_AB12_345678\" bssid {MAC_A}\n"
    f"10-04 10:04:58.100 1337 1400 I ConnectivityService: Network NetworkAgentInfo{{network{{454}} lost RE_AB12_345678 home wifi visible only here}}\n"
    # Another network's line, an event of no use and a line far from the drop: none of them is told.
    f"10-04 10:04:58.200 1337 1400 I ConnectivityService: Network NetworkAgentInfo{{network{{12}} lost HomeWifi}}\n"
    f"10-04 10:04:58.300  900  900 I wpa_supplicant: wlan0: Trying to associate with SSID 'HomeWifi'\n"
    f"10-04 10:09:00.000  900  900 I wpa_supplicant: wlan0: CTRL-EVENT-DISCONNECTED bssid={MAC_B} reason=1\n"
)


class WifiNasQuedas(unittest.TestCase):
    def pasta(self, logcat):
        d = Path(tempfile.mkdtemp())
        (d / "ride-20261004-100000.log").write_text(
            projecao(linha("10:05:00.000", "wifi", "W: link lost — reconnecting in 4000ms (after 59 s on 2447 MHz; rssi -42)")), encoding="utf-8")
        (d / "logcat-sistema.txt").write_text(logcat, encoding="utf-8")
        return d

    def achados(self, logcat):
        d = self.pasta(logcat)
        return [a for a in ler_registro.do_sistema([d], ler_registro.ler([d])) if "o Wi-Fi do painel caiu" in a]

    def test_o_que_o_sistema_disse_em_volta_da_queda_vem_sem_o_bssid_nem_o_nome_do_painel(self):
        achados = self.achados(LOGCAT_WIFI)
        self.assertEqual(1, len(achados), achados)
        texto = achados[0]
        self.assertTrue(texto.startswith("10:05:00: o Wi-Fi do painel caiu — o sistema disse: "), texto)
        self.assertIn("wpa_supplicant CTRL-EVENT-DISCONNECTED reason=3 locally_generated=1", texto)
        self.assertIn("WifiClientModeImpl handleNetworkDisconnect", texto)
        self.assertIn("ConnectivityService Network NetworkAgentInfo", texto)
        for segredo in (MAC_A[:8], "AB12_345678", "HomeWifi", MAC_B[:8], "signal=-60"):
            self.assertNotIn(segredo, texto)

    def test_sem_nenhuma_linha_de_wifi_em_volta_da_queda_nao_diz_nada(self):
        self.assertEqual([], self.achados("10-04 10:05:00.000  900  900 I wpa_supplicant: wlan0: CTRL-EVENT-SIGNAL-CHANGE above=1\n"))
        self.assertEqual([], self.achados("10-04 11:30:00.000  900  900 I wpa_supplicant: wlan0: CTRL-EVENT-DISCONNECTED reason=3\n"))

    def test_so_oito_linhas_por_queda_e_diz_quantas_mais_havia(self):
        muitas = "".join(f"10-04 10:04:{40 + i:02d}.000  900  900 I wpa_supplicant: wlan0: CTRL-EVENT-DISCONNECTED reason={i}\n" for i in range(12))
        texto = self.achados(muitas)[0]
        self.assertEqual(8, texto.count("CTRL-EVENT-DISCONNECTED"))
        self.assertIn("(+4 linhas)", texto)

    def test_sem_queda_no_registro_o_logcat_do_wifi_nao_e_contado(self):
        d = Path(tempfile.mkdtemp())
        (d / "ride-20261004-100000.log").write_text(projecao(linha("10:05:00.000", "aa", "[AA] AapTransport stopping and sending byebye")), encoding="utf-8")
        (d / "logcat-sistema.txt").write_text(LOGCAT_WIFI, encoding="utf-8")
        self.assertEqual([], [a for a in ler_registro.do_sistema([d], ler_registro.ler([d])) if "Wi-Fi do painel" in a])


# ---------------------------------------------------------------------------------------------------------------------------------------
# 03/11/2026: as lacunas do leitor que a análise da corrida de 03/11 achou.
# ---------------------------------------------------------------------------------------------------------------------------------------

def escreve(pasta, nome, texto):
    destino = Path(pasta) / nome
    destino.parent.mkdir(parents=True, exist_ok=True)
    destino.write_text(texto, encoding="utf-8")
    return destino


class SemDuplicataDoLogcat(unittest.TestCase):
    """Lacuna 1: a coleta guarda `logcat-sistema.txt` e `logcat-sistema.mascarado.txt`; o mesmo FATAL do Android Auto contava em dobro."""

    def coleta(self, *nomes):
        raiz = Path(tempfile.mkdtemp())
        escreve(raiz, "diag/ride-20261004-100000.log", projecao(linha("10:05:00.000", "aa", "[AA] AapTransport stopping and sending byebye")))
        for nome in nomes:
            escreve(raiz, nome, LOGCAT_ANDROID_AUTO)
        return raiz

    def travadas(self, raiz):
        diag = raiz / "diag"
        return [a for a in ler_registro.do_sistema([diag], ler_registro.ler([diag])) if "travou" in a]

    def test_o_nao_mascarado_e_o_mascarado_juntos_contam_o_evento_uma_vez(self):
        raiz = self.coleta("logcat-sistema.txt", "logcat-sistema.mascarado.txt")
        self.assertEqual(1, len(self.travadas(raiz)), self.travadas(raiz))

    def test_so_o_mascarado_e_lido_quando_nao_ha_o_outro(self):
        raiz = self.coleta("logcat-sistema.mascarado.txt")
        self.assertEqual(1, len(self.travadas(raiz)))

    def test_so_o_nao_mascarado_tambem(self):
        self.assertEqual(1, len(self.travadas(self.coleta("logcat-sistema.txt"))))

    def test_o_nao_mascarado_e_o_preferido(self):
        raiz = Path(tempfile.mkdtemp())
        escreve(raiz, "logcat-sistema.txt", "x")
        escreve(raiz, "logcat-sistema.mascarado.txt", "y")
        self.assertEqual(["logcat-sistema.txt"], [a.name for a in ler_registro.logcat_sistema.arquivos([raiz])])

    def test_a_pasta_e_o_arquivo_dados_juntos_nao_leem_o_mesmo_arquivo_duas_vezes(self):
        raiz = self.coleta("logcat-sistema.txt", "logcat-sistema.mascarado.txt")
        achados = ler_registro.logcat_sistema.arquivos([raiz / "diag", raiz, raiz / "logcat-sistema.txt", raiz / "logcat-sistema.mascarado.txt"])
        self.assertEqual(["logcat-sistema.txt"], [a.name for a in achados])

    def test_logcat_com_outro_nome_base_continua_lido_junto(self):
        raiz = Path(tempfile.mkdtemp())
        for nome in ("logcat-sistema.txt", "logcat-sistema.mascarado.txt", "logcat-sistema-antigo.mascarado.txt"):
            escreve(raiz, nome, "x")
        self.assertEqual(["logcat-sistema-antigo.mascarado.txt", "logcat-sistema.txt"], sorted(a.name for a in ler_registro.logcat_sistema.arquivos([raiz])))


DUMPSYS_BASE = (
    "Algo antes\n"
    "WifiMetrics:\n"
    "mConnectionEvents:\n"
    # a union the REau asked for, while it ran (10:00:03)
    'startTime=10-4 10:00:2.500, SSID="RE_AB12_345678", BSSID=segredo-bssid-01, durationMillis=700, roamType=ROAM_UNRELATED, connectionResult=1, level2FailureCode=NONE, '
    "signalStrength=-47, wifiState=WIFI_DISCONNECTED, screenOn=true, mChannelInfo=2422, connectionNominator=NOMINATOR_SPECIFIER, networkCreator=CREATOR_USER, uid=10492\n"
    # the home network: never told, name nor address
    'startTime=10-4 12:10:1.000, SSID="CasaDoDono_5G", BSSID=segredo-bssid-02, durationMillis=424, connectionResult=1, signalStrength=-60, mChannelInfo=5745, '
    "connectionNominator=NOMINATOR_SAVED, uid=1010\n"
    # a union by the system's suggestion, with the REau closed (12:42)
    'startTime=10-4 12:42:25.764, SSID="RE_AB12_345678", BSSID=segredo-bssid-01, durationMillis=680, connectionResult=1, signalStrength=-54, mChannelInfo=2452, '
    "connectionNominator=NOMINATOR_SUGGESTION, networkCreator=CREATOR_UNKNOWN, uid=1010\n"
    # a failed attempt with the REau closed (13:30)
    'startTime=10-4 13:30:0.8, SSID="RE_AB12_345678", BSSID=segredo-bssid-01, durationMillis=33847, connectionResult=0, signalStrength=0, mChannelInfo=0, '
    "connectionNominator=NOMINATOR_SPECIFIER, uid=10492\n"
    "mWifiLogProto.numSavedNetworks=112\n"
    "StaEventList:\n"
    "10-04 10:05:00.168 NETWORK_DISCONNECTION_EVENT local_gen=false reason=3:DEAUTH_LEAVING lastRssi=-59 lastFreq=2422 lastLinkSpeed=72 screenOn=true\n"
    "10-04 12:46:15.118 NETWORK_DISCONNECTION_EVENT local_gen=false reason=-1:UNSPECIFIED lastRssi=-52 lastFreq=2452 screenOn=false\n"
    "10-04 12:10:05.000 NETWORK_DISCONNECTION_EVENT local_gen=true reason=3:DEAUTH_LEAVING screenOn=true\n"
    "10-04 12:00:00.000 CMD_START_CONNECT screenOn=true\n"
    "mLinkProbeStaEventCount:0\n"
)


class DumpsysDoWifi(unittest.TestCase):
    """Lacuna 2: `sistema/dumpsys-wifi.txt` (motivo da queda e uniões ao painel que o REau não viu), sem BSSID nem nome de rede."""

    QUEDA = linha("10:05:00.231", "wifi", "W: link lost — reconnecting in 4000ms (after 632 s on 2422 MHz; rssi -61)")

    def coleta(self, dumpsys=DUMPSYS_BASE, nome="sistema/dumpsys-wifi.txt", ride=None):
        raiz = Path(tempfile.mkdtemp())
        escreve(raiz, "diag/ride-20261004-100000.log", ride or projecao(self.QUEDA))
        if dumpsys is not None:
            escreve(raiz, nome, dumpsys)
        return raiz

    def achados(self, raiz, entrada="diag"):
        caminho = raiz / entrada if entrada else raiz
        return ler_registro.do_dumpsys_wifi([caminho], ler_registro.ler([raiz / "diag"]))

    def test_cada_queda_do_app_traz_o_motivo_do_evento_mais_perto_do_sistema(self):
        queda = [a for a in self.achados(self.coleta()) if a.startswith("10:05:00")]
        self.assertEqual(1, len(queda), queda)
        for esperado in ("10:05:00.168", "63 ms antes da linha do app", "local_gen=false (quem derrubou foi o outro lado, o painel)",
                         "reason=3:DEAUTH_LEAVING (o ponto de acesso avisou que estava saindo)", "-59 dBm", "2422 MHz"):
            self.assertIn(esperado, queda[0])

    def test_a_queda_que_o_proprio_celular_gerou_diz_isso(self):
        dump = DUMPSYS_BASE.replace("local_gen=false reason=3:DEAUTH_LEAVING lastRssi=-59", "local_gen=true reason=3:DEAUTH_LEAVING lastRssi=-59")
        queda = [a for a in self.achados(self.coleta(dump)) if a.startswith("10:05:00")]
        self.assertIn("local_gen=true (o celular derrubou)", queda[0])

    def test_o_evento_do_sistema_depois_da_linha_do_app_tambem_vale(self):
        dump = DUMPSYS_BASE.replace("10:05:00.168", "10:05:02.231")
        queda = [a for a in self.achados(self.coleta(dump)) if a.startswith("10:05:00")]
        self.assertIn("2000 ms depois da linha do app", queda[0])

    def test_a_queda_sem_evento_a_10_s_diz_que_o_dumpsys_nao_traz(self):
        dump = DUMPSYS_BASE.replace("10:05:00.168", "10:30:00.168")
        queda = [a for a in self.achados(self.coleta(dump)) if a.startswith("10:05:00")]
        self.assertEqual(1, len(queda))
        self.assertIn("não traz desconexão", queda[0])

    def test_a_uniao_sem_nenhum_registro_do_reau_sai_com_quem_pediu_e_quando_saiu(self):
        unioes = [a for a in self.achados(self.coleta()) if "sem nenhum registro do REau" in a]
        sugestao = [a for a in unioes if a.startswith("04/10 12:42:25")]
        self.assertEqual(1, len(sugestao), unioes)
        for esperado in ("sugestão de rede do sistema", "680 ms para unir", "-54 dBm", "2452 MHz", "saiu às 12:46:15", "3 min 49 s", "reason=-1:UNSPECIFIED"):
            self.assertIn(esperado, sugestao[0])

    def test_a_tentativa_que_falhou_sem_registro_do_reau_tambem_sai(self):
        falhou = [a for a in self.achados(self.coleta()) if a.startswith("04/10 13:30:00")]
        self.assertEqual(1, len(falhou))
        self.assertIn("não conseguiu", falhou[0])
        self.assertIn("34 s", falhou[0])

    def test_a_uniao_que_o_reau_pediu_e_viu_nao_e_uniao_sem_registro(self):
        # The union of 10:00:02 falls inside the registro (10:00:00 to 10:09:00), and the home network's is not the dash's.
        unioes = [a for a in self.achados(self.coleta()) if "sem nenhum registro do REau" in a]
        self.assertFalse(any("10:00:0" in a for a in unioes), unioes)
        self.assertEqual(2, len(unioes), unioes)

    def test_nem_o_bssid_nem_o_nome_de_rede_saem(self):
        texto = "\n".join(self.achados(self.coleta()))
        for segredo in ("segredo-bssid", "CasaDoDono", "RE_AB12", "345678", "SSID", "BSSID"):
            self.assertNotIn(segredo, texto)

    def test_so_os_dias_dos_registros_lidos_entram(self):
        dump = DUMPSYS_BASE.replace("10-4 12:42:25.764", "10-1 12:42:25.764")
        self.assertEqual([], [a for a in self.achados(self.coleta(dump)) if a.startswith("01/10")])

    def test_o_nao_mascarado_e_lido_e_o_mascarado_so_quando_falta_o_outro(self):
        raiz = self.coleta()
        escreve(raiz, "sistema/dumpsys-wifi.mascarado.txt", "nada\n")
        self.assertTrue(any("sem nenhum registro do REau" in a for a in self.achados(raiz)))
        so_mascarado = self.coleta(DUMPSYS_BASE, "sistema/dumpsys-wifi.mascarado.txt")
        self.assertTrue(any("sem nenhum registro do REau" in a for a in self.achados(so_mascarado)))

    def test_o_nao_mascarado_vence_mesmo_em_outra_pasta(self):
        raiz = self.coleta(DUMPSYS_BASE, "sistema/dumpsys-wifi.txt")
        escreve(raiz, "dumpsys-wifi.mascarado.txt", DUMPSYS_BASE.replace("NOMINATOR_SUGGESTION", "NOMINATOR_OUTRO"))  # first in the search order, but masked
        sugestao = [a for a in self.achados(raiz) if a.startswith("04/10 12:42:25")]
        self.assertIn("sugestão de rede do sistema", sugestao[0])

    def test_a_desconexao_durante_a_propria_uniao_nao_e_a_saida_dela(self):
        # The network the phone was leaving goes 300 ms after the union began (while it connects): the union's exit is the next one.
        dump = DUMPSYS_BASE.replace("10-04 12:46:15.118", "10-04 12:42:26.064 NETWORK_DISCONNECTION_EVENT local_gen=true reason=3:DEAUTH_LEAVING\n10-04 12:46:15.118")
        sugestao = [a for a in self.achados(self.coleta(dump)) if a.startswith("04/10 12:42:25")]
        self.assertIn("saiu às 12:46:15", sugestao[0])

    def test_os_eventos_fora_de_ordem_no_dumpsys_sao_lidos_em_ordem_de_hora(self):
        dump = DUMPSYS_BASE.replace("10-04 12:46:15.118", "10-04 12:46:15.118 NETWORK_DISCONNECTION_EVENT local_gen=false reason=-1:UNSPECIFIED\n"
                                                           "10-04 12:44:00.000")
        sugestao = [a for a in self.achados(self.coleta(dump)) if a.startswith("04/10 12:42:25")]
        self.assertIn("saiu às 12:44:00", sugestao[0])

    def test_a_pasta_da_coleta_a_diag_a_pasta_sistema_e_o_arquivo_servem(self):
        raiz = self.coleta()
        for entrada in ("diag", "", "sistema", "sistema/dumpsys-wifi.txt"):
            self.assertTrue(any("sem nenhum registro do REau" in a for a in self.achados(raiz, entrada)), entrada)

    def test_sem_dumpsys_nao_diz_nada(self):
        self.assertEqual([], self.achados(self.coleta(None)))

    def test_o_dumpsys_acaba_antes_de_a_uniao_sair_e_o_texto_diz(self):
        dump = DUMPSYS_BASE.replace("10-04 12:46:15.118", "10-04 12:30:00.000")
        sugestao = [a for a in self.achados(self.coleta(dump)) if a.startswith("04/10 12:42:25")]
        self.assertIn("o dumpsys não mostra quando saiu", sugestao[0])

    def test_entra_nos_problemas_da_saida_do_programa(self):
        raiz = self.coleta()
        feito = subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), str(raiz / "diag")], capture_output=True, text=True, encoding="utf-8")
        self.assertIn("sem nenhum registro do REau", feito.stdout)
        self.assertEqual(1, feito.returncode)


class VersaoDoApp(unittest.TestCase):
    """Lacuna 3: a versão do app de cada corrida (da linha `process start`)."""

    COM_VERSAO = "10:00:00.000  +     0ms  ==== process start: app — app 0.3.0-dev+26.11.03-03.30 (29894790, 7eee9cb), motorola motorola edge 30, Android 14 (API 34) ====\n"

    def linhas(self, *textos):
        d = Path(tempfile.mkdtemp())
        for i, t in enumerate(textos):
            escreve(d, f"ride-2026100{i + 1}-100000.log", t)
        return ler_registro.ler([d])

    def test_a_projecao_diz_a_versao_do_app_que_a_abriu(self):
        linhas = self.linhas(projecao().replace(CABECALHO, self.COM_VERSAO))
        tempo = ler_registro.linha_do_tempo(linhas)
        self.assertTrue(any(x.endswith("versão do app: 0.3.0-dev+26.11.03-03.30 (29894790, 7eee9cb)") for x in tempo), tempo)

    def test_cada_abertura_do_app_sai_no_cabecalho_por_versao(self):
        linhas = self.linhas(self.COM_VERSAO, self.COM_VERSAO, self.COM_VERSAO.replace("03.30", "09.28"))
        v = ler_registro.versoes_do_app(linhas)
        self.assertEqual(2, len(v), v)
        self.assertIn("app 0.3.0-dev+26.11.03-03.30 (29894790, 7eee9cb): 2 aberturas (ride-20261001-100000.log, ride-20261002-100000.log)", v)
        self.assertIn("1 abertura (ride-20261003-100000.log)", v[1])

    def test_registro_antigo_sem_versao_nao_inventa_uma(self):
        linhas = self.linhas(projecao())  # "process start: app — Google Pixel, Android 16 (API 36)"
        self.assertEqual([], ler_registro.versoes_do_app(linhas))
        self.assertFalse(any("versão do app" in x for x in ler_registro.linha_do_tempo(linhas)))

    def test_uma_versao_so_com_o_hash_entre_parenteses_e_sem_ele(self):
        linhas = self.linhas(self.COM_VERSAO.replace(" (29894790, 7eee9cb)", ""))
        self.assertEqual(["app 0.3.0-dev+26.11.03-03.30: 1 abertura (ride-20261001-100000.log)"], ler_registro.versoes_do_app(linhas))

    def test_registros_de_duas_versoes_viram_aviso_da_leitura(self):
        d = Path(tempfile.mkdtemp())
        escreve(d, "ride-20261001-100000.log", self.COM_VERSAO)
        escreve(d, "ride-20261002-100000.log", self.COM_VERSAO.replace("03.30", "09.28"))
        avisos = ler_registro.avisos_da_leitura([d], ler_registro.ler([d]))
        self.assertTrue(any("2 versões do app" in a for a in avisos), avisos)
        d2 = Path(tempfile.mkdtemp())
        escreve(d2, "ride-20261001-100000.log", self.COM_VERSAO)
        self.assertEqual([], ler_registro.avisos_da_leitura([d2], ler_registro.ler([d2])))


class VideoPorRitmo(unittest.TestCase):
    """Lacuna 4: `idr=` por janela e `kbps` médio e máximo, cada ritmo (2, 4, 6 fps) à parte."""

    @staticmethod
    def janela(hora, fps, kbps, idr, frames=20):
        return linha(hora, "stream", f"video fps target={fps} sent={fps}.0 ({frames}frames/5.1s) composed={fps}.0 kbps={kbps} set=400k frame=5000B full=true "
                                     f"kf=1s idr={idr}(max 9000B/8pkt) sync=5[pace:5]")

    def bloco(self, *janelas):
        return ler_registro.projecoes(ler_registro.ler([self._arquivo(projecao(*janelas))]))[0]

    def _arquivo(self, texto):
        d = Path(tempfile.mkdtemp())
        return escreve(d, "ride-20261004-100000.log", texto)

    def test_cada_ritmo_tem_o_seu_idr_por_janela_e_o_seu_kbps(self):
        bloco = self.bloco(self.janela("10:01:00.000", 2, 80, 4), self.janela("10:01:05.000", 2, 100, 6), self.janela("10:01:10.000", 4, 250, 5),
                           self.janela("10:01:15.000", 4, 333, 5), self.janela("10:01:20.000", 4, 200, 2), self.janela("10:01:25.000", 6, 390, 7))
        texto = ler_registro._video_por_ritmo(bloco)
        self.assertEqual("vídeo por ritmo: 2 fps (2 janelas) idr/janela 5,00 (4 a 6), kbps médio 90, máx 100; "
                         "4 fps (3 janelas) idr/janela 4,00 (2 a 5), kbps médio 261, máx 333; "
                         "6 fps (1 janela) idr/janela 7,00 (7 a 7), kbps médio 390, máx 390", texto)

    def test_sai_na_linha_do_tempo_ao_fim_da_projecao_logo_depois_de_video(self):
        linhas = ler_registro.ler([self._arquivo(projecao(self.janela("10:01:00.000", 4, 250, 5)))])
        tempo = ler_registro.linha_do_tempo(linhas)
        self.assertTrue(any("vídeo por ritmo: 4 fps (1 janela) idr/janela 5,00 (5 a 5), kbps médio 250, máx 250" in x for x in tempo), tempo)

    def test_janela_sem_idr_conta_o_kbps_e_nao_o_idr(self):
        bloco = self.bloco(linha("10:01:00.000", "stream", "video fps target=16 sent=15.9 (80frames/5.0s) composed=16.0 kbps=900"))
        self.assertEqual("vídeo por ritmo: 16 fps (1 janela) kbps médio 900, máx 900", ler_registro._video_por_ritmo(bloco))

    def test_sem_janelas_nao_diz_nada(self):
        self.assertEqual("", ler_registro._video_por_ritmo(self.bloco(linha("10:01:00.000", "aa", "[AA] algo"))))


class AppsProvisorioDesmentido(unittest.TestCase):
    """Lacuna 5: `aa-screen: apps (a select in the menu)` desmentido pela imagem logo depois."""

    def tela(self, hora, texto):
        return linha(hora, "aa-screen", f"aa-screen: {texto}")

    def problemas(self, *eventos):
        d = Path(tempfile.mkdtemp())
        escreve(d, "ride-20261004-100000.log", projecao(*eventos))
        return [p for p in ler_registro.avisos_da_leitura([d], ler_registro.ler([d])) if "provisório" in p]

    def test_e_informativo_fica_nos_avisos_da_leitura_e_nao_nos_problemas(self):
        d = Path(tempfile.mkdtemp())
        escreve(d, "ride-20261004-100000.log", projecao(self.tela("10:01:01.000", self.SELECT), self.tela("10:01:02.000", "menu (by the picture: the app menu, from apps)")))
        linhas = ler_registro.ler([d])
        self.assertEqual([], [p for p in ler_registro.problemas(linhas) if "provisório" in p])
        feito = subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), str(d)], capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(0, feito.returncode)  # sem problema: o código de saída não muda
        self.assertIn("nenhum problema conhecido", feito.stdout)
        self.assertIn("AVISOS DA LEITURA", feito.stdout)
        self.assertIn("1 de 1 vezes", feito.stdout)

    SELECT = "apps (a select in the menu)"

    def test_o_menu_dito_pela_imagem_logo_depois_desmente_o_provisorio(self):
        p = self.problemas(self.tela("10:01:00.000", "menu (by the picture: the app menu, from nav)"), self.tela("10:01:01.000", self.SELECT),
                           self.tela("10:01:02.082", "menu (by the picture: the app menu, from apps)"))
        self.assertEqual(1, len(p), p)
        for esperado in ("1 de 1 vezes", "10:01:01,0 por 1,1 s", "ainda na tela"):
            self.assertIn(esperado, p[0])

    def test_conta_e_lista_cada_vez_com_a_duracao(self):
        p = self.problemas(self.tela("10:01:01.000", self.SELECT), self.tela("10:01:02.000", "menu (by the picture: the app menu, from apps)"),
                           self.tela("10:02:00.000", self.SELECT), self.tela("10:02:00.360", "nav (by the picture: a map, from apps)"),
                           self.tela("10:03:00.000", self.SELECT), self.tela("10:03:01.500", "menu (by the picture: the app menu, from apps)"))
        self.assertEqual(1, len(p))
        self.assertIn("2 de 3 vezes (10:01:01,0 por 1,0 s; 10:03:00,0 por 1,5 s)", p[0])

    def test_a_selecao_que_a_imagem_confirma_ou_leva_ao_mapa_nao_e_desmentida(self):
        self.assertEqual([], self.problemas(self.tela("10:01:00.000", self.SELECT), self.tela("10:01:00.360", "nav (by the picture: a map, from apps)")))
        self.assertEqual([], self.problemas(self.tela("10:01:00.000", self.SELECT), self.tela("10:01:00.400", "apps confirmed by the picture (apps)")))
        self.assertEqual([], self.problemas(self.tela("10:01:00.000", self.SELECT)))

    def test_o_menu_muito_depois_nao_e_o_desmentido_do_provisorio(self):
        self.assertEqual([], self.problemas(self.tela("10:01:00.000", self.SELECT), self.tela("10:01:20.000", "menu (by the picture: the app menu, from apps)")))

    def test_o_menu_que_nao_vem_de_apps_nao_conta(self):
        self.assertEqual([], self.problemas(self.tela("10:01:00.000", self.SELECT), self.tela("10:01:01.000", "menu (by the picture: the app menu, from nav)")))


class PainelVistoPedidoUnido(unittest.TestCase):
    """Lacuna 6: painel visto → pedido → unido, medido pelo `seen N s ago` da varredura; alarme com o painel visto há mais de 10 s e o pedido sem sair."""

    @staticmethod
    def pedido(hora, n, varredura):
        return linha(hora, "wifi", f"requestNetwork 'RE_****30' everConnected=false timeout=30000ms; request #{n} (0s since the first), "
                                   f"screen interactive=true locked=false, scan of 15 networks, {varredura}, approvedBefore=false")

    PROCURA = staticmethod(lambda hora: linha(hora, "wifi", "scan lookup for 'RE***' → not found"))
    UNIDO = staticmethod(lambda hora: linha(hora, "wifi", "connected 'RE_****30' in 3840ms"))

    def visto(self, n):
        return f"the dash: 'RE_****30' -47 dBm 2422 MHz seen {n} s ago"

    def arquivo(self, *eventos):
        d = Path(tempfile.mkdtemp())
        escreve(d, "ride-20261004-100000.log", projecao(*eventos))
        return ler_registro.ler([d])

    def test_a_olhada_da_espera_sem_ver_o_painel_e_o_pedido_com_ele_visto_ha_13_s_alarmam(self):
        linhas = self.arquivo(self.PROCURA("10:01:33.152"), self.pedido("10:01:59.846", 1, self.visto(13)), self.UNIDO("10:02:03.690"))
        p = [x for x in ler_registro.problemas(linhas) if "painel visto na varredura" in x]
        self.assertEqual(1, len(p), p)
        self.assertTrue(p[0].startswith("10:01:59.846: painel visto na varredura há 13 s e o app só pediu a rede agora (pedido #1, 27 s depois da última olhada sem vê-lo)"), p)
        self.assertIn("visto→pedido 13 s, pedido→unido 3,8 s", p[0])

    def test_a_linha_do_tempo_mede_visto_pedido_e_unido_pelo_seen(self):
        linhas = self.arquivo(self.PROCURA("10:01:33.152"), self.pedido("10:01:59.846", 1, self.visto(13)), self.UNIDO("10:02:03.690"))
        tempo = [x for x in ler_registro.linha_do_tempo(linhas) if "painel visto →" in x]
        self.assertEqual(1, len(tempo), tempo)
        self.assertIn("visto há 13 s no pedido #1 (cerca de 10:01:46,8), pedido 10:01:59,8, unido 10:02:03,7 (pedido→unido 3,8 s; visto→unido 16,8 s)", tempo[0])

    def test_pedido_sem_unir_ate_o_fim_diz_isso(self):
        linhas = self.arquivo(self.PROCURA("10:01:33.152"), self.pedido("10:01:59.846", 1, self.visto(13)))
        tempo = [x for x in ler_registro.linha_do_tempo(linhas) if "painel visto →" in x]
        self.assertIn("sem unir até o fim da projeção", tempo[0])
        p = [x for x in ler_registro.problemas(linhas) if "painel visto na varredura" in x]
        self.assertTrue(p and "pedido→unido" not in p[0], p)

    def test_visto_ha_10_s_ou_menos_nao_alarma_mas_a_linha_do_tempo_mede(self):
        linhas = self.arquivo(self.PROCURA("10:01:33.152"), self.pedido("10:01:40.000", 1, self.visto(10)), self.UNIDO("10:01:44.000"))
        self.assertEqual([], [x for x in ler_registro.problemas(linhas) if "painel visto na varredura" in x])
        self.assertTrue(any("painel visto →" in x for x in ler_registro.linha_do_tempo(linhas)))

    def test_primeiro_pedido_sem_olhada_anterior_que_nao_o_viu_nao_alarma(self):
        linhas = self.arquivo(self.pedido("10:01:59.846", 1, self.visto(30)), self.UNIDO("10:02:03.690"))
        self.assertEqual([], [x for x in ler_registro.problemas(linhas) if "painel visto na varredura" in x])

    def test_a_olhada_que_ja_via_o_painel_antes_e_recusa_nao_e_atraso(self):
        linhas = self.arquivo(self.PROCURA("10:01:00.000"), self.pedido("10:01:10.000", 1, self.visto(3)), self.pedido("10:02:00.000", 2, self.visto(25)))
        self.assertEqual([], [x for x in ler_registro.problemas(linhas) if "painel visto na varredura" in x])

    def test_um_connected_no_meio_zera_a_conta(self):
        linhas = self.arquivo(self.PROCURA("10:01:00.000"), self.UNIDO("10:01:10.000"), self.pedido("10:02:00.000", 2, self.visto(25)))
        self.assertEqual([], [x for x in ler_registro.problemas(linhas) if "painel visto na varredura" in x])

    def test_pedido_anterior_sem_o_painel_continua_dizendo_depois_do_anterior(self):
        linhas = self.arquivo(self.pedido("10:01:00.000", 1, "the dash not among them"), self.pedido("10:02:08.700", 7, self.visto(11)))
        p = [x for x in ler_registro.problemas(linhas) if "painel visto na varredura" in x]
        self.assertIn("(pedido #7, 69 s depois do anterior)", p[0])


class CoberturaDoLogcat(unittest.TestCase):
    """Lacuna 7: avisar quando o logcat do sistema não cobre o horário das projeções."""

    @staticmethod
    def logcat(hora_a, hora_b, n=60):
        h1, m1 = map(int, hora_a.split(":"))
        return "".join(f"10-04 {h1 + (m1 + i // 60) // 60:02d}:{(m1 + i // 60) % 60:02d}:{i % 60:02d}.000  900  900 I Tag: linha {i}\n" for i in range(n))

    def coleta(self, logcat):
        raiz = Path(tempfile.mkdtemp())
        escreve(raiz, "diag/ride-20261004-100000.log", projecao())
        if logcat is not None:
            escreve(raiz, "logcat-sistema.txt", logcat)
        return raiz

    def avisos(self, raiz):
        diag = raiz / "diag"
        return ler_registro.avisos_da_leitura([diag], ler_registro.ler([diag]))

    def test_logcat_de_outro_horario_avisa_com_o_que_cobre_e_o_que_falta(self):
        avisos = self.avisos(self.coleta(self.logcat("20:12", "20:49", n=2000)))
        self.assertEqual(1, len(avisos), avisos)
        for esperado in ("04/10 20:12 a 20:45", "2000 linhas", "não cobre 1 das 1 projeções", "menos de 1 h", "colete logo depois"):
            self.assertIn(esperado, avisos[0])

    def test_logcat_que_cobre_a_projecao_nao_avisa(self):
        self.assertEqual([], self.avisos(self.coleta(self.logcat("10:00", "10:09", n=400))))

    def test_poucas_linhas_perto_da_projecao_nao_cobrem(self):
        # 5 crash lines inside the projection are not coverage: the crash buffer outlives the rest.
        crash = "".join(f"10-04 10:05:0{i}.000  900  900 E AndroidRuntime: x\n" for i in range(5))
        avisos = self.avisos(self.coleta(self.logcat("20:12", "20:49", n=2000) + crash))
        self.assertEqual(1, len(avisos))
        self.assertIn("04/10 20:12", avisos[0])

    def test_sem_logcat_nao_avisa_e_o_aviso_nao_e_problema(self):
        self.assertEqual([], self.avisos(self.coleta(None)))
        raiz = self.coleta(self.logcat("20:12", "20:49", n=2000))
        diag = raiz / "diag"
        self.assertEqual([], [p for p in ler_registro.problemas(ler_registro.ler([diag])) + ler_registro.do_sistema([diag], ler_registro.ler([diag])) if "logcat do sistema" in p])

    def test_o_programa_mostra_o_aviso_em_secao_propria_e_o_codigo_de_saida_nao_muda(self):
        raiz = self.coleta(self.logcat("20:12", "20:49", n=2000))
        feito = subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), str(raiz / "diag")], capture_output=True, text=True, encoding="utf-8")
        self.assertIn("AVISOS DA LEITURA", feito.stdout)
        self.assertIn("nenhum problema conhecido", feito.stdout)
        self.assertEqual(0, feito.returncode)


class RodaSemPythonpath(unittest.TestCase):
    """Lacuna 8: `import logcat_sistema` falhava com `python -I` ou de outra pasta (o caminho vem do próprio arquivo agora)."""

    def roda(self, *args, cwd=None, isolado=True):
        ambiente = {k: v for k, v in os.environ.items() if k != "PYTHONPATH"}
        comando = [sys.executable] + (["-I"] if isolado else []) + [str(AQUI / "ler_registro.py")] + [str(a) for a in args]
        return subprocess.run(comando, capture_output=True, text=True, encoding="utf-8", env=ambiente, cwd=cwd)

    def test_com_python_i_sem_pythonpath_e_de_outra_pasta(self):
        d = Path(tempfile.mkdtemp())
        escreve(d, "ride-20261004-100000.log", BOA)
        feito = self.roda(d, cwd=tempfile.gettempdir())
        self.assertEqual(0, feito.returncode, feito.stderr)
        self.assertIn("projeção ligada", feito.stdout)
        self.assertNotIn("ModuleNotFoundError", feito.stderr)

    def test_sem_python_i_de_outra_pasta_tambem(self):
        d = Path(tempfile.mkdtemp())
        escreve(d, "ride-20261004-100000.log", BOA)
        feito = self.roda(d, cwd=tempfile.gettempdir(), isolado=False)
        self.assertEqual(0, feito.returncode, feito.stderr)

    def test_sem_argumentos_mostra_o_uso(self):
        feito = self.roda()
        self.assertEqual(2, feito.returncode)
        self.assertIn("ler_registro.py", feito.stdout)


class RaizDaColetaEAjuda(unittest.TestCase):
    """Revisão do item 17: a raiz da coleta lê os `diag/ride-*.log` como a `diag/`; `--help` e argumento errado; caminho sem registro avisa."""

    def coleta(self):
        raiz = Path(tempfile.mkdtemp())
        escreve(raiz, "diag/ride-20261004-100000.log", projecao(linha("10:05:00.000", "aa", "[AA] AapTransport stopping and sending byebye")))
        escreve(raiz, "logcat-sistema.txt", LOGCAT_ANDROID_AUTO)
        escreve(raiz, "logcat-sistema.mascarado.txt", LOGCAT_ANDROID_AUTO)
        return raiz

    def roda(self, *args):
        ambiente = {k: v for k, v in os.environ.items() if k != "PYTHONPATH"}
        return subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py")] + [str(a) for a in args], capture_output=True, text=True, encoding="utf-8", env=ambiente)

    def test_a_raiz_da_coleta_le_os_registros_de_diag_e_da_os_mesmos_problemas(self):
        raiz = self.coleta()
        na_raiz, em_diag = self.roda(raiz), self.roda(raiz / "diag")
        achados = lambda t: t.stdout.split("PROBLEMAS:")[1].split("AVISOS DA LEITURA")[0]
        self.assertEqual(achados(em_diag), achados(na_raiz))
        self.assertIn("projeção ligada", na_raiz.stdout)
        self.assertIn("1,2 s depois do byebye do REau", na_raiz.stdout)
        self.assertNotIn("sem byebye do REau nos 60 s antes", na_raiz.stdout)
        self.assertEqual(em_diag.returncode, na_raiz.returncode)

    def test_a_raiz_lida_em_codigo(self):
        raiz = self.coleta()
        self.assertEqual(ler_registro.ler([raiz / "diag"]), ler_registro.ler([raiz]))
        self.assertEqual([], [a for a in ler_registro.avisos_da_leitura([raiz], ler_registro.ler([raiz])) if "ride-" in a])

    def test_a_pasta_com_registros_na_propria_raiz_nao_le_diag_alem(self):
        d = Path(tempfile.mkdtemp())
        escreve(d, "ride-20261004-100000.log", BOA)
        escreve(d, "diag/ride-20261004-110000.log", BOA)
        self.assertEqual({"ride-20261004-100000.log"}, {x.arquivo for x in ler_registro.ler([d])})

    def test_caminho_sem_nenhum_ride_avisa_nos_avisos_da_leitura(self):
        raiz = Path(tempfile.mkdtemp())
        escreve(raiz, "logcat-sistema.txt", LOGCAT_ANDROID_AUTO)
        avisos = ler_registro.avisos_da_leitura([raiz], ler_registro.ler([raiz]))
        self.assertTrue(any("nenhum `ride-*.log`" in a and str(raiz) in a for a in avisos), avisos)
        com = self.coleta()
        self.assertFalse(any("nenhum `ride-*.log`" in a for a in ler_registro.avisos_da_leitura([com], ler_registro.ler([com]))))
        feito = self.roda(raiz)
        self.assertIn("nenhum `ride-*.log`", feito.stdout)

    def test_help_mostra_o_uso_e_sai_com_zero(self):
        for flag in ("--help", "-h"):
            feito = self.roda(flag)
            self.assertEqual(0, feito.returncode, feito.stderr)
            self.assertIn("ler_registro.py", feito.stdout)
            self.assertNotIn("Traceback", feito.stderr)
        self.assertEqual(0, self.roda(self.coleta(), "--help").returncode)

    def test_argumento_desconhecido_ou_caminho_que_nao_existe_da_mensagem_clara_e_codigo_nao_zero(self):
        for args in (["--nao-existe"], ["/caminho/que/nao/existe"], [str(self.coleta()), "-x"]):
            feito = self.roda(*args)
            self.assertEqual(2, feito.returncode, args)
            self.assertNotIn("Traceback", feito.stderr)
            self.assertTrue(feito.stderr.strip(), args)


if __name__ == "__main__":
    unittest.main()
