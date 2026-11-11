"""Testes do item 17a (08/11/2026) do ler_registro.py: os falsos alarmes que saíram e as lacunas que se preencheram (o teste do item 1a, o tempo sem imagem por queda,
o painel ligado → unido, o recorte por data e o resto). Cada teste diz de qual registro da coleta de 07/11 saiu o caso.

    python -B scripts\\registro\\test_ler_registro_17a.py
"""
import os
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
import varredura  # noqa: E402
from test_ler_registro import escreve, linha, projecao, resumo_com_dark  # noqa: E402

ABERTURA = "10:00:00.000  +     0ms  ==== process start: app — app 0.3.0-dev+26.11.05-00.25 (29897485, 7eee9cb), Google Pixel, Android 16 (API 36) ====\n"
TIPOS = linha("10:00:01.100", "projection", "projection: foreground types accepted: connectedDevice|microphone|location (0x98)")


def com_versao(*meio, hora="10:00:01.000", versao="0.3.0-dev+26.11.05-00.25 (29897485, 7eee9cb)", tipos=True):
    """Um registro de uma build: a abertura com a versão, a partida, o tipo `location` aceito (se `tipos`) e o `meio`."""
    texto = ABERTURA.replace("0.3.0-dev+26.11.05-00.25 (29897485, 7eee9cb)", versao)
    texto += f"{hora}  +  1000ms  ---- projeção ligada: projection ----\n" + (TIPOS if tipos else "") + "".join(meio)
    return texto + "10:59:00.000  +  1000ms  ---- projeção desligada: projection ----\n"


def arquivo(texto, nome="ride-20261105-100000.log"):
    d = Path(tempfile.mkdtemp())
    escreve(d, nome, texto)
    return d


def lido(texto, nome="ride-20261105-100000.log"):
    return ler_registro.ler([arquivo(texto, nome)])


FUNDO = "app background — process importance 125 (foreground service), projection in the foreground"
VAZIA = ("scan empty [location type=on, permission=given, system location=on, process importance 125 (foreground service)"
         "{extra}]")


def pedido(hora, n, varredura, tag="wifi"):
    return linha(hora, tag, f"requestNetwork 'RE_****30' everConnected=false timeout=30000ms; request #{n} (0s since the first), screen interactive=true locked=true, "
                           f"{varredura}, approvedBefore=true")


class FalsosAlarmes(unittest.TestCase):
    """O que o leitor acusava em 07/11 e não era defeito."""

    # ── o laço de espera com a moto desligada ───────────────────────────────────────────────────────────────────

    NADA = "scan of 8 networks, the dash not among them"
    VISTO = "scan of 9 networks, the dash: 'RE_****30' -50 dBm 2417 MHz seen 4 s ago"

    def laco(self, vistos, fim="10:09:00.000"):
        pedidos = [pedido(f"10:{1 + 20 * i // 60:02d}:{(20 * i) % 60:02d}.000", i + 1, v) for i, v in enumerate(vistos)]
        return projecao(*pedidos, linha(fim, "wifi", "connected 'RE_****30' in 900ms"))

    def problemas(self, texto):
        return ler_registro.problemas(lido(texto))

    def test_o_painel_visto_no_primeiro_pedido_depois_da_queda_e_no_ultimo_e_a_moto_desligada_que_voltou(self):
        # 06/11 17:21:01 (18 pedidos) e 07/11 15:03:50 (20): `seen 4 s ago` do painel que acabava de sair, 16 pedidos sem ele, e ele de volta no último.
        texto = self.laco([self.VISTO] + [self.NADA] * 14 + [self.VISTO])
        self.assertEqual([], [p for p in self.problemas(texto) if "laço de espera" in p])

    def test_dois_pedidos_seguidos_com_o_painel_e_a_espera_ainda_longe_do_fim_continuam_laco(self):
        texto = self.laco([self.NADA] * 3 + [self.VISTO] * 4 + [self.NADA])
        self.assertEqual(1, len([p for p in self.problemas(texto) if "laço de espera" in p]))

    def test_dois_pedidos_seguidos_com_o_painel_que_une_em_menos_de_30_s_nao_sao_laco(self):
        # 07/11 15:10:31.9 e 15:10:35.9 viram o painel voltar e uniram às 15:10:36.8.
        pedidos = [pedido(f"10:{1 + 20 * i // 60:02d}:{(20 * i) % 60:02d}.000", i + 1, self.NADA) for i in range(12)]
        texto = projecao(*pedidos, pedido("10:08:00.000", 13, self.VISTO), pedido("10:08:04.000", 14, self.VISTO),
                         linha("10:08:05.000", "wifi", "connected 'RE_****30' in 900ms"))
        self.assertEqual([], [p for p in self.problemas(texto) if "laço de espera" in p])

    # ── o RX watchdog ───────────────────────────────────────────────────────────────────────────────────────────

    def queda(self, primeiro):
        wifi = linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms (after 70 s on 2452 MHz; rssi -83, last dash packet 0.3 s before)")
        vigia = linha("10:00:56.000" if primeiro else "10:01:04.200", "error", "E: RX watchdog: dash silent 4s → link lost")
        return projecao(*((vigia, wifi) if primeiro else (wifi, vigia)))

    def test_o_vigia_nao_e_problema_em_nenhuma_das_quedas_a_que_comeca_por_ele_ou_pelo_wifi(self):
        for primeiro in (True, False):
            p = self.problemas(self.queda(primeiro))
            self.assertEqual([], [x for x in p if "watchdog" in x or "vigia" in x], (primeiro, p))

    def test_so_a_queda_que_comecou_pelo_painel_calado_sai_nos_avisos_e_nao_conta_como_problema(self):
        for primeiro, esperado in ((True, 1), (False, 0)):
            d = arquivo(self.queda(primeiro))
            avisos = [a for a in ler_registro.avisos_da_leitura([d], ler_registro.ler([d])) if "painel calado" in a]
            self.assertEqual(esperado, len(avisos), (primeiro, avisos))
        d = arquivo(self.queda(True))
        self.assertIn("1 de 1 quedas começaram pelo painel calado com o Wi-Fi ainda de pé", ler_registro.avisos_da_leitura([d], ler_registro.ler([d]))[0])
        feito = subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), str(d)], capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(0, feito.returncode, feito.stdout)  # nenhum problema: o código de saída é o de uma leitura limpa

    # ── o Auth timed out ────────────────────────────────────────────────────────────────────────────────────────

    SILENCIO = "E: session fail: Auth timed out — not a single packet from the dash (bike off, or its Wi-Fi gone?)."
    NOME = "E: session fail: Auth timed out — the dash answered but never accepted (no 07 01 01). Check SSID matches 'RE_****30'. If it keeps happening, check Digital."
    FRACO = "E: session fail: Auth timed out — the dash answered but the link is weak (-80 dBm, 3 packets in 15 s): the dash is probably too far from the phone."

    def test_o_auth_timed_out_da_moto_desligada_nao_e_problema(self):
        # 07/11 08:18:44 e 19:41:06: nenhum pacote do painel.
        self.assertEqual([], [p for p in self.problemas(projecao(linha("10:01:00.000", "error", self.SILENCIO))) if "autenticação" in p])

    def test_o_auth_timed_out_com_o_painel_que_respondeu_continua_problema(self):
        p = [x for x in self.problemas(projecao(linha("10:01:00.000", "error", self.NOME))) if "autenticação" in x]
        self.assertEqual(1, len(p), p)
        self.assertIn("Check SSID", p[0])
        self.assertNotIn("sinal fraco", p[0])  # sem nenhum sinal no registro nem o fraco: o texto vale como está

    def test_o_texto_novo_de_sinal_fraco_sai_como_sinal_fraco(self):
        p = [x for x in self.problemas(projecao(linha("10:01:00.000", "error", self.FRACO))) if "autenticação" in x]
        self.assertEqual(1, len(p), p)
        self.assertIn("com o sinal fraco", p[0])
        self.assertIn("-80 dBm, 3 packets in 15 s", p[0])

    def test_o_texto_antigo_que_manda_conferir_o_nome_com_o_sinal_fraco_pouco_antes_diz_que_era_enganoso(self):
        # 05/11 07:13:21: `link lost … rssi -82` às 07:13:10 e o `Check SSID` às 07:13:21.
        texto = projecao(linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms (after 4 s on 2452 MHz; rssi -82, mechanism=unknown)"),
                         linha("10:01:11.000", "error", self.NOME))
        p = [x for x in self.problemas(texto) if "autenticação" in x]
        self.assertEqual(1, len(p), p)
        self.assertIn("sinal fraco (-82 dBm pouco antes)", p[0])
        self.assertIn("enganoso", p[0])

    def test_o_sinal_bom_pouco_antes_deixa_o_texto_do_nome_como_esta(self):
        texto = projecao(linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms (after 4 s on 2452 MHz; rssi -60, mechanism=unknown)"),
                         linha("10:01:11.000", "error", self.NOME))
        p = [x for x in self.problemas(texto) if "autenticação" in x]
        self.assertNotIn("sinal fraco", p[0])

    # ── o dumpsys sem desconexão, dito uma vez ──────────────────────────────────────────────────────────────────

    def test_o_dumpsys_sem_desconexao_de_varias_quedas_sai_numa_linha_so(self):
        quedas_ = tuple(linha(f"10:0{i}:00.231", "wifi", "W: link lost — reconnecting in 4000ms (after 10 s on 2422 MHz; rssi -61)") for i in range(1, 5))
        raiz = Path(tempfile.mkdtemp())
        escreve(raiz, "diag/ride-20261004-100000.log", projecao(*quedas_))
        escreve(raiz, "sistema/dumpsys-wifi.txt", "nada que case com uma desconexão\n")
        achados = ler_registro.do_dumpsys_wifi([raiz / "diag"], ler_registro.ler([raiz / "diag"]))
        sem = [a for a in achados if "não traz desconexão" in a or "sem desconexão" in a]
        self.assertEqual(1, len(sem), sem)
        self.assertIn("4 quedas do Wi-Fi do painel sem desconexão a 10 s no dumpsys do Wi-Fi", sem[0])
        self.assertIn("10:01:00, 10:02:00, 10:03:00, 10:04:00", sem[0])

    # ── o painel sem imagem que não contava a última ────────────────────────────────────────────────────────────

    def test_o_painel_sem_imagem_do_resumo_diz_a_ultima_interrupcao_que_nao_contou(self):
        texto = projecao(
            linha("10:01:00.000", "session", "→ STREAMING"),
            linha("10:03:00.000", "stream", "video loop stop (0 frames held back before a keyframe)"),
            linha("10:05:00.000", "session", "→ STREAMING"),
            linha("10:07:00.000", "stream", "video loop stop (0 frames held back before a keyframe)"),
            linha("10:09:00.000", "projection", resumo_com_dark("2/4m00s (max 2m00s)")))
        p = [x for x in self.problemas(texto) if "painel sem imagem" in x]
        self.assertEqual(1, len(p), p)
        self.assertIn("sem contar a última, de 2 min 00 s, com a moto desligada", p[0])


