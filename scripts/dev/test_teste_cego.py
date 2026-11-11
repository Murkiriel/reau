"""Minimal tests of the blind-test tools of scripts/dev/teste_cego (item 41, 11/11/2026): extrair.py (video out of a session recording), avaliar_cego.py and avaliar_noite.py (the
rule of the picture against labelled frames) and amostrar_cego.py (contact sheets, needs Pillow and the decoder `dec5`, so it is skipped where Pillow is not installed).
Synthetic recordings and indexes, built here; nothing reads a real one.

    cd scripts/dev && python3 -B -m unittest discover -p "test_*.py"
"""
import json
import os
import stat
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

CEGO = Path(__file__).resolve().parent / "teste_cego"
sys.path.insert(0, str(CEGO))
import reencodar_200k  # noqa: E402
import extrair  # noqa: E402  (does not run anything on import since 11/11/2026)
import indice_video  # noqa: E402

START = b"\x00\x00\x00\x01"
SPS_PPS = b"\x67\x42\x00\x29\x00\x00\x00\x01\x68\xce"
IDR = b"\x00\x00\x01\x65\x88\x84"       # NAL type 5
P_FRAME = b"\x00\x00\x01\x41\x9a\x24"    # NAL type 1


def msg(t_ms, canal, flags, carga):
    return struct.pack(">iBBi", t_ms, canal, flags, len(carga)) + carga


def video(tipo, corpo):
    return struct.pack(">H", tipo) + (b"\x00" * 8 if tipo == 0 else b"") + corpo


def aasession(inicio_ms, mensagens, assinatura=b"REAA"):
    return assinatura + b"\x01" + struct.pack(">q", inicio_ms) + b"".join(mensagens)


def rodar(script, *args, cwd=None, env=None):
    return subprocess.run([sys.executable, "-I", str(CEGO / script), *map(str, args)], capture_output=True, text=True, cwd=cwd, env=env)


class ExtrairTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)

    def sessao(self, nome, inicio, quadros, config=True):
        mensagens = [msg(0, 2, 3, video(1, SPS_PPS))] if config else []
        mensagens += [msg(t, 2, 3, video(0, corpo)) for t, corpo in quadros]
        arquivo = self.dir / nome
        arquivo.write_bytes(aasession(inicio, mensagens))
        return arquivo

    def test_the_frames_before_the_first_keyframe_are_dropped_and_the_config_goes_before_the_first_kept(self):
        a = self.sessao("a.aasession", 1_000_000, [(100, P_FRAME), (600, IDR), (1100, P_FRAME)])
        feito = rodar("extrair.py", self.dir / "saida", "tag", a)
        self.assertEqual(0, feito.returncode, feito.stderr)
        h264 = (self.dir / "saida" / "tag.h264").read_bytes()
        self.assertEqual(SPS_PPS + IDR + P_FRAME, h264)
        linhas = (self.dir / "saida" / "tag.csv").read_text().splitlines()
        self.assertEqual(indice_video.CABECALHO_NOVO, linhas[0])
        self.assertEqual(["1,600,%d,1" % (len(SPS_PPS) + len(IDR)), "2,1100,%d,0" % len(P_FRAME)], linhas[1:])
        self.assertEqual("1000000", (self.dir / "saida" / "tag.start").read_text())
        self.assertIn("2 quadros", feito.stdout)

    def test_the_csv_sum_is_the_size_of_the_file_so_the_index_reads_without_a_warning(self):
        a = self.sessao("a.aasession", 5, [(0, IDR), (500, P_FRAME)])
        rodar("extrair.py", self.dir / "saida", "tag", a)
        indice = indice_video.ler(self.dir / "saida" / "tag.csv", self.dir / "saida" / "tag.h264")
        self.assertEqual([], indice.avisos)
        self.assertEqual(indice_video.NOVO, indice.formato)

    def test_two_parts_are_joined_in_time_with_the_second_offset_by_its_start(self):
        antes = self.sessao("p1.aasession", 1_000_000, [(0, IDR), (400, P_FRAME)])
        depois = self.sessao("p2.aasession", 1_002_000, [(0, IDR)], config=False)
        feito = rodar("extrair.py", self.dir / "saida", "dois", antes, depois)
        self.assertEqual(0, feito.returncode, feito.stderr)
        pts = [int(l.split(",")[1]) for l in (self.dir / "saida" / "dois.csv").read_text().splitlines()[1:]]
        self.assertEqual([0, 400, 2000], pts)

    def test_a_recording_without_a_config_needs_the_one_given_by_the_environment(self):
        a = self.sessao("a.aasession", 5, [(0, IDR)], config=False)
        self.assertNotEqual(0, rodar("extrair.py", self.dir / "s1", "tag", a).returncode)
        feito = rodar("extrair.py", self.dir / "s2", "tag", a, env={**os.environ, "CFG_HEX": SPS_PPS.hex()})
        self.assertEqual(0, feito.returncode, feito.stderr)
        self.assertEqual(SPS_PPS + IDR, (self.dir / "s2" / "tag.h264").read_bytes())

    def test_a_recording_without_a_keyframe_is_refused(self):
        a = self.sessao("a.aasession", 5, [(0, P_FRAME)])
        self.assertNotEqual(0, rodar("extrair.py", self.dir / "s", "tag", a).returncode)

    def test_a_file_that_is_not_a_session_is_refused_and_the_old_signature_is_read(self):
        ruim = self.dir / "ruim.aasession"
        ruim.write_bytes(b"XXXX" + b"\x00" * 20)
        self.assertNotEqual(0, rodar("extrair.py", self.dir / "s", "tag", ruim).returncode)
        antigo = self.dir / "antigo.aasession"
        antigo.write_bytes(aasession(7, [msg(0, 2, 3, video(1, SPS_PPS)), msg(10, 2, 3, video(0, IDR))], assinatura=b"PGAA"))
        self.assertEqual(0, rodar("extrair.py", self.dir / "s2", "tag", antigo).returncode)

    def test_the_functions_join_the_pieces_of_a_message_and_find_the_keyframe(self):
        pedacos = [(0, 2, 1, b"ab"), (1, 2, 0, b"cd"), (2, 2, 2, b"ef"), (3, 5, 2, b"orfa"), (4, 3, 3, b"xy")]
        self.assertEqual([(2, 2, 1, b"abcdef"), (4, 3, 3, b"xy")], extrair.juntar(pedacos))
        self.assertTrue(extrair.tem_idr(IDR))
        self.assertFalse(extrair.tem_idr(P_FRAME))
        self.assertFalse(extrair.tem_idr(b"\x00\x00\x01"))
        self.assertEqual(0x0001, extrair.tipo(b"\x00\x01rest"))
        self.assertEqual(-1, extrair.tipo(b"\x00"))

    def test_a_cut_message_at_the_end_is_ignored_and_the_start_clock_is_read(self):
        a = self.dir / "cortada.aasession"
        dados = aasession(42, [msg(0, 2, 3, video(0, IDR))]) + struct.pack(">iBBi", 5, 2, 3, 1000) + b"xx"
        a.write_bytes(dados)
        inicio, msgs = extrair.ler(str(a))
        self.assertEqual(42, inicio)
        self.assertEqual(1, len(msgs))


def lit_bin(compartilhamentos):
    """[ (luma>=15, 20, 25, 30, …) contados em 64x40 ] por quadro, como o dec5 grava: 10 inteiros de 16 bits (20 bytes) por quadro."""
    return b"".join(struct.pack("<10H", *c) for c in compartilhamentos)


