"""Testes do ler_registro.py.

    python -B scripts\\registro\\test_ler_registro.py
"""
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
        self.assertIn("Wi-Fi antes da queda: rssi=-56 dBm tx=72 rx=72 Mbps 2.4 GHz", tempo)

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
            linha("10:04:00.000", "device", "device battery=78% charging=no temp=37.0C thermal=none screen=on [30 s]"),
        )
        achados = [x for x in self.problemas(texto) if "temperatura" in x]
        self.assertEqual(1, len(achados), achados)
        self.assertIn("31.0", achados[0])
        self.assertIn("37.0", achados[0])
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
        self.assertTrue(any("vigia" in x and "RX watchdog" in x for x in p), p)
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
        self.assertTrue(any("temperatura subindo" in x and "31.0" in x and "38.5" in x for x in p), p)

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


if __name__ == "__main__":
    unittest.main()