class TesteDoItem1a(unittest.TestCase):
    """O teste do item 1a, com o critério novo de 08/11/2026 (`varredura.py`)."""

    def rodar(self, *meio, **opcoes):
        resultados = varredura.teste_1a(lido(com_versao(*meio, **opcoes)))
        self.assertEqual(1, len(resultados), resultados)
        return resultados[0]

    COM_REDES = "scan of 12 networks, the dash not among them"

    def fundo(self, hora="10:00:10.000"):
        return linha(hora, "system", FUNDO)

    def test_leituras_com_redes_atras_e_a_prova_positiva_valem_provado(self):
        r = self.rodar(self.fundo(), pedido("10:01:00.000", 1, self.COM_REDES), pedido("10:02:00.000", 2, "scan of 9 networks, the dash: 'RE_****30' -50 dBm 2417 MHz seen 4 s ago"))
        self.assertEqual("PROVADO", r["veredito"])
        self.assertEqual([2, 0], r["leituras"]["atras"])
        self.assertTrue(r["positiva"])
        self.assertEqual((1, 1, 0), r["tipos"])
        self.assertEqual(1, r["vistos"])

    def test_as_leituras_vazias_de_um_lugar_sem_outra_rede_nao_reprovam_17_59_de_05_11(self):
        # Quatro leituras vazias com o app atrás, e o mesmo estado dando só o painel depois; a de 17:58:58 estava com o app na frente (outro estado).
        r = self.rodar(
            linha("10:00:05.000", "system", "app foreground"),
            pedido("10:00:08.000", 1, "scan of 1 networks, the dash not among them"),
            self.fundo("10:00:30.000"),
            pedido("10:01:00.000", 1, VAZIA.format(extra="").join(["scan ", ""]).replace("scan scan", "scan")),
            pedido("10:01:02.000", 2, "scan " + VAZIA.format(extra="").split("scan ", 1)[-1]),
            pedido("10:02:30.000", 3, "scan of 1 networks, the dash: 'RE_****30' -53 dBm 2412 MHz seen 4 s ago"))
        self.assertEqual("PROVADO", r["veredito"])
        self.assertEqual(2, len(r["vazias"]))
        self.assertEqual([], [v for v in r["vazias"] if v["motivos"]])
        texto = "\n".join(varredura.texto_do_teste([r]))
        self.assertIn("lugar sem outra rede, não falta de acesso", texto)
        self.assertEqual([], varredura.problemas_do_teste([r]))

    def vazia(self, hora, n, extra=""):
        return pedido(hora, n, VAZIA.format(extra=extra).replace("scan empty", "scan empty"))

    def test_a_leitura_vizinha_com_outra_rede_no_mesmo_estado_em_60_s_reprova(self):
        r = self.rodar(self.fundo(), self.vazia("10:01:00.000", 1), pedido("10:01:40.000", 2, "scan of 6 networks, the dash not among them"))
        self.assertEqual("REPROVADO", r["veredito"])
        self.assertIn("trouxe 6 redes sem o painel", r["vazias"][0]["motivos"][0])
        p = varredura.problemas_do_teste([r])
        self.assertEqual(1, len(p))
        self.assertIn("leitura vazia da lista de redes com o app atrás", p[0])

    def test_a_leitura_vizinha_depois_dos_60_s_ou_so_com_o_painel_nao_reprova(self):
        longe = self.rodar(self.fundo(), self.vazia("10:01:00.000", 1), pedido("10:02:10.000", 2, "scan of 6 networks, the dash not among them"))
        self.assertEqual("PROVADO", longe["veredito"])
        so_painel = self.rodar(self.fundo(), self.vazia("10:01:00.000", 1), pedido("10:01:30.000", 2, "scan of 1 networks, the dash: 'RE_****30' -53 dBm 2412 MHz seen 4 s ago"))
        self.assertEqual("PROVADO", so_painel["veredito"])
        painel_e_outra = self.rodar(self.fundo(), self.vazia("10:01:00.000", 1), pedido("10:01:30.000", 2, "scan of 2 networks, the dash: 'RE_****30' -53 dBm 2412 MHz seen 4 s ago"))
        self.assertEqual("REPROVADO", painel_e_outra["veredito"])

    def test_a_leitura_vizinha_em_outro_estado_nao_vale(self):
        r = self.rodar(linha("10:00:50.000", "system", "app foreground"), pedido("10:00:55.000", 1, "scan of 6 networks, the dash not among them"),
                       linha("10:00:58.000", "system", FUNDO), self.vazia("10:01:00.000", 2))
        self.assertEqual([], [v for v in r["vazias"] if v["motivos"]])

    def test_o_celular_unido_a_um_wifi_reprova_pelo_sufixo_novo_e_pela_olhada_antiga(self):
        novo = self.rodar(self.fundo(), self.vazia("10:01:00.000", 1, ", last non-empty reading: none yet, wifi joined=other"))
        self.assertEqual("REPROVADO", novo["veredito"])
        self.assertIn("wifi joined=other", novo["vazias"][0]["motivos"][0])
        sem_wifi = self.rodar(self.fundo(), self.vazia("10:01:00.000", 1, ", last non-empty reading: none yet, wifi joined=no"), pedido("10:01:20.000", 2, "scan of 1 networks, the dash not among them (newest result 1 s old)"))
        self.assertEqual("REPROVADO", sem_wifi["veredito"])  # a leitura vizinha com 1 rede sem o painel reprova, o sufixo `no` não a salva
        antigo = self.rodar(self.fundo(), linha("10:00:40.000", "device", "device battery=80% charging=no screen=off data=wifi/ok [30 s]"), self.vazia("10:01:00.000", 1))
        self.assertEqual("REPROVADO", antigo["veredito"])
        self.assertIn("data=wifi", antigo["vazias"][0]["motivos"][0])
        celular = self.rodar(self.fundo(), linha("10:00:40.000", "device", "device battery=80% charging=no screen=off data=cell/ok [30 s]"), self.vazia("10:01:00.000", 1))
        self.assertEqual("INCONCLUSIVO", celular["veredito"])

    def test_a_ultima_leitura_com_redes_recente_e_sem_mudar_de_estado_reprova_e_com_mudanca_nao(self):
        extra = ", last non-empty reading: 12 networks, 20 s ago, newest result 21 s old, wifi joined=no"
        r = self.rodar(self.fundo(), self.vazia("10:01:00.000", 1, extra))
        self.assertEqual("REPROVADO", r["veredito"])
        self.assertIn("20 s antes e sem o app mudar de estado, tinha 12 redes", r["vazias"][0]["motivos"][0])
        mudou = self.rodar(self.fundo("10:00:50.000"), linha("10:00:55.000", "system", "app foreground"), linha("10:00:58.000", "system", FUNDO), self.vazia("10:01:00.000", 1, extra))
        self.assertEqual("INCONCLUSIVO", mudou["veredito"])
        uma = self.rodar(self.fundo(), self.vazia("10:01:00.000", 1, ", last non-empty reading: 1 networks, 20 s ago, wifi joined=no"))
        self.assertEqual("INCONCLUSIVO", uma["veredito"])  # uma rede pode ser o painel: não se afirma

    def test_so_vazias_atras_e_inconclusivo_sem_leituras_atras_e_sem_dados(self):
        self.assertEqual("INCONCLUSIVO", self.rodar(self.fundo(), self.vazia("10:01:00.000", 1))["veredito"])
        self.assertEqual("SEM DADOS", self.rodar(linha("10:00:05.000", "system", "app foreground"), pedido("10:01:00.000", 1, self.COM_REDES))["veredito"])

    def test_o_tipo_location_que_faltou_ou_foi_recusado_reprova(self):
        sem = self.rodar(self.fundo(), pedido("10:01:00.000", 1, self.COM_REDES), hora="10:00:01.000")
        self.assertEqual("PROVADO", sem["veredito"])
        sem_location = varredura.teste_1a(lido(com_versao(self.fundo(), pedido("10:01:00.000", 1, self.COM_REDES)).replace("|location (0x98)", " (0x18)")))[0]
        self.assertEqual("REPROVADO", sem_location["veredito"])
        recusado = varredura.teste_1a(lido(com_versao(linha("10:00:02.000", "projection", "projection: foreground start refused: SecurityException"), self.fundo(),
                                                     pedido("10:01:00.000", 1, self.COM_REDES))))[0]
        self.assertEqual("REPROVADO", recusado["veredito"])
        self.assertIn("não teve o tipo `location`", varredura.problemas_do_teste([recusado])[0])

    def test_a_build_sem_a_linha_dos_tipos_e_de_antes_da_correcao_e_nao_reprova(self):
        r = self.rodar(self.fundo(), self.vazia("10:01:00.000", 1), self.vazia("10:01:20.000", 2), pedido("10:01:30.000", 3, self.COM_REDES), tipos=False,
                       versao="0.3.0-dev+26.11.03-09.28 (1, abc)")
        self.assertEqual("ANTES DA CORREÇÃO", r["veredito"])
        self.assertEqual([], varredura.problemas_do_teste([r]))
        self.assertIn("o esperado antes da correção", "\n".join(varredura.texto_do_teste([r])))

    def test_uma_build_por_vez_nao_se_mistura(self):
        texto = com_versao(self.fundo(), pedido("10:01:00.000", 1, self.COM_REDES)) + com_versao(
            self.fundo("10:00:10.000"), self.vazia("10:01:00.000", 1), tipos=False, versao="0.3.0-dev+26.11.03-09.28 (1, abc)")
        r = varredura.teste_1a(lido(texto))
        self.assertEqual(["PROVADO", "ANTES DA CORREÇÃO"], [x["veredito"] for x in r])

    def test_os_cortes_da_pausa_e_as_leituras_na_frente_entram_na_contagem(self):
        r = self.rodar(
            linha("10:00:05.000", "system", "app foreground"), pedido("10:00:08.000", 1, self.COM_REDES), self.fundo("10:00:20.000"),
            pedido("10:01:00.000", 2, self.COM_REDES),
            linha("10:01:10.000", "wifi", "the dash showed up in the scan 12 s after the last request failed: the pause is cut, asking now"),
            linha("10:01:10.010", "wifi", "the dash showed up in the scan 12000 ms after the Wi-Fi was lost: asking again (the pause is cut short)"),
            linha("10:01:20.000", "wifi", "the dash was in the scan when it went out and has not joined 8044 ms into request #3: asking again"),
            linha("10:01:21.000", "projection", "projection: the bike's Wi-Fi is back or the dash was seen: the dash is tried now, not when the wait ends"))
        self.assertEqual([1, 0], r["leituras"]["frente"])
        self.assertEqual([1, 0], r["leituras"]["atras"])
        self.assertEqual({"pausa cortada": 2, "pedido de novo com o painel visto": 1, "painel na varredura sem unir": 1, "projeção tenta o painel já": 1}, r["cortes"])

    def test_o_bloco_aparece_na_saida_do_leitor_e_a_leitura_vazia_que_reprova_vira_problema(self):
        d = arquivo(com_versao(self.fundo(), self.vazia("10:01:00.000", 1), pedido("10:01:30.000", 2, "scan of 6 networks, the dash not among them")))
        feito = subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), str(d)], capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(1, feito.returncode)
        self.assertIn("TESTE DO ITEM 1a", feito.stdout)
        self.assertIn(": REPROVADO", feito.stdout)
        self.assertIn("item 1a — leitura vazia da lista de redes com o app atrás", feito.stdout)


