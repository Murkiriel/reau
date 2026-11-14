"""Tests of gravacoes.py: the sessions (`sessions/`) and the video (`video/`) of a collection read beside the log (item 36, 11/11/2026). The recordings are built here, byte by byte,
in the format of SessionRecorder.kt; nothing reads a real one."""
import os
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

AQUI = Path(__file__).resolve().parent
sys.path.insert(0, str(AQUI))
import gravacoes  # noqa: E402
import ler_registro  # noqa: E402
from test_ler_registro import escreve, linha  # noqa: E402


def mensagem(t_ms, canal, carga):
    return struct.pack(">iBBi", t_ms, canal, 3, len(carga)) + carga


def sessao(mensagens, assinatura=b"REAA", cortar=0):
    dados = assinatura + b"\x01" + struct.pack(">q", 1_700_000_000_000) + b"".join(mensagens)
    return dados[:len(dados) - cortar] if cortar else dados


QUADRO = b"\x00\x00" + b"\x00" * 8 + b"\x65\x88"  # type 0 = a frame
CONFIG = b"\x00\x01" + b"\x67\x42"                 # type 1 = SPS/PPS


def csv_novo(pasta, nome, tamanhos, pts_passo=250, chaves=(0,)):
    linhas = ["frame,pts_ms,bytes_in_file,key"] + [f"{i + 1},{i * pts_passo},{t},{1 if i in chaves else 0}" for i, t in enumerate(tamanhos)]
    (Path(pasta) / f"{nome}.csv").write_text("\n".join(linhas) + "\n")
    (Path(pasta) / f"{nome}.h264").write_bytes(b"\x00" * sum(tamanhos))


def coleta():
    raiz = Path(tempfile.mkdtemp())
    (raiz / "sessions").mkdir()
    (raiz / "video").mkdir()
    (raiz / "diag").mkdir()
    return raiz


class LerSessaoTest(unittest.TestCase):
    def arquivo(self, dados, nome="reau_20261108_100000_000.aasession"):
        d = Path(tempfile.mkdtemp())
        (d / nome).write_bytes(dados)
        return d / nome

    def test_messages_seconds_and_video_frames_are_counted_from_the_headers(self):
        s = gravacoes.ler_sessao(self.arquivo(sessao([mensagem(0, 2, CONFIG), mensagem(500, 2, QUADRO), mensagem(1500, 2, QUADRO), mensagem(2500, 0, b"\x00\x05abc")])))
        self.assertEqual((True, 4, 2.5, 2, False), (s["assinatura"], s["mensagens"], s["segundos"], s["quadros"], s["cortada"]))

    def test_a_file_that_ends_in_the_middle_of_a_message_is_flagged_and_keeps_what_came_before(self):
        dados = sessao([mensagem(0, 2, QUADRO), mensagem(900, 2, QUADRO)], cortar=5)
        s = gravacoes.ler_sessao(self.arquivo(dados))
        self.assertEqual((1, 0.0, True), (s["mensagens"], s["segundos"], s["cortada"]))

    def test_a_cut_inside_a_header_is_flagged_too(self):
        s = gravacoes.ler_sessao(self.arquivo(sessao([mensagem(0, 2, QUADRO)]) + b"\x00\x00\x03"))
        self.assertTrue(s["cortada"])
        self.assertEqual(1, s["mensagens"])

    def test_the_old_signature_is_read_and_an_unknown_one_is_not_a_recording(self):
        self.assertTrue(gravacoes.ler_sessao(self.arquivo(sessao([mensagem(0, 2, QUADRO)], assinatura=b"PGAA")))["assinatura"])
        s = gravacoes.ler_sessao(self.arquivo(b"XXXX" + b"\x00" * 30))
        self.assertEqual((False, 0), (s["assinatura"], s["mensagens"]))
        self.assertFalse(gravacoes.ler_sessao(self.arquivo(b"RE"))["assinatura"])

    def test_a_file_with_only_the_header_has_no_messages(self):
        s = gravacoes.ler_sessao(self.arquivo(sessao([])))
        self.assertEqual((True, 0, False), (s["assinatura"], s["mensagens"], s["cortada"]))

    def test_a_non_video_message_on_channel_two_shorter_than_a_type_is_not_a_frame(self):
        s = gravacoes.ler_sessao(self.arquivo(sessao([mensagem(0, 2, b"\x00")])))
        self.assertEqual((1, 0), (s["mensagens"], s["quadros"]))


