"""Tests of the index (.csv) of the recorded H.264 video, in its two formats (item 18 of 03/11/2026).

H264Recorder.kt writes `frame,pts_ms,bytes_in_file,key` (the size of each unit in the file, start code included); the recordings of 20/10 to
03/11/2026 have `frame,pts_ms,bytes,key`, which leaves the 4 bytes of the start code out. The tools (teste_cego/indice_video.py, extrair.py,
dec5.c, dec6.c) read both. Synthetic files only: nothing here reads a real recording."""
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
CEGO = HERE / "teste_cego"
# dec6.c is not in this repository: REAU_DEC6_C points at it, and without it the dec6 tests are skipped.
DEC6_C = Path(os.environ["REAU_DEC6_C"]) if os.environ.get("REAU_DEC6_C") else None
DEC5_C = CEGO / "dec5.c"
sys.path.insert(0, str(CEGO))
import indice_video  # noqa: E402

START = b"\x00\x00\x00\x01"


def unidades():
    """Three units as the recorder gets them (no start code): an IDR with SPS+PPS, and two P frames of different sizes."""
    return [b"\x67\x42\x00\x29\x00\x00\x00\x01\x68\xce\x00\x00\x00\x01\x65\x88\x84", b"\x41\x9a\x24", b"\x41\x9b" + b"\x7f" * 20]


def grava(pasta, nome, cabecalho, somar_no_csv, quadros=None):
    """A .h264 (every unit after a start code) and its .csv; the column counts the start code or not, as [somar_no_csv] says."""
    quadros = unidades() if quadros is None else quadros
    h264 = Path(pasta) / f"{nome}.h264"
    h264.write_bytes(b"".join(START + q for q in quadros))
    csv = Path(pasta) / f"{nome}.csv"
    linhas = [cabecalho] + [f"{i + 1},{i * 250},{len(q) + (4 if somar_no_csv else 0)},{1 if i == 0 else 0}" for i, q in enumerate(quadros)]
    csv.write_text("\n".join(linhas) + "\n")
    return h264, csv


class IndiceVideoTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)

    def test_the_new_header_is_read_as_it_is_and_its_sum_is_the_size_of_the_file(self):
        h264, csv = grava(self.dir, "novo", indice_video.CABECALHO_NOVO, somar_no_csv=True)
        idx = indice_video.ler(csv, h264)
        self.assertEqual(indice_video.NOVO, idx.formato)
        self.assertEqual([len(q) + 4 for q in unidades()], [q[2] for q in idx.quadros])
        self.assertEqual(h264.stat().st_size, idx.soma)
        self.assertEqual([], idx.avisos)

    def test_the_old_header_gets_4_bytes_per_unit_and_then_its_sum_is_the_size_of_the_file(self):
        h264, csv = grava(self.dir, "antigo", indice_video.CABECALHO_ANTIGO, somar_no_csv=False)
        idx = indice_video.ler(csv, h264)
        self.assertEqual(indice_video.ANTIGO, idx.formato)
        self.assertEqual([len(q) + 4 for q in unidades()], [q[2] for q in idx.quadros])
        self.assertEqual(h264.stat().st_size, idx.soma)
        self.assertEqual([], idx.avisos)

    def test_both_formats_of_the_same_video_give_the_same_units_and_offsets(self):
        h_n, c_n = grava(self.dir, "n", indice_video.CABECALHO_NOVO, True)
        h_a, c_a = grava(self.dir, "a", indice_video.CABECALHO_ANTIGO, False)
        novo, antigo = indice_video.ler(c_n, h_n), indice_video.ler(c_a, h_a)
        self.assertEqual(novo.quadros, antigo.quadros)
        deslocamentos = novo.desloca()
        self.assertEqual([0, len(unidades()[0]) + 4, len(unidades()[0]) + len(unidades()[1]) + 8], deslocamentos)
        dados = h_n.read_bytes()
        for desloc, (_, _, n, _), q in zip(deslocamentos, novo.quadros, unidades()):
            self.assertEqual(START + q, dados[desloc:desloc + n])

    def test_a_sum_that_does_not_match_the_file_is_a_warning_with_the_difference(self):
        h264, csv = grava(self.dir, "curto", indice_video.CABECALHO_ANTIGO, False)
        h264.write_bytes(h264.read_bytes()[:-5])  # a recording cut short
        idx = indice_video.ler(csv, h264)
        self.assertEqual(1, len(idx.avisos), idx.avisos)
        self.assertIn("-5", idx.avisos[0])
        self.assertIn("antigo", idx.avisos[0])

    def test_a_new_index_that_does_not_match_the_file_is_a_warning_too(self):
        h264, csv = grava(self.dir, "novo_errado", indice_video.CABECALHO_NOVO, False)  # new header, but sizes without the start codes
        idx = indice_video.ler(csv, h264)
        self.assertEqual(indice_video.NOVO, idx.formato)
        self.assertEqual(1, len(idx.avisos))
        self.assertIn("(+12)", idx.avisos[0])  # the file is 12 B (3 start codes) bigger than the sum

    def test_an_old_header_whose_sum_is_already_the_file_is_read_as_already_added(self):
        h264, csv = grava(self.dir, "c6", indice_video.CABECALHO_ANTIGO, True)  # a .c6.csv of the studies: old header, start codes already counted
        idx = indice_video.ler(csv, h264)
        self.assertEqual(indice_video.ANTIGO_JA_SOMADO, idx.formato)
        self.assertEqual(h264.stat().st_size, idx.soma)
        self.assertEqual([len(q) + 4 for q in unidades()], [q[2] for q in idx.quadros])
        self.assertEqual(1, len(idx.avisos))  # told, not hidden

    def test_without_the_video_file_there_is_nothing_to_check(self):
        _, csv = grava(self.dir, "so_csv", indice_video.CABECALHO_ANTIGO, False)
        idx = indice_video.ler(csv)
        self.assertEqual(indice_video.ANTIGO, idx.formato)
        self.assertIsNone(idx.tamanho_arquivo)
        self.assertEqual([], idx.avisos)

    def test_an_unknown_header_or_an_empty_file_is_refused(self):
        csv = self.dir / "x.csv"
        csv.write_text("n,t,b,k\n1,0,5,1\n")
        with self.assertRaises(ValueError):
            indice_video.ler(csv)
        csv.write_text("")
        with self.assertRaises(ValueError):
            indice_video.ler(csv)

    def test_normalizing_writes_the_new_format_from_either(self):
        for nome, cab, somado in (("a", indice_video.CABECALHO_ANTIGO, False), ("n", indice_video.CABECALHO_NOVO, True)):
            h264, csv = grava(self.dir, nome, cab, somado)
            saida = self.dir / f"{nome}.norm.csv"
            indice_video.normalizar(csv, h264, saida)
            linhas = saida.read_text().splitlines()
            self.assertEqual(indice_video.CABECALHO_NOVO, linhas[0])
            self.assertEqual([len(q) + 4 for q in unidades()], [int(l.split(",")[2]) for l in linhas[1:]])
            self.assertEqual(h264.stat().st_size, sum(int(l.split(",")[2]) for l in linhas[1:]))
            self.assertEqual([], indice_video.ler(saida, h264).avisos)

    def test_the_program_reports_the_format_and_exits_non_zero_when_the_sum_is_off(self):
        h264, csv = grava(self.dir, "p", indice_video.CABECALHO_ANTIGO, False)
        ok = subprocess.run([sys.executable, "-I", str(CEGO / "indice_video.py"), str(csv), str(h264)], capture_output=True, text=True)
        self.assertEqual(0, ok.returncode, ok.stderr)
        self.assertIn("formato antigo: 3 quadros", ok.stdout)
        h264.write_bytes(h264.read_bytes() + b"x")
        ruim = subprocess.run([sys.executable, "-I", str(CEGO / "indice_video.py"), str(csv), str(h264)], capture_output=True, text=True)
        self.assertEqual(1, ruim.returncode)
        self.assertIn("AVISO", ruim.stderr)


def sessao(quadros, cfg):
    """A .aasession as SessionRecorder.kt writes it: 'REAA', version 1, the clock of the first message, then (ms, channel, flags, size, payload)."""
    out = b"REAA" + b"\x01" + struct.pack(">q", 1_700_000_000_000)

    def msg(ms, tipo, carga):
        corpo = struct.pack(">H", tipo) + carga
        return struct.pack(">iBBi", ms, 2, 3, len(corpo)) + corpo

    out += msg(0, 1, cfg)
    for i, q in enumerate(quadros):
        out += msg(100 + 250 * i, 0, b"\x00" * 8 + q)  # type (2) + 8 bytes of time, then the Annex-B unit
    return out


