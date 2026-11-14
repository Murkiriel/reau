import tempfile
import contextlib
import io
import unittest
from pathlib import Path

import mascarar

# MACs built at run time: no literal hardware address sits in a versioned file (PrivacyGuardTest).
MAC_FF = ":".join(["aa", "bb", "cc", "dd", "ee", "ff"])
MAC_01 = ":".join(["aa", "bb", "cc", "dd", "ee", "01"])
MAC_02 = ":".join(["aa", "bb", "cc", "dd", "ee", "02"])
MAC_UPPER = ":".join(["0A", "1B", "2C", "3D", "4E", "5F"])


class MascararTest(unittest.TestCase):
    def test_the_dash_name_is_hidden_wherever_it_stands(self):
        self.assertEqual(f"SSID: \"{mascarar.MASK}\" joined", mascarar.mascarar('SSID: "RE_AB12_345678" joined'))
        self.assertEqual(f"a {mascarar.MASK}, b {mascarar.MASK}", mascarar.mascarar("a RE_AB12_345678, b RE_zz99_000001"))

    def test_a_longer_or_odd_variant_does_not_leak(self):
        self.assertNotIn("XYZ", mascarar.mascarar("net RE_XYZ1_ABC_extra"))

    def test_other_text_is_not_touched(self):
        for line in ("nothing here", "REAU is not RE_", "SSID ""HomeNet"" ok", "rate 5G_5 GHz"):
            self.assertEqual(line, mascarar.mascarar(line))

    def test_the_command_line_writes_a_masked_copy_and_leaves_the_original(self):
        with tempfile.TemporaryDirectory() as tmp:
            src, dst = Path(tmp) / "in.txt", Path(tmp) / "out.txt"
            src.write_text("10-26 wifi RE_AB12_345678 up\nline two\n", encoding="utf-8")
            self.assertEqual(0, mascarar.main([str(src), str(dst)]))
            self.assertEqual(f"10-26 wifi {mascarar.MASK} up\nline two\n", dst.read_text(encoding="utf-8"))
            self.assertIn("RE_AB12_345678", src.read_text(encoding="utf-8"))

    def test_the_output_may_not_be_the_input_itself_and_the_file_is_left_whole(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "in.txt"
            src.write_text("wifi RE_AB12_345678 up\n", encoding="utf-8")
            with contextlib.redirect_stderr(io.StringIO()) as err:
                self.assertEqual(2, mascarar.main([str(src), str(src)]))
                self.assertEqual(2, mascarar.main([str(src), str(Path(tmp) / "." / "in.txt")]))
            self.assertIn("próprio arquivo", err.getvalue())
            self.assertEqual("wifi RE_AB12_345678 up\n", src.read_text(encoding="utf-8"))

    def test_wrong_arguments_say_how_to_use_it(self):
        self.assertEqual(2, mascarar.main([]))


class OutrasRedesEMacTest(unittest.TestCase):
    """Item 23 (11/11/2026): the names of networks that are not the dash's, and the MAC addresses, are hidden too. Invented names only."""

    def test_every_mac_address_is_hidden_in_either_case(self):
        self.assertEqual(f"wlan0: Associated with {mascarar.MASK_MAC} and {mascarar.MASK_MAC}",
                         mascarar.mascarar(f"wlan0: Associated with {MAC_FF} and {MAC_UPPER}"))

    def test_a_clock_time_or_a_short_hex_run_is_not_a_mac(self):
        for linha in ("10:06:55.123 ok", "id 12:34:56:78", "a1:b2:c3:d4:e5 only five groups"):
            self.assertEqual(linha, mascarar.mascarar(linha))

    def test_a_quoted_name_after_ssid_or_configkey_is_hidden_with_its_quotes(self):
        self.assertEqual('mConfig SSID="<rede>" priority=1', mascarar.mascarar('mConfig SSID="Escritorio 5G" priority=1'))
        self.assertEqual('Cannot find network with configKey="<rede>"WPA_PSK', mascarar.mascarar('Cannot find network with configKey="CasaDaTia"WPA_PSK'))
        self.assertEqual("ssid='<rede>'", mascarar.mascarar("ssid='Cafe do Fulano'"))
        self.assertEqual('SSID: "<rede>" up', mascarar.mascarar('SSID: "CasaDaTia" up'))

    def test_the_unquoted_dumpsys_form_goes_to_the_comma_and_the_token_form_to_the_space(self):
        self.assertEqual(f"SSID: <rede>, BSSID: {mascarar.MASK_MAC}, level", mascarar.mascarar(f"SSID: Cafe do Fulano, BSSID: {MAC_FF}, level"))
        self.assertEqual(f"ssid=<rede> bssid={mascarar.MASK_MAC} level=-50", mascarar.mascarar(f"ssid=CasaDaTia bssid={MAC_01} level=-50"))

    def test_the_dash_name_after_the_keyword_is_masked_as_a_dash_and_not_as_a_network(self):
        self.assertEqual(f'SSID: "{mascarar.MASK}", BSSID', mascarar.mascarar('SSID: "RE_AB12_345678", BSSID'))
        self.assertEqual(f"ssid={mascarar.MASK} x", mascarar.mascarar("ssid=RE_AB12_345678 x"))
        self.assertEqual(f"SSID: {mascarar.MASK}, x", mascarar.mascarar("SSID: RE_AB12_345678, x"))

    def test_placeholders_and_unknown_markers_are_left_as_they_are(self):
        for linha in ("SSID: <unknown ssid>, x", "ssid=<rede> y", 'SSID: "<rede>"'):
            self.assertEqual(linha, mascarar.mascarar(linha, mascarar.nomes_de_redes(linha)))

    def test_a_name_found_after_a_keyword_is_hidden_where_a_line_says_it_alone(self):
        texto = 'SSID: "CasaDaTia"\nroaming to CasaDaTia now\nCasaDaTiaExtra is another word\n'
        nomes = mascarar.nomes_de_redes(texto)
        self.assertEqual({"CasaDaTia"}, nomes)
        saida = mascarar.mascarar(texto, nomes)
        self.assertEqual('SSID: "<rede>"\nroaming to <rede> now\nCasaDaTiaExtra is another word\n', saida)

    def test_names_shorter_than_three_characters_are_only_hidden_after_the_keyword(self):
        self.assertEqual('SSID: "<rede>" and ab', mascarar.mascarar('SSID: "ab" and ab', {"ab"}))

    def test_the_longest_name_is_hidden_first_so_a_name_inside_another_does_not_leave_a_piece(self):
        self.assertEqual("saw <rede> now", mascarar.mascarar("saw Casa Dois now", {"Casa", "Casa Dois"}))

    def test_a_number_alone_after_the_keyword_is_not_taken_for_a_network_name(self):
        self.assertEqual(set(), mascarar.nomes_de_redes("SSID: 12345, x"))

    def test_the_command_line_hides_networks_macs_and_the_names_given_by_hand(self):
        with tempfile.TemporaryDirectory() as tmp:
            src, dst = Path(tmp) / "in.txt", Path(tmp) / "out.txt"
            src.write_text(f'SSID: "CasaDaTia", BSSID: {MAC_FF}\nsaw Trabalho5G in range\nroaming to CasaDaTia\nRE_AB12_345678 up\n', encoding="utf-8")
            self.assertEqual(0, mascarar.main(["--rede", "Trabalho5G", str(src), str(dst)]))
            saida = dst.read_text(encoding="utf-8")
            self.assertEqual(f'SSID: "<rede>", BSSID: {mascarar.MASK_MAC}\nsaw <rede> in range\nroaming to <rede>\n{mascarar.MASK} up\n', saida)
            for vazou in ("CasaDaTia", "Trabalho5G", "aa:bb", "AB12"):
                self.assertNotIn(vazou, saida)
            self.assertIn("CasaDaTia", src.read_text(encoding="utf-8"))  # the original stays

    def test_rede_without_a_name_says_how_to_use_it(self):
        self.assertEqual(2, mascarar.main(["a.txt", "b.txt", "--rede"]))

    def test_the_system_log_reader_hides_with_the_same_masks(self):
        import logcat_sistema
        self.assertIs(mascarar.MAC, logcat_sistema.MAC)
        self.assertIs(mascarar.DASH_QUALQUER, logcat_sistema.DASH)


if __name__ == "__main__":
    unittest.main()
