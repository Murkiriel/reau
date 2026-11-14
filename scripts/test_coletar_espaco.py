"""Tests of the two warnings of scripts/coletar_do_celular.sh (item 11c, 12/11): the small logcat buffers (`logcat -g`, read first) and the recordings'
space (the `left of` line of the ride log and the size of what was copied). A fake `adb` stands for the phone; nothing here touches a real one.
Run:  cd scripts && python3 -B -m unittest discover -p "test_coletar_espaco.py"
"""
import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "coletar_do_celular.sh"
BUFFERS_PEQUENOS = ("main: ring buffer is 256 KiB (251 KiB consumed, 1 MiB readable), max entry is 5120 B, max payload is 4068 B\n"
                    "system: ring buffer is 256 KiB (244 KiB consumed, 964 KiB readable), max entry is 5120 B, max payload is 4068 B\n"
                    "crash: ring buffer is 256 KiB (0 B consumed, 0 B readable), max entry is 5120 B, max payload is 4068 B\n")
BUFFERS_GRANDES = BUFFERS_PEQUENOS.replace("256 KiB (251", "4 MiB (251").replace("256 KiB (244", "2048 KiB (244")


def fake_adb(pasta: Path, buffers: str, arquivos: Path | None) -> Path:
    """A fake adb: `logcat -g` answers [buffers]; the `tar c -C files PASTA` of run-as is made from [arquivos]/PASTA (nothing if it is missing)."""
    log = pasta / "adb.log"
    (pasta / "buffers.txt").write_text(buffers, encoding="utf-8")
    adb = pasta / "adb"
    adb.write_text(
        "#!/usr/bin/env bash\n"
        f'echo "$*" >> "{log}"\n'
        'if [ "$1" = devices ]; then printf "List of devices attached\\nABC123\\tdevice\\n"; exit 0; fi\n'
        f'case "$*" in *"logcat -g"*) cat "{pasta / "buffers.txt"}"; exit 0;; esac\n'
        'case "$*" in *"exec-out run-as com.reau.app tar c -C files "*)\n'
        '  for ultimo; do :; done\n'
        f'  if [ -n "{arquivos or ""}" ] && [ -d "{arquivos or "/nao"}/$ultimo" ]; then tar c -C "{arquivos or "/nao"}" "$ultimo"; else exit 1; fi; exit 0;; esac\n'
        "echo fake\n",
        encoding="utf-8",
    )
    adb.chmod(adb.stat().st_mode | stat.S_IXUSR)
    return log


def rodar(buffers=BUFFERS_GRANDES, ride=None, tamanho_kb=0, **ambiente):
    """Runs the script. [ride]: text of a ride log for diag/; [tamanho_kb]: KB of a video/ file to be copied. Returns (done, out dir, adb log lines)."""
    tmp = tempfile.TemporaryDirectory()
    base = Path(tmp.name)
    arquivos = base / "celular"
    (arquivos / "diag").mkdir(parents=True)
    if ride is not None:
        (arquivos / "diag" / "ride-20261101-100000.log").write_text(ride, encoding="utf-8")
    if tamanho_kb:
        (arquivos / "video").mkdir()
        with open(arquivos / "video" / "reau_x.h264", "wb") as f:
            f.truncate(tamanho_kb * 1024)
    log = fake_adb(base, buffers, arquivos)
    saida = base / "saida"
    env = {**os.environ, "ADB": str(base / "adb"), "REAU_SAIDA": str(saida), **ambiente}
    done = subprocess.run(["bash", str(SCRIPT), "ABC123"], env=env, capture_output=True, text=True)
    comandos = log.read_text(encoding="utf-8").splitlines()
    return done, saida, comandos, tmp


def linha_de_espaco(ocupado_kb, teto_kb=1048576):
    return f"10:00:00.000  + 5ms  [diag] recordings space: 3 files removed (45351 KB), {ocupado_kb} KB left of {teto_kb} KB\n"