class RecortePorData(unittest.TestCase):
    """`--desde`, `--ate` e o corte padrão da coleta anterior."""

    def pasta(self):
        raiz = Path(tempfile.mkdtemp())
        for nome, hora in (("ride-20261103-100000.log", "10"), ("ride-20261104-221500.log", "22"), ("ride-20261105-070000.log", "07"),
                           ("ride-20261106-070000.log", "07")):
            escreve(raiz, f"diag/{nome}", f"{hora}:00:00.000  +     0ms  ==== process start: app — x ====\n"
                                         f"{hora}:00:01.000  +  1000ms  ---- projeção ligada: projection ----\n"
                                         f"{hora}:09:00.000  +  1000ms  ---- projeção desligada: projection ----\n")
        return raiz

    def nomes(self, linhas):
        return sorted({x.arquivo for x in linhas})

    def test_sem_corte_le_tudo(self):
        self.assertEqual(4, len(self.nomes(ler_registro.ler([self.pasta()]))))

    def test_desde_e_ate_deixam_o_registro_inteiro_que_tem_algum_instante_na_janela(self):
        raiz = self.pasta()
        linhas = ler_registro.ler([raiz], desde=datetime(2026, 11, 5), ate=datetime(2026, 11, 5, 23, 59))
        self.assertEqual(["ride-20261105-070000.log"], self.nomes(linhas))
        info = {}
        ler_registro.ler([raiz], desde=datetime(2026, 11, 4, 22, 5), info=info)
        self.assertEqual(["ride-20261103-100000.log"], info["excluidos"])  # o de 04/11 22:00 acaba às 22:09, depois das 22:05: fica inteiro

    def test_um_registro_cujo_nome_nao_traz_o_dia_nunca_sai(self):
        raiz = Path(tempfile.mkdtemp())
        escreve(raiz, "ride-sem-dia.log", projecao())
        self.assertEqual(1, len(self.nomes(ler_registro.ler([raiz], desde=datetime(2030, 1, 1)))))

    def coletas(self):
        base = Path(tempfile.mkdtemp())
        antiga, nova = base / "celular-20261104-2331", base / "celular-20261107-2327"
        escreve(nova, "diag/ride-20261104-225217.log", projecao())   # acaba antes da coleta anterior: já foi analisado
        escreve(nova, "diag/ride-20261105-070358.log", projecao())
        escreve(nova, "diag/ride-20261107-195357.log", projecao())
        antiga.mkdir(parents=True)
        (base / "celular-20261025-lite-dubai").mkdir()    # sem hora no nome: não é uma coleta datada
        return base, antiga, nova

    def test_a_coleta_anterior_e_a_mais_recente_das_pastas_ao_lado_com_a_data_e_a_hora_no_nome(self):
        _, antiga, nova = self.coletas()
        pasta, quando = ler_registro.coleta_anterior(nova)
        self.assertEqual(antiga.name, pasta.name)
        self.assertEqual(datetime(2026, 11, 4, 23, 31), quando)
        self.assertEqual(antiga.name, ler_registro.coleta_anterior(nova / "diag")[0].name)  # a `diag` dela também
        self.assertIsNone(ler_registro.coleta_anterior(antiga))  # nenhuma antes
        self.assertIsNone(ler_registro.coleta_anterior(Path(tempfile.mkdtemp())))  # não é uma coleta

    def test_o_corte_padrao_tira_os_registros_da_coleta_anterior_e_o_tudo_os_traz(self):
        _, _, nova = self.coletas()
        info = {}
        linhas = ler_registro.ler([nova], corte_padrao=True, info=info)
        self.assertEqual(["ride-20261105-070358.log", "ride-20261107-195357.log"], self.nomes(linhas))
        self.assertEqual(["ride-20261104-225217.log"], info["excluidos"])
        self.assertEqual(3, len(self.nomes(ler_registro.ler([nova]))))

    def test_na_linha_de_comando_o_corte_aparece_no_cabecalho_e_tudo_ou_desde_o_desligam(self):
        _, _, nova = self.coletas()
        roda = lambda *a: subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), *a], capture_output=True, text=True, encoding="utf-8")
        padrao = roda(str(nova))
        self.assertIn("recorte: só os registros que acabam depois da coleta anterior (celular-20261104-2331, 04/11 23:31)", padrao.stdout)
        self.assertIn("recorte: 1 registros fora da janela (ride-20261104-225217.log a ride-20261104-225217.log)", padrao.stdout)
        tudo = roda(str(nova), "--tudo")
        self.assertNotIn("recorte:", tudo.stdout)
        desde = roda(str(nova), "--desde", "2026-11-07")
        self.assertIn("recorte: janela de 07/11 00:00 em diante", desde.stdout)
        self.assertIn("recorte: 2 registros fora da janela", desde.stdout)
        ate = roda(str(nova), "--ate", "2026-11-05")
        self.assertIn("recorte: até 05/11 23:59", ate.stdout)

    def test_uma_data_que_nao_e_data_e_recusada(self):
        feito = subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), str(self.pasta()), "--desde", "ontem"], capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(2, feito.returncode)
        self.assertIn("pede uma data", feito.stderr)
        self.assertEqual(2, subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), "--desde"], capture_output=True, text=True).returncode)

    def test_o_cabecalho_agrupa_por_data_e_build(self):
        texto = com_versao(linha("10:01:00.000", "wifi", "W: link lost — reconnecting in 4000ms")) + com_versao(hora="10:30:01.000", versao="0.3.0-dev+26.11.03-09.28 (1, abc)")
        grupos = ler_registro._por_data_e_build(lido(texto))
        self.assertEqual(2, len(grupos))
        self.assertTrue(any(g.startswith("05/11: build 0.3.0-dev+26.11.03-09.28 (1, abc): 1 projeção") for g in grupos), grupos)
        self.assertTrue(any(g.startswith("05/11: build 0.3.0-dev+26.11.05-00.25 (29897485, 7eee9cb): 1 projeção") and "1 queda" in g for g in grupos), grupos)


