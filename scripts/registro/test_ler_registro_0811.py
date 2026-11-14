"""Testes das correções do leitor feitas a partir da coleta de 08/11/2026 (análise da coleta, §3 achados 4, 6 e 7 e §5):
quem pediu cada união ao Wi-Fi do painel (o `uid`, não só `NOMINATOR_SPECIFIER`), o `TX send failing` que é exceção de política e não queda, e as linhas do ramo do painel
(`panel settings:`, `nav distances in …`, `dash says it changed a setting`). Trechos sintéticos; o último grupo usa a coleta de 08/11 e pula se a pasta não existir.

    python -B scripts/registro/test_ler_registro_0811.py
"""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import ler_registro  # noqa: E402
import wifi_sistema  # noqa: E402
from test_ler_registro import escreve, linha, projecao  # noqa: E402

COLETA_0811 = AQUI.parents[2] / "reau_registros" / "celular-20261108-1458"


def uniao(quando, uid, nominador="NOMINATOR_SPECIFIER", ok=1, ms=700):
    """Uma linha de `mConnectionEvents` do painel (11-8 = 08/11), com nome de rede e BSSID que não podem sair."""
    return (f'startTime=11-8 {quando}, SSID="RE_AB12_345678", BSSID=segredo-bssid-01, durationMillis={ms}, connectionResult={ok}, signalStrength=-50, '
            f"mChannelInfo=2422, connectionNominator={nominador}, uid={uid}\n")


def nome_de_uid(uid, pacote):
    return f'2026-11-08T00:41:08.060055 - addOrUpdateNetworkInternal: added/updated config. netId=129 configKey="RE_AB12_345678"WPA_PSK-0 uid={uid} name={pacote}\n'


def dumpsys(*linhas):
    return "mConnectionEvents:\n" + "".join(linhas) + "mWifiLogProto.numSavedNetworks=1\nStaEventList:\n" + "mLinkProbeStaEventCount:0\n"


def coleta(wifi, info="10\nuser,com.reau.app,10492\n", conectividade=None, ride=None, wifi_nome="sistema/dumpsys-wifi.txt"):
    """Uma coleta sintética: `diag/ride-20261108-100000.log` (10:00 a 10:09), o dumpsys do Wi-Fi e, se dados, `INFO.txt` e o dumpsys do conectividade."""
    raiz = Path(tempfile.mkdtemp())
    escreve(raiz, "diag/ride-20261108-100000.log", ride or projecao())
    escreve(raiz, wifi_nome, wifi)
    if info is not None:
        escreve(raiz, "INFO.txt", info)
    if conectividade is not None:
        escreve(raiz, "sistema/dumpsys-connectivity.txt", conectividade)
    return raiz


def achados(raiz):
    linhas = ler_registro.ler([raiz / "diag"])
    return (ler_registro.do_dumpsys_wifi([raiz / "diag"], linhas), ler_registro.avisos_do_dumpsys_wifi([raiz / "diag"], linhas))


def sem_registro(problemas):
    return [a for a in problemas if "sem nenhum registro do REau" in a]