class LerVideoTest(unittest.TestCase):
    def test_frames_seconds_keyframes_and_kbps_come_from_the_index(self):
        d = Path(tempfile.mkdtemp())
        csv_novo(d, "reau_20261108_100000_000", [1000, 500, 500, 2000], pts_passo=1000, chaves=(0, 3))
        v = gravacoes.ler_video(d / "reau_20261108_100000_000.h264")
        self.assertEqual((4, 3.0, 2, 4000), (v["quadros"], v["segundos"], v["chaves"], v["bytes"]))
        self.assertAlmostEqual(4000 * 8 / 1000 / 3.0, v["kbps"])
        self.assertEqual([], v["avisos"])

    def test_an_index_that_does_not_match_the_file_is_a_warning_and_a_missing_index_is_told(self):
        d = Path(tempfile.mkdtemp())
        csv_novo(d, "reau_20261108_100000_000", [1000, 500])
        (d / "reau_20261108_100000_000.h264").write_bytes(b"\x00" * 10)
        self.assertEqual(1, len(gravacoes.ler_video(d / "reau_20261108_100000_000.h264")["avisos"]))
        (d / "reau_20261108_100100_000.h264").write_bytes(b"\x00" * 10)
        self.assertTrue(gravacoes.ler_video(d / "reau_20261108_100100_000.h264")["sem_indice"])

    def test_an_unreadable_index_becomes_a_warning_and_not_an_error(self):
        d = Path(tempfile.mkdtemp())
        (d / "reau_20261108_100000_000.csv").write_text("x,y\n1,2\n")
        (d / "reau_20261108_100000_000.h264").write_bytes(b"\x00")
        self.assertIn("o índice não se lê", gravacoes.ler_video(d / "reau_20261108_100000_000.h264")["avisos"][0])


class NomesTest(unittest.TestCase):
    def test_the_start_comes_from_the_name_of_every_kind_of_file(self):
        from datetime import datetime
        esperado = datetime(2026, 11, 8, 17, 58, 9, 640000)
        for nome in ("reau_20261108_175809_640.aasession", "reau_20261108_175809_640.1.aasession", "reau_20261108_175809_640_marca.aasession",
                     "reau_20261108_175809_640_p02.h264", "reau_20261108_175809_640.csv"):
            self.assertEqual(esperado, gravacoes.inicio_do_nome(nome), nome)
        self.assertIsNone(gravacoes.inicio_do_nome("logcat-20261108.txt"))
        self.assertIsNone(gravacoes.inicio_do_nome("reau_20261308_175809_640.aasession"))


class ContasDoAppTest(unittest.TestCase):
    def linhas(self, *textos):
        return ler_registro.ler([self.pasta(*textos)])

    def pasta(self, *textos):
        d = Path(tempfile.mkdtemp())
        escreve(d, "ride-20261108-100000.log", "".join(linha("10:00:00.000", "aa", t) for t in textos))
        return d

    def test_the_closed_session_the_roll_the_video_and_the_space_are_read(self):
        c = gravacoes.contas_do_app(self.linhas(
            "[AA] session recording closed: 9992 messages, 38801755 bytes, /data/user/0/com.reau.app/files/sessions/reau_20261108_125815_044.aasession — covers the first 320 s",
            "[AA] session recording rolled over (#1): reau_20261108_175809_640.1.aasession keeps the first 1815 s of that part; the part before it is dropped",
            "[AA] session recording closed: 92526 messages, 185503056 bytes in the last part, /data/user/0/com.reau.app/files/sessions/reau_20261108_175809_640.aasession — the last part covers 1090 s, after 1 roll",
            "video recording stopped early: 859 frames, 8175258 bytes, /data/user/0/com.reau.app/files/video/reau_20261108_125824_856.h264",
            "video recording part 1 complete, the next follows: 3000 frames, 29531904 bytes, /data/user/0/com.reau.app/files/video/reau_20261108_175831_340.h264",
            "recordings space: 3 files removed (45351 KB), 1042833 KB left of 1048576 KB"))
        self.assertEqual({"mensagens": 9992, "bytes": 38801755, "ultima_parte": False, "cobre_s": 320}, c["sessoes"]["reau_20261108_125815_044.aasession"])
        self.assertTrue(c["sessoes"]["reau_20261108_175809_640.aasession"]["ultima_parte"])
        self.assertEqual([(1, "reau_20261108_175809_640.1.aasession", 1815)], c["rolagens"])
        self.assertEqual({"quadros": 859, "bytes": 8175258}, c["videos"]["reau_20261108_125824_856.h264"])
        self.assertEqual(3000, c["videos"]["reau_20261108_175831_340.h264"]["quadros"])
        self.assertEqual([(3, 45351, 1042833, 1048576)], c["espaco"])

    def test_one_file_removed_in_the_singular_is_read_too(self):
        self.assertEqual([(1, 62978, 1035507, 1048576)], gravacoes.contas_do_app(self.linhas("recordings space: 1 file removed (62978 KB), 1035507 KB left of 1048576 KB"))["espaco"])