class AvaliarCegoTest(unittest.TestCase):
    def test_the_rule_is_scored_per_labelled_group_and_the_transition_frames_are_left_out(self):
        with tempfile.TemporaryDirectory() as tmp:
            w = Path(tmp)
            (w / "cego").mkdir()
            (w / "t1").mkdir()
            # frames 1..3 of the sample: a map (lit 2000/2560), a menu (lit 300) and a transition (ignored)
            (w / "t1" / "lit.bin").write_bytes(lit_bin([[2000] * 10, [300] * 10, [1500] * 10]))
            (w / "cego" / "amostra.json").write_text(json.dumps([["t1", 1, 0.0], ["t1", 2, 0.5], ["t1", 3, 1.0]]))
            rotulos = {"rotulos": {"N": [1], "M": [2], "T": [3]}, "grupos": {"waze": [1], "menu": [2]}, "faixas": {},
                       "regra_atual": [30, 0.75, 0.55], "regra_noite": [30, 0.75, 0.55]}
            (w / "rot.json").write_text(json.dumps(rotulos))
            feito = rodar("avaliar_cego.py", w, w / "rot.json")
            self.assertEqual(0, feito.returncode, feito.stderr)
            self.assertIn("REGRA ATUAL DO REAU", feito.stdout)
            self.assertIn("-> 2 quadros: certo 2 (100.0%), errado 0, sem voto 0", feito.stdout)
            self.assertIn("waze", feito.stdout)
            self.assertIn("menu", feito.stdout)

    def test_a_frame_the_rule_gets_wrong_is_counted_as_wrong(self):
        with tempfile.TemporaryDirectory() as tmp:
            w = Path(tmp)
            (w / "cego").mkdir()
            (w / "t1").mkdir()
            (w / "t1" / "lit.bin").write_bytes(lit_bin([[300] * 10]))  # dark, labelled as a map
            (w / "cego" / "amostra.json").write_text(json.dumps([["t1", 1, 0.0]]))
            (w / "rot.json").write_text(json.dumps({"rotulos": {"N": [1]}, "grupos": {"waze": [1]}, "faixas": {}, "regra_atual": [30, 0.75, 0.55], "regra_noite": [30, 0.75, 0.55]}))
            feito = rodar("avaliar_cego.py", w, w / "rot.json")
            self.assertIn("-> 1 quadros: certo 0 (0.0%), errado 1, sem voto 0", feito.stdout)


class AvaliarNoiteTest(unittest.TestCase):
    def test_the_readings_are_labelled_by_the_report_windows_and_scored_for_both_sessions(self):
        with tempfile.TemporaryDirectory() as tmp:
            w, s = Path(tmp) / "dec", Path(tmp) / "runs"
            for tag in ("cal", "tst"):
                (w / tag).mkdir(parents=True)
                base_ms = (3 * 3600 + 5003) * 1000  # the clock of the phone is 3 h ahead of the report's
                (w / f"{tag}.start").write_text(str(base_ms))
                (w / f"{tag}.csv").write_text("frame,pts_ms,bytes_in_file,key\n" + "".join(f"{i + 1},{i * 600},100,0\n" for i in range(8)))
                (w / tag / "lit.bin").write_bytes(lit_bin([[2000] * 10] * 8))
            (s / "seed-1").mkdir(parents=True)
            (s / "seed-1" / "relatorio.json").write_text(json.dumps({"t0": [5000.0, 5010.0], "steps": [{"kind": "open", "app": "waze", "wait": 8}, {"kind": "menu", "wait": 8}]}))
            feito = rodar("avaliar_noite.py", w, s, "cal", "tst")
            self.assertEqual(0, feito.returncode, feito.stderr)
            self.assertIn("leituras estáveis: calibração (cal)", feito.stdout)
            self.assertIn("REGRA ATUAL em cal", feito.stdout)
            self.assertIn("waze", feito.stdout)
            self.assertIn("MELHOR REGRA em tst (teste, nunca visto na busca)", feito.stdout)