class ExtrairTest(unittest.TestCase):
    def test_extrair_writes_the_new_format_and_the_sum_is_the_size_of_the_h264(self):
        with tempfile.TemporaryDirectory() as tmp:
            cfg = b"\x00\x00\x00\x01\x67\x42\x00\x29\x00\x00\x00\x01\x68\xce\x06\xf2"
            quadros = [b"\x00\x00\x00\x01\x65\x88\x84\x00\x10", b"\x00\x00\x00\x01\x41\x9a\x24", b"\x00\x00\x00\x01\x41\x9b\x7f\x7f\x7f\x7f"]
            (Path(tmp) / "s.aasession").write_bytes(sessao(quadros, cfg))
            feito = subprocess.run([sys.executable, "-I", str(CEGO / "extrair.py"), tmp, "t", str(Path(tmp) / "s.aasession")], capture_output=True, text=True)
            self.assertEqual(0, feito.returncode, feito.stderr)
            linhas = (Path(tmp) / "t.csv").read_text().splitlines()
            self.assertEqual(indice_video.CABECALHO_NOVO, linhas[0])
            tamanhos = [int(l.split(",")[2]) for l in linhas[1:]]
            self.assertEqual([len(cfg) + len(quadros[0]), len(quadros[1]), len(quadros[2])], tamanhos)
            self.assertEqual((Path(tmp) / "t.h264").stat().st_size, sum(tamanhos))
            self.assertEqual([], indice_video.ler(Path(tmp) / "t.csv", Path(tmp) / "t.h264").avisos)


def libavcodec():
    return Path("/usr/lib/x86_64-linux-gnu/libavcodec.so.60").exists() and Path("/usr/lib/x86_64-linux-gnu/libavutil.so.58").exists() and shutil.which("gcc") is not None


@unittest.skipUnless(libavcodec(), "needs gcc and the system's libavcodec 60 (the decoders link to it with no headers)")
class DecoderCsvTest(unittest.TestCase):
    """What dec5/dec6 say about the index. The video is not H.264 (the decoders get nothing out of it); only their reading of the csv is tried."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.bin = {}
        for nome, fonte in (("dec5", DEC5_C), ("dec6", DEC6_C)):
            if fonte is None:
                continue
            alvo = Path(cls.tmp.name) / nome
            feito = subprocess.run(["gcc", "-O1", "-o", str(alvo), str(fonte), "/usr/lib/x86_64-linux-gnu/libavcodec.so.60",
                                    "/usr/lib/x86_64-linux-gnu/libavutil.so.58", "-lm"], capture_output=True, text=True)
            if feito.returncode:
                raise unittest.SkipTest(f"{nome}.c does not build here: {feito.stderr[-300:]}")
            cls.bin[nome] = alvo

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def roda(self, nome, csv, h264):
        pasta = Path(self.tmp.name) / "out"
        pasta.mkdir(exist_ok=True)
        if nome == "dec5":
            cmd = [str(self.bin[nome]), str(h264), str(csv), str(pasta)]
        else:
            cmd = [str(self.bin[nome]), "amostra", str(h264), str(csv), str(pasta / "o.bin"), "250"]
        return subprocess.run(cmd, capture_output=True, text=True)

    def test_both_formats_matching_the_file_say_nothing(self):
        for nome in self.bin:
            with tempfile.TemporaryDirectory() as t:
                h, c = grava(t, "n", indice_video.CABECALHO_NOVO, True)
                self.assertNotIn("AVISO", self.roda(nome, c, h).stderr, nome)
                h, c = grava(t, "a", indice_video.CABECALHO_ANTIGO, False)
                self.assertNotIn("AVISO", self.roda(nome, c, h).stderr, nome)

    def test_an_index_that_does_not_add_up_to_the_file_is_a_warning(self):
        for nome in self.bin:
            with tempfile.TemporaryDirectory() as t:
                h, c = grava(t, "n", indice_video.CABECALHO_NOVO, False)  # new header with sizes that miss the start codes
                self.assertIn("AVISO", self.roda(nome, c, h).stderr, nome)
                h, c = grava(t, "a", indice_video.CABECALHO_ANTIGO, False)
                h.write_bytes(h.read_bytes()[:-3])
                self.assertIn("AVISO", self.roda(nome, c, h).stderr, nome)

    def test_dec6_takes_an_old_header_that_is_already_added_as_it_is_and_says_so(self):
        if "dec6" not in self.bin:
            self.skipTest("REAU_DEC6_C is not set: dec6.c is not in this repository")
        with tempfile.TemporaryDirectory() as t:
            h, c = grava(t, "c6", indice_video.CABECALHO_ANTIGO, True)
            self.assertIn("ja e o tamanho", self.roda("dec6", c, h).stderr)


if __name__ == "__main__":
    unittest.main()
