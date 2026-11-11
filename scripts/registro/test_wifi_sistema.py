"""Direct tests of wifi_sistema.py (item 41, 11/11/2026): the dumpsys of the Wi-Fi read for the reader. Synthetic dumps; the network names are invented, and the test checks that
no name of a network that is not the dash's, and no BSSID, comes out."""
import sys
import tempfile
import unittest
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import wifi_sistema as ws  # noqa: E402

# MACs built at run time: no literal hardware address sits in a versioned file (PrivacyGuardTest).
MAC_FF = ":".join(["aa", "bb", "cc", "dd", "ee", "ff"])
MAC_01 = ":".join(["aa", "bb", "cc", "dd", "ee", "01"])
MAC_02 = ":".join(["aa", "bb", "cc", "dd", "ee", "02"])
MAC_UPPER = ":".join(["0A", "1B", "2C", "3D", "4E", "5F"])

DUMP = f"""mConnectionEvents:
startTime=11-3 7:45:2.909, SSID="RE_AB12_345678", BSSID={MAC_01}, durationMillis=689, connectionResult=1, signalStrength=-58, mChannelInfo=2447, connectionNominator=NOMINATOR_SPECIFIER, uid=10492
startTime=11-3 8:00:0.5, SSID="CasaDaTia", BSSID={MAC_02}, durationMillis=900, connectionResult=1, signalStrength=-40, mChannelInfo=5180, connectionNominator=NOMINATOR_SAVED, uid=1000
startTime=11-3 9:10:11.0, SSID="RE_AB12_345678", BSSID={MAC_01}, durationMillis=2000, connectionResult=3, signalStrength=-80, mChannelInfo=2427, connectionNominator=NOMINATOR_SUGGESTION, uid=1010
StaEventList:
11-03 18:46:15.118 NETWORK_DISCONNECTION_EVENT local_gen=false reason=-1:UNSPECIFIED lastRssi=-54 lastFreq=2452 extra
11-03 07:45:30.000 NETWORK_DISCONNECTION_EVENT local_gen=true reason=3:DEAUTH_LEAVING lastRssi=-60 lastFreq=2447
11-03 07:46:00.000 SOMETHING_ELSE foo=bar
"""


def dump(texto=DUMP, nome="dumpsys-wifi.txt"):
    d = Path(tempfile.mkdtemp())
    (d / nome).write_text(texto, encoding="utf-8")
    return d, d / nome


class LerTest(unittest.TestCase):
    def test_only_the_dashs_unions_are_kept_with_who_asked_the_signal_and_the_channel(self):
        _, f = dump()
        unioes, _ = ws.ler(f, 2026)
        self.assertEqual(2, len(unioes))
        u = unioes[0]
        self.assertEqual(datetime(2026, 11, 3, 7, 45, 2, 909000), u.quando)
        self.assertEqual((689, True, "NOMINATOR_SPECIFIER", "10492", -58, 2447), (u.ms, u.ok, u.nominador, u.uid, u.dbm, u.mhz))
        self.assertFalse(unioes[1].ok)  # connectionResult=3

    def test_no_name_and_no_bssid_is_kept_in_any_field(self):
        _, f = dump()
        unioes, desconexoes = ws.ler(f, 2026)
        guardado = repr(unioes) + repr(desconexoes)
        for vazou in ("CasaDaTia", "aa:bb", "AB12", "RE_"):
            self.assertNotIn(vazou, guardado)

    def test_the_disconnections_come_in_order_with_reason_who_and_the_last_signal(self):
        _, f = dump()
        _, desconexoes = ws.ler(f, 2026)
        self.assertEqual([datetime(2026, 11, 3, 7, 45, 30), datetime(2026, 11, 3, 18, 46, 15, 118000)], [d.quando for d in desconexoes])
        self.assertEqual(("true", "3:DEAUTH_LEAVING", -60, 2447), (desconexoes[0].local_gen, desconexoes[0].motivo, desconexoes[0].dbm, desconexoes[0].mhz))

    def test_a_missing_file_or_an_impossible_date_gives_nothing(self):
        self.assertEqual(([], []), ws.ler(None, 2026))
        _, f = dump(f'startTime=2-30 7:45:2.909, SSID="RE_AB12_345678", BSSID={MAC_01}, durationMillis=1, connectionResult=1\n')
        self.assertEqual(([], []), ws.ler(f, 2026))