class VistoPedidoUnidoSemLeituraVelha(unittest.TestCase):
    """Lacuna 3: depois de uma queda, o `seen N s ago` do painel que acabou de sair não mede nada; o painel que volta, sim."""

    def visto(self, n):
        return f"scan of 11 networks, the dash: 'RE_****30' -50 dBm 2417 MHz seen {n} s ago"

    def bloco(self, *meio):
        return ler_registro.projecoes(lido(projecao(*meio)))[0]

    QUEDA = staticmethod(lambda hora: linha(hora, "wifi", "W: link lost — reconnecting in 4000ms (after 1069 s on 2417 MHz; rssi -60, last dash packet 0.1 s before)"))

    def test_o_pedido_logo_depois_da_queda_que_ve_o_painel_de_antes_nao_e_medida(self):
        # 06/11 17:21:01.2: `seen 4 s ago` a 4 s da queda (era o painel que acabava de sair): "383,7 s" e afins.
        bloco = self.bloco(self.QUEDA("10:01:00.000"), pedido("10:01:04.000", 8, self.visto(4)))
        self.assertEqual([], ler_registro._pedidos_com_painel(bloco))
        self.assertEqual({}, ler_registro._visto_pedido_unido(bloco))

    def test_a_lembranca_velha_nao_gasta_o_painel_que_volta_depois(self):
        bloco = self.bloco(self.QUEDA("10:01:00.000"), pedido("10:01:04.000", 8, self.visto(4)), pedido("10:01:18.000", 9, "scan of 8 networks, the dash not among them"),
                           pedido("10:07:20.000", 25, self.visto(5)), linha("10:07:21.000", "wifi", "connected 'RE_****30' in 963ms"))
        achados = ler_registro._pedidos_com_painel(bloco)
        self.assertEqual(1, len(achados))
        self.assertEqual("10:07:20.000", achados[0][0].hora)
        self.assertEqual(5, achados[0][1])
        texto = list(ler_registro._visto_pedido_unido(bloco).values())[0]
        self.assertIn("visto há 5 s no pedido #25", texto)
        self.assertIn("visto→unido 6,0 s", texto)

    def test_o_painel_visto_numa_partida_sem_queda_continua_medindo(self):
        bloco = self.bloco(pedido("10:01:04.000", 1, self.visto(7)), linha("10:01:05.000", "wifi", "connected 'RE_****30' in 900ms"))
        self.assertEqual(1, len(ler_registro._pedidos_com_painel(bloco)))

    def test_a_olhada_do_app_separa_o_tempo_do_android_do_tempo_do_app(self):
        olhada = linha("10:01:03.000", "wifi", "scan look: the dash is in the list of 11 networks, its result 5 s old, newest result 2 s old")
        bloco = self.bloco(olhada, pedido("10:01:04.500", 1, self.visto(6)), linha("10:01:05.500", "wifi", "connected 'RE_****30' in 900ms"))
        texto = list(ler_registro._visto_pedido_unido(bloco).values())[0]
        self.assertIn("olhada do app 10:01:03,0 com o painel na varredura há 5 s (visto pelo Android cerca de 10:00:58,0)", texto)
        self.assertIn("da olhada ao pedido 1,5 s", texto)
        sem = list(ler_registro._visto_pedido_unido(self.bloco(pedido("10:01:04.500", 1, self.visto(6)))).values())[0]
        self.assertNotIn("olhada do app", sem)


