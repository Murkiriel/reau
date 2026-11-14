"""Direct tests of logcat_sistema.py (item 41, 11/11/2026): the files it picks, the threadtime lines it reads, the Android Auto crash, the app stalls and the Wi-Fi lines around a drop.
Synthetic logs only; the names of the networks are invented."""
import sys
import tempfile
import unittest
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import logcat_sistema as ls  # noqa: E402
import mascarar  # noqa: E402

# MACs built at run time: no literal hardware address sits in a versioned file (PrivacyGuardTest).
MAC_FF = ":".join(["aa", "bb", "cc", "dd", "ee", "ff"])
MAC_01 = ":".join(["aa", "bb", "cc", "dd", "ee", "01"])
MAC_02 = ":".join(["aa", "bb", "cc", "dd", "ee", "02"])
MAC_UPPER = ":".join(["0A", "1B", "2C", "3D", "4E", "5F"])


def entrada(hora, tag, texto, pid=100, nivel="I", mes=11, dia=8):
    return f"{mes:02d}-{dia:02d} {hora}  {pid}  {pid} {nivel} {tag}: {texto}\n"


class ArquivosTest(unittest.TestCase):
    def test_the_masked_copy_is_dropped_when_the_whole_one_is_there(self):
        d = Path(tempfile.mkdtemp())
        (d / "logcat-sistema.txt").write_text("", encoding="utf-8")
        (d / "logcat-sistema.mascarado.txt").write_text("", encoding="utf-8")
        self.assertEqual(["logcat-sistema.txt"], [a.name for a in ls.arquivos([d])])

    def test_the_masked_copy_stays_when_it_is_alone(self):
        d = Path(tempfile.mkdtemp())
        (d / "logcat-sistema.mascarado.txt").write_text("", encoding="utf-8")
        self.assertEqual(["logcat-sistema.mascarado.txt"], [a.name for a in ls.arquivos([d])])

    def test_the_diag_folder_looks_in_the_collection_above_it_too(self):
        raiz = Path(tempfile.mkdtemp())
        (raiz / "diag").mkdir()
        (raiz / "logcat-sistema.txt").write_text("", encoding="utf-8")
        self.assertEqual(["logcat-sistema.txt"], [a.name for a in ls.arquivos([raiz / "diag"])])

    def test_the_apps_own_logcat_is_never_the_systems(self):
        raiz = Path(tempfile.mkdtemp())
        (raiz / "diag").mkdir()
        (raiz / "diag" / "logcat-20261108-100000.txt").write_text("", encoding="utf-8")
        (raiz / "logcat-sistema.txt").write_text("", encoding="utf-8")
        (raiz / "logcat-crash.txt").write_text("", encoding="utf-8")
        self.assertEqual(["logcat-20261108-100000.txt"], [a.name for a in ls.arquivos_do_app([raiz])])
        self.assertEqual(["logcat-sistema.txt"], [a.name for a in ls.arquivos([raiz])])


class LerTest(unittest.TestCase):
    def test_threadtime_lines_are_read_and_the_others_are_skipped(self):
        d = Path(tempfile.mkdtemp())
        (d / "logcat-sistema.txt").write_text("--------- beginning of main\n" + entrada("10:00:00.123", "wpa_supplicant", "wlan0: CTRL-EVENT-DISCONNECTED reason=3", pid=7, nivel="D") +
                                              "lixo sem formato\n", encoding="utf-8")
        [e] = ls.ler([d])
        self.assertEqual((11, 8, "10:00:00.123", 7, "D", "wpa_supplicant"), (e.mes, e.dia, e.hora, e.pid, e.nivel, e.tag))

    def test_the_flag_chooses_the_systems_or_the_apps_files(self):
        raiz = Path(tempfile.mkdtemp())
        (raiz / "logcat-sistema.txt").write_text(entrada("10:00:00.000", "A", "do sistema"), encoding="utf-8")
        (raiz / "logcat-20261108-100000.txt").write_text(entrada("10:00:01.000", "B", "do app"), encoding="utf-8")
        self.assertEqual(["A"], [e.tag for e in ls.ler([raiz])])
        self.assertEqual(["B"], [e.tag for e in ls.ler([raiz], so_do_app=True)])