class QuemUniuOPainel(unittest.TestCase):
    """O `NOMINATOR_SPECIFIER` é de qualquer app: de quem é a união vem do `uid` (08/11: o app antigo, o app oficial da Royal Enfield e o sistema, não o REau)."""

    def test_so_a_uniao_do_uid_do_reau_e_problema_a_dos_outros_apps_e_do_sistema_e_aviso(self):
        raiz = coleta(dumpsys(
            uniao("3:10:5.000", 10492),                                  # the REau's, with no registro at 03:10
            uniao("0:41:8.185", 10223), uniao("0:41:30.254", 10223), uniao("0:46:5.450", 10223, ok=0, ms=659),
            uniao("8:34:28.174", 1010, "NOMINATOR_SAVED"),
            uniao("8:35:17.539", 10238),
            uniao("8:34:18.510", 10185, "NOMINATOR_UNKNOWN"),
        ), conectividade="NetworkRequest [ REQUEST id=14 RequestorUid: 10185 RequestorPkg: com.android.systemui UnderlyingNetworks: Null] ]\n")
        # the names of the apps come from the Wi-Fi dumpsys itself (when a network is created) and from the connectivity one
        with open(raiz / "sistema/dumpsys-wifi.txt", "a", encoding="utf-8") as f:
            f.write(nome_de_uid(10223, "com.exemplo.outroapp") + nome_de_uid(10238, "com.exemplo.fabricante"))
        problemas, avisos = achados(raiz)
        unioes = sem_registro(problemas)
        self.assertEqual(1, len(unioes), unioes)
        self.assertTrue(unioes[0].startswith("08/11 03:10:05"), unioes[0])
        self.assertIn("pedido do REau, uid 10492", unioes[0])
        texto = "\n".join(avisos)
        self.assertEqual(4, len(avisos), avisos)
        for esperado in ("com.exemplo.outroapp (uid 10223)", "2 uniões, 1 tentativa sem sucesso", "com.exemplo.fabricante (uid 10238)",
                         "com.android.systemui (uid 10185)", "o sistema do Android (uid 1010)", "rede salva", "não pelo REau"):
            self.assertIn(esperado, texto)
        self.assertNotIn("o REau)", texto.replace("não pelo REau", ""))

    def test_a_uniao_de_outro_app_nao_entra_em_problemas_mesmo_sendo_pedido_de_app(self):
        problemas, avisos = achados(coleta(dumpsys(uniao("3:10:5.000", 10223))))
        self.assertEqual([], sem_registro(problemas))
        self.assertEqual(1, len(avisos), avisos)
        self.assertIn("um app sem nome nesta coleta (uid 10223)", avisos[0])

    def test_o_uid_do_reau_vem_do_nome_do_pacote_no_dumpsys_sem_o_info(self):
        raiz = coleta(dumpsys(uniao("3:10:5.000", 10492), uniao("4:10:5.000", 10223), nome_de_uid(10492, "com.reau.app")), info=None)
        problemas, avisos = achados(raiz)
        self.assertEqual(1, len(sem_registro(problemas)), problemas)
        self.assertEqual(1, len(avisos), avisos)

    def test_sem_info_nem_nome_o_uid_que_pediu_a_rede_enquanto_o_app_registrava_e_o_do_reau(self):
        # The union at 10:00:03 is inside the registro (10:00 to 10:09): its uid is the REau's; the 03:10 ones are told apart by it.
        raiz = coleta(dumpsys(uniao("10:0:3.000", 10492), uniao("3:10:5.000", 10492), uniao("4:10:5.000", 10223)), info=None)
        problemas, avisos = achados(raiz)
        self.assertEqual(1, len(sem_registro(problemas)), problemas)
        self.assertEqual(1, len(avisos), avisos)

    def test_sem_como_saber_o_uid_do_reau_o_pedido_de_app_continua_problema_e_diz_por_que(self):
        problemas, avisos = achados(coleta(dumpsys(uniao("3:10:5.000", 10223)), info=None))
        unioes = sem_registro(problemas)
        self.assertEqual(1, len(unioes), unioes)
        self.assertIn("a coleta não diz o uid do REau", unioes[0])
        self.assertEqual([], avisos)

    def test_a_sugestao_de_rede_do_sistema_com_o_reau_fechado_continua_sendo_do_reau(self):
        problemas, avisos = achados(coleta(dumpsys(uniao("3:10:5.000", 1010, "NOMINATOR_SUGGESTION"))))
        unioes = sem_registro(problemas)
        self.assertEqual(1, len(unioes), unioes)
        self.assertIn("sugestão de rede do sistema", unioes[0])
        self.assertEqual([], avisos)

    def test_uid_do_info_sem_o_lite_e_a_linha_do_pacote_lite_sem_uid_nao_confundem(self):
        raiz = coleta(dumpsys(), info="--- com.reau.app.lite\n10\nuser,com.reau.app,10492\n--- outro\nuser,com.outro.app,10111\n")
        nomes = wifi_sistema.nomes_dos_uids(wifi_sistema.arquivo([raiz]))
        self.assertEqual({"10492"}, wifi_sistema.uids_do_reau([raiz], wifi_sistema.arquivo([raiz]), nomes))

    def test_nem_o_nome_da_rede_nem_o_bssid_saem(self):
        problemas, avisos = achados(coleta(dumpsys(uniao("3:10:5.000", 10492), uniao("4:10:5.000", 10223))))
        texto = "\n".join(problemas + avisos)
        for segredo in ("segredo-bssid", "RE_AB12", "345678", "SSID", "BSSID"):
            self.assertNotIn(segredo, texto)

    def test_o_aviso_vai_para_a_secao_de_avisos_da_saida_do_programa_e_nao_para_os_problemas(self):
        raiz = coleta(dumpsys(uniao("4:10:5.000", 10223), nome_de_uid(10223, "com.exemplo.outroapp")))
        feito = subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), str(raiz / "diag")], capture_output=True, text=True, encoding="utf-8")
        problemas, _, avisos = feito.stdout.partition("AVISOS DA LEITURA")
        self.assertIn("com.exemplo.outroapp (uid 10223)", avisos)
        self.assertNotIn("com.exemplo.outroapp", problemas)
        self.assertNotIn("sem nenhum registro do REau", feito.stdout)