class TempoSemImagemPorQueda(unittest.TestCase):
    """Lacuna 6: o tempo sem imagem de cada queda, no total e com o painel ligado."""

    def bloco(self, *meio):
        return ler_registro.projecoes(lido(projecao(*meio)))[0]

    def interrupcoes(self, bloco):
        return quedas.interrupcoes(bloco, ler_registro._grupos_de_queda(bloco))

    QUEDA = staticmethod(lambda hora, atras="0.2": linha(hora, "wifi", f"W: link lost — reconnecting in 4000ms (after 1005 s on 2452 MHz; rssi -52, last dash packet {atras} s before)"))
    IDADE = staticmethod(lambda hora, n: linha(hora, "auth", f"dash heartbeat age {n} s at the first packet"))
    STREAM = staticmethod(lambda hora: linha(hora, "session", "→ STREAMING"))

    def test_o_painel_que_religou_conta_do_ligar_e_nao_da_queda_06_11_17_47(self):
        # queda 17:44:06.7 (último pacote 0.2 s antes de 17:44:06.9), painel ligou 17:46:26.7 (idade 85 s às 17:47:51.7), STREAMING 17:47:53.4.
        bloco = self.bloco(self.QUEDA("10:00:06.900"), self.IDADE("10:03:51.700", 85), self.STREAM("10:03:53.400"))
        i = self.interrupcoes(bloco)
        self.assertEqual(1, len(i))
        self.assertAlmostEqual(226.7, i[0]["total"], places=1)
        self.assertAlmostEqual(86.7, i[0]["com_painel"], places=1)
        self.assertAlmostEqual(140.0, i[0]["religou"], places=1)
        self.assertEqual("calculado", i[0]["origem"])
        self.assertIn("sem imagem 226,7 s desde a queda das 10:00:06 (86,7 s com o painel ligado: o painel religou 140,0 s depois da queda (idade 85 s no primeiro pacote)) [calculado do registro]",
                      quedas.texto_da_interrupcao(i[0]))

    def test_o_wifi_que_caiu_com_o_painel_ligado_o_tempo_todo_e_o_mesmo_nos_dois_numeros(self):
        # 05/11 07:12:56 a 07:13:25: o painel ficou ligado (idade 79 s), só o Wi-Fi caiu e voltou.
        bloco = self.bloco(self.QUEDA("10:01:00.000", "0.4"), self.IDADE("10:01:25.000", 79), self.STREAM("10:01:26.000"))
        i = self.interrupcoes(bloco)[0]
        self.assertAlmostEqual(26.4, i["total"], places=1)
        self.assertAlmostEqual(26.4, i["com_painel"], places=1)
        self.assertIsNone(i["religou"])
        self.assertIn("o painel ficou ligado, só o Wi-Fi caiu e voltou", quedas.texto_da_interrupcao(i))

    def test_a_linha_do_app_vale_mais_que_o_calculo(self):
        bloco = self.bloco(self.QUEDA("10:00:06.900"), self.IDADE("10:03:51.700", 85), self.STREAM("10:03:53.400"),
                           linha("10:03:53.401", "session", "image back: 223.4 s without it since the last dash packet before the loss; with the dash on: 86.5 s (it came on 136.9 s into the loss)"))
        i = self.interrupcoes(bloco)[0]
        self.assertEqual("app", i["origem"])
        self.assertEqual((223.4, 86.5, 136.9), (i["total"], i["com_painel"], i["religou"]))
        self.assertIn("[linha do app]", quedas.texto_da_interrupcao(i))
        ficou = self.bloco(self.QUEDA("10:01:00.000"), self.STREAM("10:01:30.000"),
                           linha("10:01:30.001", "session", "image back: 30.0 s without it since the last dash packet before the loss; with the dash on: 30.0 s (it stayed on)"))
        self.assertIsNone(self.interrupcoes(ficou)[0]["religou"])
        desconhecido = self.bloco(self.QUEDA("10:01:00.000"), self.STREAM("10:01:30.000"),
                                  linha("10:01:30.001", "session", "image back: 30.0 s without it since the last dash packet before the loss; with the dash on: unknown"))
        self.assertIsNone(self.interrupcoes(desconhecido)[0]["com_painel"])

    def test_a_queda_de_256_s_ou_mais_diz_pelo_menos_para_o_painel(self):
        bloco = self.bloco(self.QUEDA("10:00:00.000", "0.0"), self.IDADE("10:06:21.000", 19), self.STREAM("10:06:23.000"))
        self.assertIn("pelo menos 21,0 s com o painel ligado", quedas.texto_da_interrupcao(self.interrupcoes(bloco)[0]))

    def test_a_ultima_queda_sem_a_imagem_voltar_e_a_ultima_interrupcao_e_diz_se_o_painel_voltou_ao_wifi(self):
        bloco = self.bloco(self.QUEDA("10:05:00.000"))
        i = self.interrupcoes(bloco)[0]
        self.assertTrue(i["final"])
        self.assertIn("o painel não voltou (moto desligada?)", quedas.texto_da_interrupcao(i))
        unido = self.bloco(self.QUEDA("10:05:00.000"), linha("10:05:20.000", "wifi", "connected 'RE_****30' in 900ms"))
        self.assertIn("o painel voltou ao Wi-Fi às 10:05:20", quedas.texto_da_interrupcao(self.interrupcoes(unido)[0]))

    def test_duas_quedas_antes_de_a_imagem_voltar_sao_uma_interrupcao_so(self):
        bloco = self.bloco(self.QUEDA("10:01:00.000"), self.QUEDA("10:01:30.000"), self.STREAM("10:02:00.000"))
        self.assertEqual(1, len(self.interrupcoes(bloco)))
        outra = self.bloco(self.QUEDA("10:01:00.000"), self.STREAM("10:02:00.000"), self.QUEDA("10:03:00.000"), self.STREAM("10:04:00.000"))
        self.assertEqual(2, len(self.interrupcoes(outra)))

    def test_o_painel_ligado_por_60_s_ou_mais_sem_imagem_e_problema_e_o_curto_so_informacao(self):
        longo = self.bloco(self.QUEDA("10:00:00.000", "0.0"), self.IDADE("10:03:51.000", 85), self.STREAM("10:03:53.000"))
        p = quedas.problemas_das_interrupcoes(self.interrupcoes(longo))
        self.assertEqual(1, len(p), p)
        self.assertIn("o painel ficou 87,0 s ligado e sem imagem", p[0])
        curto = self.bloco(self.QUEDA("10:00:00.000", "0.0"), self.IDADE("10:00:40.000", 19), self.STREAM("10:00:42.000"))
        self.assertEqual([], quedas.problemas_das_interrupcoes(self.interrupcoes(curto)))
        self.assertEqual(60.0, quedas.COM_PAINEL_LONGO_S)

    def test_a_linha_do_tempo_e_os_problemas_do_leitor_trazem_o_tempo_sem_imagem(self):
        d = arquivo(projecao(self.QUEDA("10:00:06.900"), self.IDADE("10:03:51.700", 85), self.STREAM("10:03:53.400")))
        linhas = ler_registro.ler([d])
        tempo = "\n".join(ler_registro.linha_do_tempo(linhas))
        self.assertIn("10:03:53.400  sem imagem 226,7 s desde a queda das 10:00:06", tempo)
        self.assertTrue(any("ficou 86,7 s ligado e sem imagem" in p for p in ler_registro.problemas(linhas)))