class InstanteTest(unittest.TestCase):
    def e(self, mes, dia):
        return ls.Entrada(mes, dia, "10:00:00.500", 1, 1, "I", "T", "x")

    def test_the_year_is_the_one_given(self):
        self.assertEqual(datetime(2026, 11, 8, 10, 0, 0, 500000), ls._instante(self.e(11, 8), 2026, []))

    def test_a_december_log_read_in_january_takes_the_year_closest_to_the_byebye(self):
        bye = datetime(2026, 12, 31, 23, 59, 59)
        self.assertEqual(datetime(2026, 12, 31, 10, 0, 0, 500000), ls._instante(self.e(12, 31), 2027, [bye]))

    def test_a_day_the_year_does_not_have_has_no_instant_unless_a_neighbour_year_has_it(self):
        self.assertIsNone(ls._instante(self.e(2, 30), 2026, []))
        self.assertEqual(datetime(2028, 2, 29, 10, 0, 0, 500000), ls._instante(self.e(2, 29), 2027, []))


class AndroidAutoTravouTest(unittest.TestCase):
    def crash(self, processo, hora="10:00:05.000", pid=900):
        return [ls.Entrada(11, 8, hora, pid, pid, "E", "AndroidRuntime", "FATAL EXCEPTION: main"),
                ls.Entrada(11, 8, hora, pid, pid, "E", "AndroidRuntime", f"Process: {processo}, PID: {pid}"),
                ls.Entrada(11, 8, hora, pid, pid, "E", "AndroidRuntime", "java.lang.NullPointerException: Attempt to invoke virtual method on user data")]

    def test_the_class_only_is_told_and_the_message_never(self):
        saida = ls.travadas_do_android_auto(self.crash(ls.GEARHEAD + ":projection"), [datetime(2026, 11, 8, 10, 0, 3, 800000)], 2026)
        self.assertEqual(["10:00:05: o Android Auto (gearhead:projection) travou — java.lang.NullPointerException (1,2 s depois do byebye do REau)"], saida)
        self.assertNotIn("user data", saida[0])

    def test_a_crash_far_from_any_byebye_says_so(self):
        saida = ls.travadas_do_android_auto(self.crash(ls.GEARHEAD), [datetime(2026, 11, 8, 9, 0, 0)], 2026)
        self.assertIn("(gearhead) travou", saida[0].replace(":projection", ""))
        self.assertIn("sem byebye do REau nos 60 s antes", saida[0])

    def test_the_byebye_exactly_sixty_seconds_before_counts_and_a_second_more_does_not(self):
        self.assertIn("60,0 s depois", ls.travadas_do_android_auto(self.crash(ls.GEARHEAD), [datetime(2026, 11, 8, 10, 0, 5) - __import__("datetime").timedelta(seconds=60)], 2026)[0])
        self.assertIn("sem byebye", ls.travadas_do_android_auto(self.crash(ls.GEARHEAD), [datetime(2026, 11, 8, 10, 0, 5) - __import__("datetime").timedelta(seconds=61)], 2026)[0])

    def test_another_apps_crash_is_not_told(self):
        self.assertEqual([], ls.travadas_do_android_auto(self.crash("com.exemplo.outro"), [], 2026))