def detalhe(hora, texto):
    return f"{hora}  +     0ms  [detail] {texto}\n"


class EnvioRecusadoPelaPolitica(unittest.TestCase):
    """`TX send failing (link down?): NetworkOnMainThreadException` (08/11, 12:58:34 a 12:58:46, o Wi-Fi a -51 dBm): é defeito do app, não queda."""

    def problemas(self, *meio):
        return ler_registro.problemas(ler_registro.ler([_pasta(projecao(*meio))]))

    def test_a_excecao_de_thread_principal_e_acusada_como_defeito_do_app_e_nao_como_queda(self):
        achados = self.problemas(linha("10:03:00.000", "dash", "W: TX send failing (link down?): NetworkOnMainThreadException: null"),
                                 linha("10:03:01.000", "dash", "TX send recovered after 1 failures"),
                                 linha("10:03:10.000", "dash", "W: TX send failing (link down?): NetworkOnMainThreadException: null"))
        politica = [a for a in achados if "NetworkOnMainThreadException" in a]
        self.assertEqual(1, len(politica), achados)
        for esperado in ("defeito do app", "não queda do Wi-Fi", "política de threads", "thread principal", "NÃO saiu", "2 vezes", "10:03:00.000 a 10:03:10.000"):
            self.assertIn(esperado, politica[0])

    def test_o_texto_novo_do_ramo_tambem_e_reconhecido(self):
        achados = self.problemas(linha("10:03:00.000", "dash", "W: TX send refused: NetworkOnMainThreadException (the caller was the main thread, not the link); nothing was sent"))
        self.assertEqual(1, len([a for a in achados if "defeito do app" in a]), achados)

    def test_a_falha_de_rede_de_verdade_nao_e_acusada_como_defeito(self):
        achados = self.problemas(linha("10:03:00.000", "dash", "W: TX send failing (link down?): SocketException: sendto failed: ENETUNREACH (Network is unreachable)"))
        self.assertEqual([], [a for a in achados if "defeito do app" in a])

    def test_o_envio_rtp_tem_a_sua_propria_linha(self):
        achados = self.problemas(linha("10:03:00.000", "dash", "W: RTP send failing (link down?): NetworkOnMainThreadException: null"))
        self.assertEqual(1, len([a for a in achados if "o envio RTP ao painel" in a]), achados)


def _pasta(texto, nome="ride-20261108-100000.log"):
    d = Path(tempfile.mkdtemp())
    escreve(d, nome, texto)
    return d


ESCRITA = "panel settings: writing 04/01=55 04/02=AA 04/03=10 04/04=10 04/06=55 04/07=04 04/08=AAAAAAAAAA 04/09=55555555 (changes: distance KM -> MILES); the dash said distance KM"
ENVIADA = "panel settings: sent once; asking for the data again (0A 02) once and waiting up to 6.0 s for the dash to say group 03 again"