class PainelLigadoUnido(unittest.TestCase):
    """Lacuna 5: painel ligado → unido pela idade do painel, e o Wi-Fi que caiu contra o painel que religou."""

    def bloco(self, *meio):
        return ler_registro.projecoes(lido(projecao(*meio)))[0]

    def textos(self, bloco):
        grupos = ler_registro._grupos_de_queda(bloco)
        return list(quedas.ligado_unido(bloco, quedas.interrupcoes(bloco, grupos)).values())

    UNIDO = staticmethod(lambda hora: linha(hora, "wifi", "connected 'RE_****30' in 900ms"))
    IDADE = staticmethod(lambda hora, n: linha(hora, "auth", f"dash heartbeat age {n} s at the first packet"))
    STREAM = staticmethod(lambda hora: linha(hora, "session", "→ STREAMING"))
    QUEDA = staticmethod(lambda hora: linha(hora, "wifi", "W: link lost — reconnecting in 4000ms (after 100 s on 2417 MHz; rssi -60, last dash packet 0.1 s before)"))

    def test_a_partida_mede_do_painel_ligar_ate_unir_e_ate_transmitir(self):
        # 06/11 17:03:07.8: idade 20 s no primeiro pacote, 1 s depois do `connected`.
        t = self.textos(self.bloco(pedido("10:01:00.000", 1, "scan of 9 networks, the dash: 'RE_****30' -50 dBm 2417 MHz seen 6 s ago"), self.UNIDO("10:01:07.800"),
                                   self.IDADE("10:01:08.700", 20), self.STREAM("10:01:10.000")))
        self.assertEqual(1, len(t))
        self.assertIn("unido 19,1 s depois de ligar (idade 20 s no primeiro pacote)", t[0])
        self.assertIn("transmitindo 21,3 s depois de ligar", t[0])
        self.assertTrue(t[0].endswith("[partida]"), t[0])
        self.assertNotIn("o app não viu o painel", t[0])

    def test_o_painel_que_religou_e_o_wifi_que_caiu_com_o_painel_ligado_se_distinguem(self):
        religou = self.textos(self.bloco(self.QUEDA("10:01:00.000"), self.UNIDO("10:03:20.000"), self.IDADE("10:03:20.800", 15), self.STREAM("10:03:22.000")))
        self.assertIn("o painel religou", religou[0])
        self.assertIn("depois da queda das 10:01:00", religou[0])
        ficou = self.textos(self.bloco(self.QUEDA("10:01:00.000"), self.UNIDO("10:01:05.000"), self.IDADE("10:01:05.800", 61), self.STREAM("10:01:07.000")))
        self.assertIn("o painel ficou ligado: o Wi-Fi caiu às 10:01:00 e voltou", ficou[0])

    def test_o_app_que_nao_viu_o_painel_na_varredura_nao_afirma_de_quem_foi_a_uniao_se_a_linha_nao_diz(self):
        # 07/11 08:12:03: pedido #1 sem o painel na varredura e o Android o uniu 7 s depois, sem `showed up`.
        t = self.textos(self.bloco(pedido("10:01:00.000", 1, "scan of 16 networks, the dash not among them"), self.UNIDO("10:01:07.000"),
                                   self.IDADE("10:01:08.000", 18), self.STREAM("10:01:10.000")))
        self.assertIn("o app não viu o painel na varredura antes de ele ser unido (a linha `connected` não diz de quem foi a união", t[0])
        self.assertNotIn("uniu sozinho", t[0])

    def test_sem_a_idade_do_painel_nao_ha_o_que_medir(self):
        self.assertEqual([], self.textos(self.bloco(self.UNIDO("10:01:07.000"), self.STREAM("10:01:10.000"))))
        self.assertEqual([], self.textos(self.bloco(self.UNIDO("10:01:07.000"), self.IDADE("10:02:00.000", 20))))  # a idade veio depois de 30 s: é de outra conexão

    def test_a_linha_do_tempo_poe_o_texto_logo_depois_do_painel_no_wifi(self):
        d = arquivo(projecao(self.UNIDO("10:01:07.800"), self.IDADE("10:01:08.700", 20), self.STREAM("10:01:10.000")))
        tempo = ler_registro.linha_do_tempo(ler_registro.ler([d]))
        i = next(k for k, x in enumerate(tempo) if "painel no Wi-Fi" in x)
        self.assertIn("painel ligado → unido: unido 19,1 s depois de ligar", tempo[i + 1])