class TravadasDoAppTest(unittest.TestCase):
    def app(self):
        return [ls.Entrada(11, 8, "10:00:00.000", 50, 50, "I", "ActivityManager", "Start proc 4242:com.reau.app/u0a123 for activity"),
                ls.Entrada(11, 8, "10:01:00.000", 4242, 4242, "I", "HWUI", "Davey! duration=900ms; Flags=0"),
                ls.Entrada(11, 8, "10:02:00.000", 4242, 4242, "I", "HWUI", "Davey! duration=750ms; Flags=0"),
                ls.Entrada(11, 8, "10:03:00.000", 4242, 4242, "I", "Choreographer", "Skipped 150 frames! The application may be doing too much work"),
                ls.Entrada(11, 8, "10:04:00.000", 4242, 4242, "I", "Choreographer", "Skipped 99 frames!"),
                ls.Entrada(11, 8, "10:05:00.000", 777, 777, "I", "HWUI", "Davey! duration=5000ms; Flags=0")]  # another process

    def test_only_the_apps_process_counts_and_the_worst_is_told(self):
        saida = ls.travadas_do_app(self.app())
        self.assertEqual(["10:01:00: o app travou quadros (2× Davey!, o pior de 900 ms)", "10:03:00: o app pulou quadros (1× com 100 ou mais, o pior com 150)"], saida)

    def test_a_hundred_skipped_frames_count_and_ninety_nine_do_not(self):
        base = self.app()[:1]
        cem = ls.Entrada(11, 8, "10:03:00.000", 4242, 4242, "I", "Choreographer", "Skipped 100 frames!")
        noventa = ls.Entrada(11, 8, "10:03:01.000", 4242, 4242, "I", "Choreographer", "Skipped 99 frames!")
        self.assertEqual(["10:03:00: o app pulou quadros (1× com 100 ou mais, o pior com 100)"], ls.travadas_do_app(base + [cem, noventa]))

    def test_without_the_process_start_nothing_is_the_apps(self):
        self.assertEqual([], ls.travadas_do_app(self.app()[1:]))

    def test_the_per_event_form_names_the_walk_and_puts_it_first(self):
        e = [ls.Entrada(11, 8, "10:00:10.000", 1, 1, "I", "HWUI", "Davey! duration=900ms"),
             ls.Entrada(11, 8, "10:05:00.500", 1, 1, "I", "Choreographer", "Skipped 120 frames!")]
        caminhada = [("start", datetime(2026, 11, 8, 10, 4, 58), datetime(2026, 11, 8, 10, 5, 0))]  # the frame is logged 0.5 s after the walk ended: within 1 s
        saida = ls.travadas_do_app_por_evento(e, 2026, caminhada)
        self.assertEqual(["08/11 10:00:10: o app travou a tela — Davey! de 900 ms", "08/11 10:05:00: o app travou a tela — 120 quadros pulados (durante a caminhada de iniciar o servidor)"], saida)
        e[1] = ls.Entrada(11, 8, "10:05:01.500", 1, 1, "I", "Choreographer", "Skipped 120 frames!")  # 1.5 s after: out
        self.assertNotIn("caminhada", ls.travadas_do_app_por_evento(e, 2026, caminhada)[1])

    def test_the_per_event_list_stops_at_the_limit_and_says_how_many_more(self):
        e = [ls.Entrada(11, 8, f"10:{k // 60:02d}:{k % 60:02d}.000", 1, 1, "I", "HWUI", "Davey! duration=900ms") for k in range(ls.POR_EVENTO_MAX + 5)]
        saida = ls.travadas_do_app_por_evento(e, 2026, [])
        self.assertEqual(ls.POR_EVENTO_MAX + 1, len(saida))
        self.assertIn("mais 5 travadas", saida[-1])