class LinhasDoRamoDoPainel(unittest.TestCase):
    """`panel settings:`, `nav distances in …` e `dash says it changed a setting`: na linha do tempo, e os achados da escrita em PROBLEMAS."""

    def tempo(self, *meio):
        return "\n".join(ler_registro.linha_do_tempo(ler_registro.ler([_pasta(projecao(*meio))])))

    def da_escrita(self, *meio):
        pasta = _pasta(projecao(*meio))
        return ler_registro.da_escrita_no_painel([pasta], ler_registro.ler([pasta]))

    def test_a_escrita_confirmada_vai_para_a_linha_do_tempo_com_o_pedido(self):
        t = self.tempo(linha("10:03:00.000", "dash", ESCRITA), linha("10:03:00.001", "dash", ENVIADA),
                       linha("10:03:01.400", "dash", "panel settings: CONFIRMED after 1.4 s: the dash says 03/04=AA"))
        for esperado in ("escrita no painel pedida pelo dono: distance KM -> MILES", "o bloco foi ao fio uma vez", "escrita no painel: CONFIRMADA em 1.4 s (the dash says 03/04=AA)"):
            self.assertIn(esperado, t)

    def test_nao_confirmada_nao_escrita_recusada_e_cancelada_tem_cada_uma_a_sua_linha(self):
        t = self.tempo(linha("10:03:00.000", "dash", "panel settings: NOT confirmed after 6.0 s: the dash did not say 03/04=AA again; not trying again"),
                       linha("10:03:10.000", "dash", "panel settings: not written (the dash is not connected)"),
                       linha("10:03:20.000", "dash", "panel settings: the socket refused the block; nothing else is tried"),
                       linha("10:03:30.000", "dash", "panel settings: the wait was cancelled; the block was sent and nothing more will be"))
        for esperado in ("NÃO confirmada em 6.0 s", "não escrita (the dash is not connected)", "o soquete recusou o bloco, NADA foi enviado", "a espera foi cancelada"):
            self.assertIn(esperado, t)

    def test_o_pedido_de_novo_que_nao_foi_ao_fio_tem_a_sua_linha(self):
        t = self.tempo(linha("10:03:01.000", "dash", "panel settings: the ask for the data (0A 02) did not go to the wire; the wait goes on for the dash to say group 03 by itself"),
                       linha("10:03:02.000", "dash", "panel settings: the socket refused the block (nothing went to the wire, nothing was changed); nothing else is tried"))
        self.assertIn("o pedido dos dados de novo (0A 02) não foi ao fio; a espera segue", t)
        self.assertIn("o soquete recusou o bloco, NADA foi enviado ao painel (SEND_FAILED)", t)

    def test_a_unidade_das_distancias_da_guiagem(self):
        t = self.tempo(linha("10:03:00.000", "dash", "nav distances in feet and tenths of a mile (the dash says miles)"),
                       linha("10:03:10.000", "dash", "nav distances in metres and tenths of a kilometre (the dash says km)"))
        self.assertIn("guiagem: distâncias em pés e décimos de milha (the dash says miles)", t)
        self.assertIn("guiagem: distâncias em metros e décimos de quilômetro (the dash says km)", t)

    def test_o_aviso_09_05_do_painel(self):
        t = self.tempo(
            linha("10:03:00.000", "dash", "dash says it changed a setting (09 05 = 01): nothing is sent back; asking for its data again (0A 02) to read group 03"),
            linha("10:03:10.000", "dash", "dash says it changed a setting (09 05 = 01): nothing is sent back; not asking again (asked a moment ago, or 5 times already)"),
            linha("10:03:20.000", "dash", "dash says 09 05 = 02, not the 01 of a changed setting: nothing done"))
        self.assertIn("o app não manda nada de volta e pede os dados de novo (0A 02)", t)
        self.assertIn("não pede de novo", t)
        self.assertIn("o painel disse 09 05 = 02 (não é o 01 de ajuste mudado): nada feito", t)

    def test_o_que_o_app_diz_enviado_sem_pacote_04_no_registro_de_pacotes_e_acusado(self):
        # 08/11 12:58:34: "sent once" with the packet log on (the 0556 beats are there) and no 04xx nor 0A02.
        achados = self.da_escrita(linha("10:03:00.000", "dash", ESCRITA), linha("10:03:00.001", "dash", ENVIADA),
                                  detalhe("10:02:59.900", "dash tx 0556:55"), detalhe("10:03:00.300", "dash tx 0556:55"),
                                  linha("10:03:06.100", "dash", "panel settings: NOT confirmed after 6.0 s: the dash did not say 03/04=AA again; not trying again"))
        self.assertEqual(1, len([a for a in achados if "nada foi ao fio" in a]), achados)
        self.assertEqual(1, len([a for a in achados if "NÃO confirmada" in a]), achados)

    def test_com_o_pacote_04_no_registro_de_pacotes_nao_ha_acusacao_de_nada_no_fio(self):
        achados = self.da_escrita(linha("10:03:00.000", "dash", ESCRITA), linha("10:03:00.001", "dash", ENVIADA),
                                  detalhe("10:03:00.002", "dash tx 0401:55 0402:AA 0403:10 0407:04"), detalhe("10:03:01.100", "dash tx 0A02"),
                                  linha("10:03:01.400", "dash", "panel settings: CONFIRMED after 1.4 s: the dash says 03/04=AA"))
        self.assertEqual([], achados)

    def test_sem_registro_de_pacotes_nao_se_acusa_nada_pelo_pacote_que_falta(self):
        achados = self.da_escrita(linha("10:03:00.000", "dash", ESCRITA), linha("10:03:00.001", "dash", ENVIADA),
                                  linha("10:03:01.400", "dash", "panel settings: CONFIRMED after 1.4 s: the dash says 03/04=AA"))
        self.assertEqual([], achados)

    def test_o_soquete_que_recusou_o_bloco_e_acusado(self):
        achados = self.da_escrita(linha("10:03:00.000", "dash", ESCRITA),
                                  linha("10:03:00.001", "dash", "panel settings: the socket refused the block; nothing else is tried"))
        self.assertEqual(1, len(achados), achados)
        self.assertIn("NÃO enviada (distance KM -> MILES)", achados[0])
        self.assertIn("SEND_FAILED", achados[0])