class Informes(unittest.TestCase):
    """O que o registro conta e não é defeito: avisos e linha do tempo."""

    def avisos(self, texto):
        d = arquivo(texto)
        return ler_registro.avisos_da_leitura([d], ler_registro.ler([d]))

    def tempo(self, texto):
        return "\n".join(ler_registro.linha_do_tempo(lido(texto)))

    def test_o_apps_provisorio_que_a_imagem_leva_ao_mapa_sai_com_a_duracao_abaixo_de_1_s(self):
        tela = lambda hora, texto: linha(hora, "aa-screen", f"aa-screen: {texto}")
        a = [x for x in self.avisos(projecao(
            tela("10:01:00.000", "apps (a select in the menu)"), tela("10:01:00.600", "nav (by the picture: a map, from apps)"),
            tela("10:02:00.000", "apps (a select in the menu)"), tela("10:02:00.900", "nav (by the picture: a map, from apps)"),
            tela("10:03:00.000", "apps (a select in the menu)"), tela("10:03:01.060", "menu (by the picture: the app menu, from apps)"))) if "levou ao mapa" in x]
        self.assertEqual(1, len(a), a)
        self.assertIn("a escolha no menu levou ao mapa 2 de 3 vezes (10:01:00,0 por 0,6 s; 10:02:00,0 por 0,9 s)", a[0])

    def test_o_has_not_joined_entra_na_linha_do_tempo(self):
        t = self.tempo(projecao(linha("10:01:00.000", "wifi", "the dash was in the scan when it went out and has not joined 8044 ms into request #3: asking again")))
        self.assertIn("o painel estava na varredura quando o pedido saiu e não foi unido", t)
        self.assertIn("has not joined 8044 ms into request #3", t)

    def test_as_chamadas_do_joystick_e_do_audio_entram_na_linha_do_tempo(self):
        t = self.tempo(projecao(
            linha("10:01:00.000", "call", "audio route: mode=RINGTONE device=BUILTIN_SPEAKER sco=false volume=8/8"),
            linha("10:01:05.000", "call", "end: key 6 to Android Auto, then Telecom"),
            linha("10:02:00.000", "call", "audio route: mode=IN_COMMUNICATION device=BUILTIN_EARPIECE sco=false volume=8/8"),
            linha("10:02:00.500", "call", "audio route: mode=IN_COMMUNICATION device=BUILTIN_EARPIECE sco=false volume=8/8"),
            linha("10:03:00.000", "call", "audio route: mode=NORMAL device=BUILTIN_EARPIECE sco=false volume=8/8")))
        self.assertIn("chamada tocando (modo de áudio RINGTONE)", t)
        self.assertIn("chamada encerrada pelo joystick (key 6 to Android Auto, then Telecom)", t)
        self.assertEqual(1, t.count("chamada em curso"))
        self.assertNotIn("mode=NORMAL", t)

    def test_o_cartao_de_chamada_sobre_o_mapa_diz_duracao_velocidade_e_o_que_o_dono_fez(self):
        # 05/11 18:04:13: 5,3 s, mapa, ~47 km/h, o cartão do REau mostrado e a chamada recusada pelo joystick.
        a = [x for x in self.avisos(projecao(
            linha("10:00:50.000", "aa-screen", "aa-screen: nav (by the picture: a map, from nav)"),
            linha("10:00:55.000", "stream", "video fps target=2 sent=2.2 (11frames/5.1s) composed=2.2 speed~47km/h(max 47) wifi=-67dBm"),
            linha("10:01:00.000", "aa", "[AA] AapCards: call card ringing"), linha("10:01:00.015", "dash", "call card shown (ringing)"),
            linha("10:01:05.000", "call", "end: key 6 to Android Auto, then Telecom"),
            linha("10:01:05.300", "aa", "[AA] AapCards: call card cleared"))) if "cartão de chamada" in x]
        self.assertEqual(1, len(a), a)
        for esperado in ("tocando 5,3 s (10:01:00 → 10:01:05)", "tela mapa", "velocidade estimada 47 a 47 km/h", "cartão do REau mostrado no painel",
                         "encerrada pelo joystick 5,0 s depois", "decidido em 08/11/2026: deixar como está"):
            self.assertIn(esperado, a[0])

    def test_a_chamada_em_curso_com_o_painel_fora_diz_isso(self):
        a = [x for x in self.avisos(projecao(
            linha("10:01:00.000", "session", "→ STREAMING"),
            linha("10:02:00.000", "wifi", "W: link lost — reconnecting in 4000ms (after 60 s on 2417 MHz; rssi -60, last dash packet 0.1 s before)"),
            linha("10:03:00.000", "aa", "[AA] AapCards: call card in a call"), linha("10:03:01.000", "aa", "[AA] AapCards: call card in a call"),
            linha("10:04:00.000", "aa", "[AA] AapCards: call card cleared"),
            linha("10:05:00.000", "session", "→ STREAMING"))) if "cartão de chamada" in x]
        self.assertEqual(1, len(a), a)
        self.assertIn("em curso 1 min 00 s", a[0].replace(",0 s", " s") if False else a[0].replace("60,0 s", "1 min 00 s"))
        self.assertIn("com o painel fora (sem imagem)", a[0])

    def test_o_doze_com_a_projecao_ligada_diz_quanto_durou_e_a_velocidade_estimada(self):
        texto = projecao(
            linha("10:01:00.000", "system", "doze on"),
            linha("10:05:00.000", "stream", "video fps target=2 sent=2.2 (11frames/5.1s) speed~128km/h(max 128) wifi=-67dBm"),
            linha("10:08:00.000", "stream", "video fps target=2 sent=2.2 (11frames/5.1s) speed~140km/h(max 140) wifi=-67dBm"),
            linha("10:13:24.000", "system", "doze off"))
        a = [x for x in self.avisos(texto) if "doze" in x]
        self.assertEqual(1, len(a), a)
        self.assertIn("até 10:13:24 (12 min 24 s)", a[0])
        self.assertIn("velocidade estimada de 128 a 140 km/h", a[0])
        self.assertIn("celular em doze: ligou", self.tempo(texto))
        self.assertIn("celular em doze: saiu", self.tempo(texto))

    def test_a_economia_de_bateria_automatica_e_a_que_ja_estava_ligada_na_partida(self):
        sozinha = [x for x in self.avisos(projecao(
            linha("10:00:20.000", "device", "device battery=80% charging=no temp=31.0C screen=on doze=no saver=off [start]"),
            linha("10:08:30.000", "system", "battery low at 75% (Android's low mark follows the battery saver's automatic level: 75%; said once until it charges)"),
            linha("10:08:30.002", "system", "saver on"))) if "economia" in x]
        self.assertEqual(["10:08:30: a economia de bateria ligou sozinha, no nível automático do Android (75 %)"], sozinha)
        ligada = [x for x in self.avisos(projecao(
            linha("10:00:20.000", "device", "device battery=80% charging=no temp=31.0C screen=on doze=no saver=on [start]"),
            linha("10:00:21.000", "system", "saver on (came late: it changed and came back before REau heard)"))) if "economia" in x]
        self.assertEqual(1, len(ligada), ligada)
        self.assertIn("já estava ligada na partida", ligada[0])

    def abertura_do_menu(self, tela, tecla=False):
        return projecao(
            linha("10:00:30.000", "system", tela),
            linha("10:01:00.000", "aa", "[AA] Nav: cluster status INACTIVE"),
            *([linha("10:01:01.000", "aa", "[AA] key 23 sent to Android Auto")] if tecla else []),
            linha("10:01:03.000", "aa-screen", "aa-screen: menu (by the picture: the app menu, from nav)"))

    def test_o_menu_aberto_sem_tecla_com_a_tela_acesa_sai_com_o_inactive_que_veio_antes(self):
        a = [x for x in self.avisos(self.abertura_do_menu("screen on")) if "menu" in x and "tecla" in x]
        self.assertEqual(2, len(a), a)
        self.assertIn("10:01:03.000: menu aberto sem tecla do REau com a tela do celular acesa, 3,0 s depois de `cluster status INACTIVE`", a[0])
        self.assertIn("de 1 episódios de `cluster status INACTIVE`, 1 com a tela acesa e 0 com a tela apagada ou sem dado; 1 virou menu sem tecla", a[1])

    def test_com_a_tela_apagada_ou_com_tecla_do_rear_nao_diz_nada(self):
        self.assertEqual([], [x for x in self.avisos(self.abertura_do_menu("screen off")) if "menu aberto" in x])
        self.assertEqual([], [x for x in self.avisos(self.abertura_do_menu("screen on", tecla=True)) if "menu aberto" in x])

    def test_o_sigsegv_do_audioserver_e_informativo_e_o_de_um_processo_do_reau_e_defeito(self):
        raiz = Path(tempfile.mkdtemp())
        escreve(raiz, "diag/ride-20261107-100000.log", projecao())
        escreve(raiz, "logcat-crash.txt",
                "11-07 17:49:23.149  1161  2015 F libc    : Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0 in tid 2015 (AudioOut_D), pid 1161 (audioserver)\n"
                "11-07 17:49:23.712 27388 27388 F DEBUG   : signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000000000000000\n"
                "11-06 10:00:00.000  500  600 F libc    : Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0 in tid 600 (x), pid 500 (com.reau.app)\n"
                "10-30 10:00:00.000  500  600 F libc    : Fatal signal 6 (SIGABRT), code -1 in tid 600 (x), pid 500 (com.reau.app)\n")
        linhas = ler_registro.ler([raiz])
        achados = ler_registro._crash_da_coleta([raiz], linhas)
        self.assertEqual(1, len(achados), achados)  # só o dia dos registros lidos (07/11): o do REau de 06/11 e o de 30/10 são de outro dia
        self.assertEqual("07/11 17:49:23: audioserver caiu com SIGSEGV (sinal 11): é do Android, fora do REau (informativo)", achados[0][0])
        self.assertFalse(achados[0][1])
        self.assertIn(achados[0][0], ler_registro.avisos_da_leitura([raiz], linhas))
        escreve(raiz, "logcat-crash.txt", "11-07 10:05:00.000  500  600 F libc    : Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0 in tid 600 (x), pid 500 (com.reau.app)\n")
        do_reau = ler_registro._crash_da_coleta([raiz], linhas)
        self.assertTrue(do_reau[0][1])
        feito = subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), str(raiz)], capture_output=True, text=True, encoding="utf-8")
        self.assertEqual(1, feito.returncode)  # o processo do REau que caiu é problema
        self.assertIn("com.reau.app caiu com SIGSEGV", feito.stdout)

    def test_a_lacuna_das_sessoes_e_do_video_e_dita_quando_a_coleta_as_tem(self):
        raiz = Path(tempfile.mkdtemp())
        escreve(raiz, "diag/ride-20261107-100000.log", projecao())
        (raiz / "sessions").mkdir()
        a = [x for x in ler_registro.avisos_da_leitura([raiz], ler_registro.ler([raiz])) if "lacuna conhecida" in x]
        self.assertEqual(1, len(a))
        self.assertIn("não lê as gravações das sessões", a[0])
        sem = Path(tempfile.mkdtemp())
        escreve(sem, "diag/ride-20261107-100000.log", projecao())
        self.assertEqual([], [x for x in ler_registro.avisos_da_leitura([sem], ler_registro.ler([sem])) if "lacuna conhecida" in x])