class WifiNasQuedasTest(unittest.TestCase):
    def sistema(self):
        return [ls.Entrada(11, 8, "10:00:49.000", 1, 1, "I", "wpa_supplicant", f"wlan0: CTRL-EVENT-DISCONNECTED bssid={MAC_FF} reason=3 locally_generated=1"),
                ls.Entrada(11, 8, "10:00:50.000", 1, 1, "I", "wpa_supplicant", "wlan0: CTRL-EVENT-SIGNAL-CHANGE above=1 signal=-60"),
                ls.Entrada(11, 8, "10:00:51.000", 1, 1, "I", "WifiService", f"disconnect from RE_AB12_345678 bssid={MAC_FF}"),
                ls.Entrada(11, 8, "10:00:52.000", 1, 1, "I", "WifiService", "scan found CasaDaTia"),
                ls.Entrada(11, 8, "10:30:00.000", 1, 1, "I", "wpa_supplicant", "wlan0: CTRL-EVENT-CONNECTED")]

    def test_the_drop_gets_the_systems_events_with_the_dash_name_and_the_mac_hidden(self):
        saida = ls.wifi_nas_quedas(self.sistema(), [datetime(2026, 11, 8, 10, 0, 53)], 2026)
        self.assertEqual(1, len(saida))
        self.assertIn("10:00:53: o Wi-Fi do painel caiu — o sistema disse:", saida[0])
        self.assertIn("wpa_supplicant CTRL-EVENT-DISCONNECTED reason=3 locally_generated=1", saida[0])
        self.assertIn(f"WifiService disconnect from {mascarar.MASK} bssid={mascarar.MASK_MAC}", saida[0])
        for vazou in ("AB12", "aa:bb", "CasaDaTia", "SIGNAL-CHANGE"):
            self.assertNotIn(vazou, saida[0])

    def test_the_window_is_30_s_before_and_10_s_after(self):
        e = [ls.Entrada(11, 8, "10:00:23.000", 1, 1, "I", "wpa_supplicant", "wlan0: CTRL-EVENT-DISCONNECTED reason=1"),
             ls.Entrada(11, 8, "10:01:03.000", 1, 1, "I", "wpa_supplicant", "wlan0: CTRL-EVENT-CONNECTED")]
        queda = datetime(2026, 11, 8, 10, 0, 53)
        self.assertEqual(1, len(ls.wifi_nas_quedas(e[:1], [queda], 2026)))  # exactly 30 s before
        self.assertEqual(1, len(ls.wifi_nas_quedas(e[1:], [queda], 2026)))  # 10 s after
        self.assertEqual([], ls.wifi_nas_quedas([ls.Entrada(11, 8, "10:00:22.900", 1, 1, "I", "wpa_supplicant", "CTRL-EVENT-DISCONNECTED")], [queda], 2026))

    def test_a_drop_with_nothing_in_the_log_gives_no_line_and_the_bike_that_went_off_is_told(self):
        self.assertEqual([], ls.wifi_nas_quedas([], [datetime(2026, 11, 8, 10, 0, 53)], 2026))
        saida = ls.wifi_nas_quedas(self.sistema(), [datetime(2026, 11, 8, 10, 0, 53)], 2026, da_moto={datetime(2026, 11, 8, 10, 0, 53)})
        self.assertIn("caiu (moto desligada) —", saida[0])

    def test_more_than_the_limit_of_lines_is_cut_and_counted(self):
        e = [ls.Entrada(11, 8, "10:00:50.000", 1, 1, "I", "wpa_supplicant", f"wlan0: CTRL-EVENT-DISCONNECTED reason={k}") for k in range(ls.WIFI_POR_QUEDA + 3)]
        self.assertIn("(+3 linhas)", ls.wifi_nas_quedas(e, [datetime(2026, 11, 8, 10, 0, 53)], 2026)[0])


class EnchenteEBufferTest(unittest.TestCase):
    def test_the_thermal_flood_is_not_coverage_and_the_tags_tell_the_buffer(self):
        termica = ls.Entrada(11, 8, "10:00:00.000", 1, 1, "E", "ThermalHalWrapper", "x")
        self.assertTrue(ls.e_enchente(termica))
        self.assertFalse(ls.etiqueta_do_buffer(termica, "main"))
        self.assertTrue(ls.etiqueta_do_buffer(ls.Entrada(11, 8, "10:00:00.000", 1, 1, "I", "wpa_supplicant", "x"), "main"))
        self.assertFalse(ls.etiqueta_do_buffer(ls.Entrada(11, 8, "10:00:00.000", 1, 1, "I", "wpa_supplicant", "x"), "system"))
        self.assertTrue(ls.etiqueta_do_buffer(ls.Entrada(11, 8, "10:00:00.000", 1, 1, "I", "ActivityManager", "x"), "system"))


if __name__ == "__main__":
    unittest.main()