class BuffersDoLogcatTest(unittest.TestCase):
    def test_small_main_and_system_buffers_are_warned_at_the_start_and_in_the_info(self):
        done, saida, comandos, tmp = rodar(BUFFERS_PEQUENOS)
        with tmp:
            self.assertEqual(0, done.returncode, done.stderr)
            self.assertIn("o buffer main do logcat é pequeno (256 KiB", done.stderr)
            self.assertIn("o buffer system do logcat é pequeno (256 KiB", done.stderr)
            self.assertNotIn("buffer crash", done.stderr)  # only main and system carry the history that matters
            info = (saida / "INFO.txt").read_text(encoding="utf-8")
            self.assertIn("--- avisos da coleta", info)
            self.assertIn("o buffer main", info)

    def test_big_buffers_are_not_warned(self):
        done, saida, comandos, tmp = rodar(BUFFERS_GRANDES)
        with tmp:
            self.assertNotIn("buffer", done.stderr)
            self.assertNotIn("avisos da coleta", (saida / "INFO.txt").read_text(encoding="utf-8"))

    def test_the_size_is_read_first_once_and_the_file_in_sistema_keeps_it(self):
        done, saida, comandos, tmp = rodar(BUFFERS_PEQUENOS)
        with tmp:
            self.assertEqual(["-s ABC123 logcat -g"], [c for c in comandos if "logcat -g" in c])
            primeiro_logcat = next(k for k, c in enumerate(comandos) if "logcat" in c)
            self.assertIn("logcat -g", comandos[primeiro_logcat])
            self.assertFalse([c for c in comandos[:primeiro_logcat] if "dumpsys" in c or "getprop" in c], "the buffers are read before the other reads")
            self.assertEqual(BUFFERS_PEQUENOS.strip(), (saida / "sistema" / "logcat-tamanho-dos-buffers.txt").read_text(encoding="utf-8").strip())

    def test_a_size_that_cannot_be_read_is_said_and_the_collection_goes_on(self):
        done, saida, comandos, tmp = rodar("logcat: error\n")
        with tmp:
            self.assertEqual(0, done.returncode, done.stderr)
            self.assertIn("não pôde ser lido", done.stderr)
            self.assertTrue((saida / "logcat-sistema.txt").exists())

    def test_the_minimum_can_be_set(self):
        done, saida, comandos, tmp = rodar(BUFFERS_PEQUENOS, LOGCAT_MIN_KIB="128")
        with tmp:
            self.assertNotIn("é pequeno", done.stderr)

    def test_a_buffer_exactly_at_the_minimum_is_not_small_and_one_below_is(self):
        done, _, _, tmp = rodar(BUFFERS_PEQUENOS, LOGCAT_MIN_KIB="256")
        with tmp:
            self.assertNotIn("é pequeno", done.stderr)
        done, _, _, tmp = rodar(BUFFERS_PEQUENOS, LOGCAT_MIN_KIB="257")
        with tmp:
            self.assertIn("o buffer main do logcat é pequeno (256 KiB, abaixo de 257)", done.stderr)

    def test_a_buffer_given_in_mib_or_gib_is_converted_to_kib(self):
        done, _, _, tmp = rodar("main: ring buffer is 1 MiB (1 KiB consumed)\nsystem: ring buffer is 1 GiB (1 KiB consumed)\n")
        with tmp:
            self.assertNotIn("buffer main", done.stderr)  # 1 MiB = 1024 KiB is not below 1024
            self.assertNotIn("buffer system", done.stderr)

    def test_a_line_of_main_without_the_size_is_told_with_a_question_mark(self):
        done, saida, comandos, tmp = rodar("main: weird format\nsystem: ring buffer is 4 MiB (1 KiB consumed)\n")
        with tmp:
            self.assertIn("o buffer main do logcat é pequeno (? KiB", done.stderr)
            self.assertNotIn("buffer system", done.stderr)