class ColetaDeReferencia(unittest.TestCase):
    """A coleta de 07/11 (só leitura; se a pasta não estiver aqui, o teste é pulado): os falsos alarmes sumiram e o resultado bate com a análise."""

    RAIZ = AQUI.parents[2] / "reau_registros" / "celular-20261107-2327"

    @classmethod
    def setUpClass(cls):
        if not cls.RAIZ.is_dir():
            raise unittest.SkipTest("a coleta de 07/11 não está nesta cópia")
        feito = subprocess.run([sys.executable, "-I", "-B", str(AQUI / "ler_registro.py"), str(cls.RAIZ)], capture_output=True, text=True, encoding="utf-8")
        cls.saida = feito.stdout
        cls.problemas = cls.saida.split("PROBLEMAS:")[1].split("TESTE DO ITEM 1a")[0]

    def test_os_falsos_alarmes_sumiram(self):
        for ausente in ("RX watchdog", "laço de espera", "not a single packet", "tela \"apps\" por 9 min", "esperou o app voltar"):
            self.assertNotIn(ausente, self.problemas)
        self.assertEqual(1, self.problemas.count("não traz desconexão") + self.problemas.count("sem desconexão"))

    def test_o_veredito_do_1a_o_eperm_e_o_tempo_sem_imagem_batem_com_a_analise(self):
        self.assertIn("build 0.3.0-dev+26.11.05-00.25 (29897485, 7eee9cb): PROVADO", self.saida)
        self.assertIn("`location` em 11 de 11 partidas, 0 recusas", self.saida)
        self.assertIn("app atrás 247 com redes e 4 vazias", self.saida)
        self.assertIn("lugar sem outra rede, não falta de acesso", self.saida)
        self.assertIn("EPERM e, 0 s depois, outra rede já unida, mas o painel só foi tentado 30 s depois", self.problemas)
        self.assertIn("sem imagem 226,7 s desde a queda das 17:44:06 (86,7 s com o painel ligado", self.saida)

    def test_so_as_corridas_de_05_a_07_11_entram_pelo_corte_da_coleta_anterior(self):
        self.assertIn("recorte: só os registros que acabam depois da coleta anterior (celular-20261104-2331, 04/11 23:31)", self.saida)
        self.assertNotIn("03/11:", self.saida)


if __name__ == "__main__":
    unittest.main()