class Reencodar200kTest(unittest.TestCase):
    """The tool that prepares the blind comparison at 200 kbps: it never writes inside the collection or reau_registros/, and its commands are the ones the ffmpeg would get."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        self.coleta = self.dir / "celular-20261101-1000"
        (self.coleta / "video").mkdir(parents=True)
        for nome, passo in (("reau_20261101_100000_000", 250), ("reau_20261101_110000_000", 500)):
            (self.coleta / "video" / f"{nome}.h264").write_bytes(b"\x00" * 40)
            (self.coleta / "video" / f"{nome}.csv").write_text("frame,pts_ms,bytes_in_file,key\n" + "".join(f"{i + 1},{i * passo},10,{1 if i == 0 else 0}\n" for i in range(4)))
        self.saida = self.dir / "saida"

    def ffmpeg_falso(self, falha=False):
        f = self.dir / "ffmpeg-falso"
        f.write_text('#!/usr/bin/env bash\necho "$*" >> "%s/chamadas.txt"\n%sfor ultimo; do :; done\necho filme > "$ultimo"\n' % (self.dir, "exit 1\n" if falha else ""))
        f.chmod(f.stat().st_mode | stat.S_IXUSR)
        return f

    def test_the_average_rate_comes_from_the_index_and_defaults_without_it(self):
        import reencodar_200k
        self.assertAlmostEqual(4.0, reencodar_200k.ritmo_medio(self.coleta / "video" / "reau_20261101_100000_000.h264"))  # 3 intervals in 750 ms
        self.assertAlmostEqual(2.0, reencodar_200k.ritmo_medio(self.coleta / "video" / "reau_20261101_110000_000.h264"))
        solto = self.dir / "sem_indice.h264"
        solto.write_bytes(b"x")
        self.assertEqual(reencodar_200k.FPS_PADRAO, reencodar_200k.ritmo_medio(solto))

    def test_without_ffmpeg_it_says_so_writes_nothing_and_exits_three(self):
        feito = rodar("reencodar_200k.py", self.coleta, self.saida, "--ffmpeg", str(self.dir / "nao-existe"))
        self.assertEqual(3, feito.returncode)
        self.assertIn("ffmpeg não está instalado", feito.stderr)
        self.assertFalse(self.saida.exists())

    def test_listing_the_commands_works_without_ffmpeg_and_runs_none(self):
        feito = rodar("reencodar_200k.py", self.coleta, self.saida, "--ffmpeg", str(self.dir / "nao-existe"), "--so-listar", "--max-s", "60")
        self.assertEqual(0, feito.returncode, feito.stderr)
        linhas = feito.stdout.strip().splitlines()
        self.assertEqual(4, len(linhas))  # two recordings, original and reencoded each
        reenc = [l for l in linhas if "libx264" in l]
        self.assertEqual(2, len(reenc))
        for l in reenc:
            for pedaco in ("-b:v 200k", "-maxrate 200k", "-bufsize 200k", "-t 60", "-an", "-pix_fmt yuv420p"):
                self.assertIn(pedaco, l)
        self.assertIn("-r 4.0000", linhas[0])
        self.assertTrue([l for l in linhas if "-c:v copy" in l])
        self.assertFalse(self.saida.exists())

    def test_blind_mode_writes_pairs_a_b_and_a_key_that_says_which_is_which(self):
        feito = rodar("reencodar_200k.py", self.coleta, self.saida, "--ffmpeg", self.ffmpeg_falso())
        self.assertEqual(0, feito.returncode, feito.stderr)
        self.assertEqual(["001_A.mp4", "001_B.mp4", "002_A.mp4", "002_B.mp4", "gabarito.json"], sorted(p.name for p in self.saida.iterdir()))
        gabarito = json.loads((self.saida / "gabarito.json").read_text())
        self.assertEqual(["001_A.mp4", "001_B.mp4", "002_A.mp4", "002_B.mp4"], sorted(gabarito))
        for n in ("001", "002"):
            self.assertEqual(["original", "reencodado"], sorted([gabarito[f"{n}_A.mp4"], gabarito[f"{n}_B.mp4"]]))
        chamadas = (self.dir / "chamadas.txt").read_text().splitlines()
        self.assertEqual(4, len(chamadas))
        for nome, o_que in gabarito.items():  # the encoder got the 200k command exactly for the file the key calls reencoded
            linha = next(c for c in chamadas if c.endswith(nome))
            self.assertEqual(o_que == "reencodado", "libx264" in linha, (nome, o_que))
        self.assertIn("abra só depois de escolher", feito.stdout)

    def test_the_draw_is_fixed_by_the_seed_and_changes_with_it(self):
        import reencodar_200k
        h264s = reencodar_200k.gravacoes(self.coleta)
        a = reencodar_200k.planejar(h264s, self.saida, 200, 15, False, "ffmpeg")[1]
        self.assertEqual(a, reencodar_200k.planejar(h264s, self.saida, 200, 15, False, "ffmpeg")[1])
        varios = {tuple(reencodar_200k.planejar(h264s * 6, self.saida, 200, semente, False, "ffmpeg")[1].values()) for semente in range(1, 8)}
        self.assertGreater(len(varios), 1)

    def test_open_mode_names_the_files_by_what_they_are_and_has_no_key(self):
        feito = rodar("reencodar_200k.py", self.coleta, self.saida, "--ffmpeg", self.ffmpeg_falso(), "--aberto", "--kbps", "150")
        self.assertEqual(0, feito.returncode, feito.stderr)
        nomes = sorted(p.name for p in self.saida.iterdir())
        self.assertEqual(["reau_20261101_100000_000.150k.mp4", "reau_20261101_100000_000.original.mp4", "reau_20261101_110000_000.150k.mp4", "reau_20261101_110000_000.original.mp4"], nomes)
        self.assertIn("-b:v 150k", (self.dir / "chamadas.txt").read_text())

    def test_the_output_may_not_be_inside_reau_registros_nor_inside_the_collection(self):
        for ruim in (self.dir / "reau_registros" / "saida", self.coleta / "saida", self.coleta, self.coleta / "video"):
            feito = rodar("reencodar_200k.py", self.coleta, ruim, "--ffmpeg", self.ffmpeg_falso())
            self.assertEqual(2, feito.returncode, ruim)
            self.assertFalse(ruim.exists() and any(ruim.glob("*.mp4")))
        self.assertIn("é só leitura", rodar("reencodar_200k.py", self.coleta, self.dir / "reau_registros" / "x", "--so-listar").stderr)

    def test_a_failing_ffmpeg_is_reported_and_the_exit_code_is_one(self):
        feito = rodar("reencodar_200k.py", self.coleta, self.saida, "--ffmpeg", self.ffmpeg_falso(falha=True))
        self.assertEqual(1, feito.returncode)
        self.assertIn("FALHOU", feito.stderr)

    def test_bad_arguments_and_a_folder_without_video_say_how_to_use_it(self):
        self.assertEqual(2, rodar("reencodar_200k.py").returncode)
        self.assertEqual(2, rodar("reencodar_200k.py", self.coleta, self.saida, "--kbps").returncode)
        self.assertEqual(2, rodar("reencodar_200k.py", self.coleta, self.saida, "--kbps", "dois").returncode)
        self.assertEqual(2, rodar("reencodar_200k.py", self.coleta, self.saida, "--nada").returncode)
        vazia = self.dir / "vazia"
        vazia.mkdir()
        self.assertEqual(2, rodar("reencodar_200k.py", vazia, self.saida, "--so-listar").returncode)
        self.assertEqual(2, rodar("reencodar_200k.py", self.dir / "nao-existe", self.saida).returncode)
        self.assertEqual(0, rodar("reencodar_200k.py", "--help").returncode)

    def test_the_video_folder_itself_can_be_given(self):
        feito = rodar("reencodar_200k.py", self.coleta / "video", self.saida, "--so-listar")
        self.assertEqual(4, len(feito.stdout.strip().splitlines()))


def pillow_instalado():
    try:
        import PIL  # noqa: F401
        return True
    except ImportError:
        return False


@unittest.skipUnless(pillow_instalado(), "Pillow não está instalado (scripts/requirements.txt): amostrar_cego.py não roda")
class AmostrarCegoTest(unittest.TestCase):
    def test_it_draws_the_frames_with_a_fixed_seed_and_makes_the_contact_sheets_with_a_fake_decoder(self):
        with tempfile.TemporaryDirectory() as tmp:
            w = Path(tmp)
            (w / "t1.csv").write_text("frame,pts_ms,bytes_in_file,key\n" + "".join(f"{i + 1},{i * 600},100,0\n" for i in range(20)))
            (w / "t1.start").write_text("0")
            decoder = w / "dec5"
            decoder.write_text('#!/usr/bin/env bash\nout="$3"; shift 3\nfor i in "$@"; do printf "P6\\n4 4\\n255\\n" > "$out/f$(printf %05d $i).ppm"; head -c 48 /dev/zero >> "$out/f$(printf %05d $i).ppm"; done\n')
            decoder.chmod(decoder.stat().st_mode | stat.S_IXUSR)
            feito = rodar("amostrar_cego.py", w, "t1:5", cwd=w)
            self.assertEqual(0, feito.returncode, feito.stderr)
            amostra = json.loads((w / "cego" / "amostra.json").read_text())
            self.assertEqual(5, len(amostra))
            self.assertTrue((w / "cego" / "folha_1.png").exists())
            outra = rodar("amostrar_cego.py", w, "t1:5", cwd=w)
            self.assertEqual(amostra, json.loads((w / "cego" / "amostra.json").read_text()))  # the same seed, the same draw
            self.assertEqual(0, outra.returncode)


if __name__ == "__main__":
    unittest.main()