class ArquivoTest(unittest.TestCase):
    def test_the_whole_dump_wins_over_the_masked_one_and_the_masked_is_used_alone(self):
        d = Path(tempfile.mkdtemp())
        (d / "dumpsys-wifi.mascarado.txt").write_text("", encoding="utf-8")
        self.assertEqual("dumpsys-wifi.mascarado.txt", ws.arquivo([d]).name)
        (d / "dumpsys-wifi.txt").write_text("", encoding="utf-8")
        self.assertEqual("dumpsys-wifi.txt", ws.arquivo([d]).name)

    def test_it_is_found_in_sistema_in_the_collection_above_the_diag_and_as_a_loose_file(self):
        raiz = Path(tempfile.mkdtemp())
        (raiz / "sistema").mkdir()
        (raiz / "diag").mkdir()
        (raiz / "sistema" / "dumpsys-wifi.txt").write_text("", encoding="utf-8")
        self.assertEqual("dumpsys-wifi.txt", ws.arquivo([raiz]).name)
        self.assertEqual("dumpsys-wifi.txt", ws.arquivo([raiz / "diag"]).name)
        self.assertEqual("dumpsys-wifi.txt", ws.arquivo([raiz / "sistema" / "dumpsys-wifi.txt"]).name)
        self.assertIsNone(ws.arquivo([Path(tempfile.mkdtemp())]))


class MaisPertoEFimTest(unittest.TestCase):
    def setUp(self):
        _, f = dump()
        self.unioes, self.desconexoes = ws.ler(f, 2026)

    def test_the_closest_disconnection_inside_the_window(self):
        quando = datetime(2026, 11, 3, 7, 45, 32)
        self.assertEqual("3:DEAUTH_LEAVING", ws.mais_perto(self.desconexoes, quando, 5, 5).motivo)
        self.assertIsNone(ws.mais_perto(self.desconexoes, datetime(2026, 11, 3, 7, 45, 40), 5, 5))
        self.assertEqual("3:DEAUTH_LEAVING", ws.mais_perto(self.desconexoes, datetime(2026, 11, 3, 7, 45, 35), 5, 5).motivo)  # exactly 5 s before it (the disconnection is at 07:45:30)
        self.assertEqual("3:DEAUTH_LEAVING", ws.mais_perto(self.desconexoes, datetime(2026, 11, 3, 7, 45, 25), 5, 5).motivo)  # exactly 5 s after the time asked

    def test_the_end_of_a_union_is_the_first_disconnection_after_it_formed(self):
        assert self.unioes[0].quando < self.desconexoes[0].quando
        self.assertEqual("3:DEAUTH_LEAVING", ws.fim_da_uniao(self.unioes[0], self.desconexoes).motivo)
        self.assertIsNone(ws.fim_da_uniao(self.unioes[1], [d for d in self.desconexoes if d.quando < datetime(2026, 11, 3, 9)]))


class UidsTest(unittest.TestCase):
    def test_the_package_of_each_uid_comes_from_the_wifi_and_the_connectivity_dumps(self):
        d = Path(tempfile.mkdtemp())
        wifi = d / "dumpsys-wifi.txt"
        wifi.write_text("WifiConfigStore uid=10492 name=com.reau.app\nother uid=10223 name=com.exemplo.outroapp\nuid=10492 name=com.nao.vale.de.novo\n", encoding="utf-8")
        (d / "dumpsys-connectivity.txt").write_text("NetworkRequest RequestorUid: 10185 RequestorPkg: com.android.systemui\n", encoding="utf-8")
        nomes = ws.nomes_dos_uids(wifi)
        self.assertEqual({"10492": "com.reau.app", "10223": "com.exemplo.outroapp", "10185": "com.android.systemui"}, nomes)

    def test_without_a_file_there_are_no_names(self):
        self.assertEqual({}, ws.nomes_dos_uids(None))

    def test_the_apps_uid_comes_from_the_dump_and_from_the_info_of_the_collection(self):
        raiz = Path(tempfile.mkdtemp())
        (raiz / "sistema").mkdir()
        (raiz / "INFO.txt").write_text("modelo: x\nuser,com.reau.app,10492\nuser,com.outra,10999\n", encoding="utf-8")
        self.assertEqual({"10492"}, ws.uids_do_reau([raiz], raiz / "sistema" / "dumpsys-wifi.txt", {}))
        self.assertEqual({"10492", "10555"}, ws.uids_do_reau([raiz], raiz / "sistema" / "dumpsys-wifi.txt", {"10555": "com.reau.app"}))
        self.assertEqual(set(), ws.uids_do_reau([Path(tempfile.mkdtemp())], None, {"10555": "com.outro"}))


if __name__ == "__main__":
    unittest.main()