class EspacoDasGravacoesTest(unittest.TestCase):
    def test_the_left_of_line_at_or_above_800_mb_warns_with_the_free_space(self):
        done, saida, comandos, tmp = rodar(ride=linha_de_espaco(1027431))
        with tmp:
            self.assertIn("gravações em 1003 MB de 1024 MB (livre 20 MB", done.stderr)
            info = (saida / "INFO.txt").read_text(encoding="utf-8")
            self.assertIn("1027431 KB left of 1048576 KB", info)
            self.assertIn("avisos da coleta", info)

    def test_below_800_mb_there_is_no_warning(self):
        done, saida, comandos, tmp = rodar(ride=linha_de_espaco(700 * 1024))
        with tmp:
            self.assertNotIn("gravações em", done.stderr)

    def test_the_limit_is_exactly_800_mb_and_can_be_set(self):
        done, _, _, tmp = rodar(ride=linha_de_espaco(800 * 1024))
        with tmp:
            self.assertIn("gravações em 800 MB", done.stderr)
        done, _, _, tmp = rodar(ride=linha_de_espaco(800 * 1024 - 1))
        with tmp:
            self.assertNotIn("gravações em", done.stderr)
        done, _, _, tmp = rodar(ride=linha_de_espaco(500 * 1024), REAU_AVISO_MB="400")
        with tmp:
            self.assertIn("gravações em 500 MB", done.stderr)

    def test_both_the_line_and_the_copy_over_the_limit_make_one_warning_only(self):
        done, _, _, tmp = rodar(ride=linha_de_espaco(900 * 1024), tamanho_kb=900 * 1024)
        with tmp:
            self.assertEqual(1, done.stderr.count("gravações em"))

    def test_below_eighty_percent_of_the_default_there_is_no_warning_even_with_a_big_copy(self):
        done, _, _, tmp = rodar(ride=linha_de_espaco(799 * 1024), tamanho_kb=799 * 1024)
        with tmp:
            self.assertNotIn("gravações em", done.stderr)

    def test_only_the_last_line_counts(self):
        antigo = linha_de_espaco(1000 * 1024)
        done, _, _, tmp = rodar(ride=antigo + linha_de_espaco(300 * 1024))
        with tmp:
            self.assertNotIn("gravações em", done.stderr)

    def test_a_cap_other_than_1024_mb_is_taken_from_the_line(self):
        done, _, _, tmp = rodar(ride=linha_de_espaco(900 * 1024, 2 * 1048576))
        with tmp:
            self.assertIn("gravações em 900 MB de 2048 MB (livre 1148 MB", done.stderr)

    def test_without_a_line_the_size_of_the_copy_decides(self):
        done, saida, comandos, tmp = rodar(ride="10:00:00.000  [diag] nada\n", tamanho_kb=900 * 1024)
        with tmp:
            self.assertIn("gravações em 900 MB de 1024 MB", done.stderr)
            self.assertIn("nenhuma", (saida / "INFO.txt").read_text(encoding="utf-8"))
        done, saida, comandos, tmp = rodar(ride="10:00:00.000  [diag] nada\n", tamanho_kb=10)
        with tmp:
            self.assertNotIn("gravações em", done.stderr)

    def test_a_phone_with_nothing_to_copy_is_not_warned_and_the_script_ends_well(self):
        done, saida, comandos, tmp = rodar(ride=None)
        with tmp:
            self.assertEqual(0, done.returncode, done.stderr)
            self.assertNotIn("gravações em", done.stderr)

    def test_the_new_warnings_send_the_phone_nothing_but_reads(self):
        done, saida, comandos, tmp = rodar(BUFFERS_PEQUENOS, ride=linha_de_espaco(1027431))
        with tmp:
            for c in comandos:
                self.assertNotIn(" -G ", f" {c} ")
                self.assertNotIn("logcat -c", c)
                self.assertTrue(c.removeprefix("-s ABC123 ").startswith(("devices", "shell getprop", "shell dumpsys", "shell cmd appops get", "shell am get-standby-bucket",
                                                                       "shell settings get", "logcat", "exec-out run-as")), c)


if __name__ == "__main__":
    unittest.main()