def registro_com(hora_ligar, hora_desligar, *meio, nome="ride-20261108-100000.log"):
    return (f"{hora_ligar}  +  1000ms  ---- projeção ligada: projection ----\n" + "".join(meio) +
            f"{hora_desligar}  +  1000ms  ---- projeção desligada: projection ----\n")


def resumo(hora, frames):
    return linha(hora, "projection", f"resumo duration=10m00s streaming=9m00s drops dash=0 aa=0 watchdogs=0 frames={frames} fps avg=4.0 min=2.0 gap max=0.3s thermal max=none battery 80→79% (-1) marks=0")


class SecaoTest(unittest.TestCase):
    def montar(self, log, preparar):
        raiz = coleta()
        escreve(raiz / "diag", "ride-20261108-100000.log", log)
        preparar(raiz)
        linhas = ler_registro.ler([raiz / "diag"])
        return raiz, linhas, ler_registro.projecoes(linhas)

    def test_the_recording_is_tied_to_its_projection_with_the_coverage_and_the_frame_count_of_the_resumo(self):
        def preparar(raiz):
            (raiz / "sessions" / "reau_20261108_100005_000.aasession").write_bytes(sessao([mensagem(0, 2, QUADRO), mensagem(540_000, 2, QUADRO)]))
            csv_novo(raiz / "video", "reau_20261108_100006_000", [100, 100, 100], chaves=(0,))
        raiz, linhas, blocos = self.montar(registro_com("10:00:01.000", "10:10:00.000", resumo("10:09:59.000", 4)), preparar)
        s = gravacoes.secao(raiz, linhas, blocos)
        texto = "\n".join(s)
        self.assertIn("1 arquivo de sessão, 540 s gravados de 599 s de projeção (90 %)", texto)
        self.assertIn("sessão reau_20261108_100005_000.aasession:", texto)
        self.assertIn("1 arquivo de vídeo, 3 quadros; o resumo da projeção diz frames=4 e os índices somam 3 (-1)", texto)
        self.assertIn("1 quadros-chave", texto)

    def test_a_recording_goes_to_one_projection_only_the_last_that_had_started(self):
        def preparar(raiz):
            (raiz / "sessions" / "reau_20261108_100530_000.aasession").write_bytes(sessao([mensagem(0, 2, QUADRO)]))
        log = registro_com("10:00:01.000", "10:10:00.000") + registro_com("10:05:00.000", "10:20:00.000")
        raiz, linhas, blocos = self.montar(log, preparar)
        s = gravacoes.secao(raiz, linhas, blocos)
        titulos = [l for l in s if l.startswith("projeção de")]
        self.assertEqual(1, len(titulos), s)
        self.assertIn("10:05:00", titulos[0])

    def test_the_parts_of_a_rolled_ring_are_both_listed_and_the_last_part_is_not_compared_with_the_app(self):
        def preparar(raiz):
            (raiz / "sessions" / "reau_20261108_100005_000.1.aasession").write_bytes(sessao([mensagem(0, 2, QUADRO), mensagem(100_000, 2, QUADRO)]))
            (raiz / "sessions" / "reau_20261108_100005_000.aasession").write_bytes(sessao([mensagem(0, 2, QUADRO), mensagem(200_000, 2, QUADRO)]))
        log = registro_com("10:00:01.000", "10:10:00.000",
                           linha("10:09:00.000", "aa", "[AA] session recording rolled over (#1): reau_20261108_100005_000.1.aasession keeps the first 100 s of that part; the part before it is dropped"),
                           linha("10:09:59.000", "aa", "[AA] session recording closed: 9999 messages, 77 bytes in the last part, /d/files/sessions/reau_20261108_100005_000.aasession — the last part covers 200 s, after 1 roll"))
        raiz, linhas, blocos = self.montar(log, preparar)
        s = "\n".join(gravacoes.secao(raiz, linhas, blocos))
        self.assertIn("2 arquivos de sessão, 300 s gravados", s)
        self.assertIn("parte anterior (o anel rolou)", s)
        self.assertNotIn("o app fechou com", s)
        self.assertNotIn("o app contou", s)
        self.assertIn("anel das sessões rolou: #1 (reau_20261108_100005_000.1.aasession guarda os primeiros 100 s da parte)", s)

    def test_a_whole_session_whose_size_the_app_did_not_confirm_is_told_with_both_numbers(self):
        def preparar(raiz):
            (raiz / "sessions" / "reau_20261108_100005_000.aasession").write_bytes(sessao([mensagem(0, 2, QUADRO)]))
        log = registro_com("10:00:01.000", "10:10:00.000", linha("10:09:59.000", "aa", "[AA] session recording closed: 1 messages, 99999 bytes, /d/files/sessions/reau_20261108_100005_000.aasession — covers the first 1 s"))
        s = "\n".join(gravacoes.secao(*self._tres(log, preparar)))
        self.assertIn("o app fechou com 99999 B e a pasta tem", s)

    def test_a_cut_recording_and_one_that_is_not_a_recording_are_flagged(self):
        def preparar(raiz):
            (raiz / "sessions" / "reau_20261108_100005_000.aasession").write_bytes(sessao([mensagem(0, 2, QUADRO)], cortar=3))
            (raiz / "sessions" / "reau_20261108_100006_000.aasession").write_bytes(b"lixo")
        s = "\n".join(gravacoes.secao(*self._tres(registro_com("10:00:01.000", "10:10:00.000"), preparar)))
        self.assertIn("acaba no meio de uma mensagem", s)
        self.assertIn("NÃO é uma gravação", s)

    def _tres(self, log, preparar):
        raiz, linhas, blocos = self.montar(log, preparar)
        return raiz, linhas, blocos

    def test_a_recording_that_started_half_a_minute_before_the_projection_is_still_tied_to_it_and_one_five_minutes_before_is_not(self):
        def preparar(raiz):
            (raiz / "sessions" / "reau_20261108_095930_000.aasession").write_bytes(sessao([mensagem(0, 2, QUADRO)]))  # 31 s before 10:00:01
            (raiz / "sessions" / "reau_20261108_095500_000.aasession").write_bytes(sessao([mensagem(0, 2, QUADRO)]))  # 5 min before
        s = "\n".join(gravacoes.secao(*self._tres(registro_com("10:00:01.000", "10:10:00.000"), preparar)))
        self.assertIn("sessão reau_20261108_095930_000.aasession", s)
        self.assertNotIn("sessão reau_20261108_095500_000.aasession", s)

    def test_sessions_longer_than_the_projection_read_say_the_log_ends_before_the_recording(self):
        def preparar(raiz):
            (raiz / "sessions" / "reau_20261108_100005_000.aasession").write_bytes(sessao([mensagem(0, 2, QUADRO), mensagem(700_000, 2, QUADRO)]))
        s = "\n".join(gravacoes.secao(*self._tres(registro_com("10:00:01.000", "10:10:00.000"), preparar)))
        self.assertIn("700 s gravados de 599 s de projeção (117 %): mais que a projeção lida", s)

    def test_sixty_percent_of_the_ring_is_not_a_warning(self):
        def preparar(raiz):
            with open(raiz / "sessions" / "reau_20261108_100005_000.aasession", "wb") as f:
                f.truncate(600 * 1048576)
        self.assertNotIn("perto do teto", gravacoes.secao(*self._tres(registro_com("10:00:01.000", "10:10:00.000"), preparar))[0])

    def test_the_occupation_is_told_against_the_ring_and_warned_from_eighty_percent(self):
        def preparar(raiz):
            with open(raiz / "sessions" / "reau_20261108_100005_000.aasession", "wb") as f:
                f.truncate(900 * 1048576)
        s = gravacoes.secao(*self._tres(registro_com("10:00:01.000", "10:10:00.000"), preparar))
        self.assertIn("900,0 MB de 1024 MB do anel (88 %): perto do teto", s[0])
        def pouco(raiz):
            (raiz / "sessions" / "reau_20261108_100005_000.aasession").write_bytes(sessao([]))
        self.assertNotIn("perto do teto", gravacoes.secao(*self._tres(registro_com("10:00:01.000", "10:10:00.000"), pouco))[0])

    def test_files_the_app_closed_that_are_not_in_the_folder_and_the_space_lines_are_told(self):
        def preparar(raiz):
            (raiz / "sessions" / "reau_20261108_100005_000.aasession").write_bytes(sessao([mensagem(0, 2, QUADRO)]))
        log = registro_com("10:00:01.000", "10:10:00.000",
                           linha("10:01:00.000", "aa", "[AA] session recording closed: 5 messages, 100 bytes, /d/files/sessions/reau_20261107_090000_000.aasession — covers the first 3 s"),
                           linha("10:02:00.000", "stream", "recordings space: 3 files removed (45351 KB), 1042833 KB left of 1048576 KB"),
                           linha("10:03:00.000", "stream", "recordings space: 2 files removed (1024 KB), 1000000 KB left of 1048576 KB"))
        s = "\n".join(gravacoes.secao(*self._tres(log, preparar)))
        self.assertIn("1 gravações que o app diz ter fechado não estão na pasta", s)
        self.assertIn("teto de pasta: 5 arquivos apagados (45 MB) em 2 vezes; a última deixou 977 MB de 1024 MB", s)

    def test_recordings_from_before_the_projections_read_are_counted_and_not_listed(self):
        def preparar(raiz):
            (raiz / "sessions" / "reau_20261105_100005_000.aasession").write_bytes(sessao([mensagem(0, 2, QUADRO)]))
        s = gravacoes.secao(*self._tres(registro_com("10:00:01.000", "10:10:00.000"), preparar))
        self.assertIn("1 arquivos de gravação de antes das projeções lidas (ou de outro dia): não listados", s)
        self.assertFalse([l for l in s if "sessão reau_20261105" in l])

    def test_a_projection_with_no_recording_adds_nothing_but_the_occupation(self):
        raiz, linhas, blocos = self.montar(registro_com("10:00:01.000", "10:10:00.000"), lambda r: None)
        self.assertEqual(1, len(gravacoes.secao(raiz, linhas, blocos)))

    def test_a_folder_without_sessions_or_video_gives_nothing_and_the_roots_are_found(self):
        vazia = Path(tempfile.mkdtemp())
        self.assertEqual([], gravacoes.secao(vazia, [], []))
        raiz = coleta()
        self.assertEqual([raiz], gravacoes.raizes_da_coleta([raiz / "diag"]))
        self.assertEqual([raiz], gravacoes.raizes_da_coleta([raiz, raiz / "diag"]))
        self.assertEqual([], gravacoes.raizes_da_coleta([vazia]))

    def test_the_command_line_prints_the_section_for_a_collection(self):
        def preparar(raiz):
            (raiz / "sessions" / "reau_20261108_100005_000.aasession").write_bytes(sessao([mensagem(0, 2, QUADRO), mensagem(540_000, 2, QUADRO)]))
        raiz, _, _ = self.montar(registro_com("10:00:01.000", "10:10:00.000"), preparar)
        feito = subprocess.run([sys.executable, "-I", str(AQUI / "ler_registro.py"), str(raiz), "--tudo"], capture_output=True, text=True, encoding="utf-8")
        self.assertIn("GRAVAÇÕES DA COLETA", feito.stdout)
        self.assertIn("540 s gravados", feito.stdout)


if __name__ == "__main__":
    unittest.main()