@unittest.skipUnless(COLETA_0811.is_dir(), "a coleta de 08/11/2026 não está nesta máquina")
class ColetaDe0811(unittest.TestCase):
    """O leitor sobre a coleta real de 08/11/2026 (`reau_registros/celular-20261108-1458`, só leitura)."""

    @classmethod
    def setUpClass(cls):
        feito = subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), str(COLETA_0811)], capture_output=True, text=True, encoding="utf-8")
        cls.saida = feito.stdout
        cls.problemas, _, cls.avisos = feito.stdout.partition("AVISOS DA LEITURA")

    def test_as_17_uniões_de_outros_apps_saem_dos_problemas(self):
        self.assertNotIn("sem nenhum registro do REau", self.problemas)
        self.assertNotIn("pedido de um app (o REau)", self.saida)

    def test_cada_origem_e_nomeada_nos_avisos(self):
        for esperado in ("(uid 10223)", "10 uniões, 3 tentativas sem sucesso", "(uid 10238)", "59 min 47 s",
                         "o sistema do Android (uid 1010)", "com.android.systemui (uid 10185)"):
            self.assertIn(esperado, self.avisos)

    def test_a_uniao_do_reau_nao_aparece_como_de_outro(self):
        self.assertNotIn("uid 10492", self.avisos)

    def test_o_defeito_do_item_4_do_ramo_e_acusado(self):
        for esperado in ("defeito do app, não queda do Wi-Fi", "NetworkOnMainThreadException", "4 vezes", "nada foi ao fio", "NÃO confirmada (distance KM -> MILES)"):
            self.assertIn(esperado, self.problemas)
        self.assertIn("escrita no painel pedida pelo dono: temperature CELSIUS -> FAHRENHEIT", self.saida)

    def test_nenhum_nome_de_rede_sai(self):
        self.assertNotRegex(self.saida, r"RE_[A-Z0-9]{4}_[0-9]{6}")
        self.assertNotRegex(self.saida, r"(?i)\b(?:[0-9a-f]{2}:){5}[0-9a-f]{2}\b")


if __name__ == "__main__":
    unittest.main()
