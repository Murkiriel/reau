import tempfile
import contextlib
import io
import unittest
from pathlib import Path

import mascarar


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


if __name__ == "__main__":
    unittest.main()
