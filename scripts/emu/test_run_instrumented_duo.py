"""Tests for scripts/emu/run_instrumented_duo.py: the two-emulator suite must not need the last classes run again by
hand. In the duo runs the main emulator died several times right after VideoLoopThermalTest (five minutes of
screen-off streaming) and took the classes after it down with it.

Run from the repo root:  python -B -m unittest discover -s scripts/emu -p "test_run_instrumented_duo.py" -v
"""
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import run_instrumented_duo as duo  # noqa: E402

P = "com.reau.app."


class OrderTest(unittest.TestCase):
    def test_the_thermal_stream_runs_last_on_the_main_emulator(self):
        classes = [P + "video.VideoLoopThermalTest", P + "video.VideoLoopTest", P + "aa.AaReceiverTest",
                   P + "video.DashCompositorTest", P + "service.ProjectionEndToEndTest", P + "ui.MainScreenTest"]
        main, second, _ = duo.split(classes)
        self.assertEqual(P + "video.VideoLoopThermalTest", main[-1])
        # The other gap-and-pace classes still come after everything else, in their order, before it.
        self.assertEqual([P + "video.VideoLoopTest", P + "video.DashCompositorTest", P + "service.ProjectionEndToEndTest"],
                         main[-4:-1])
        self.assertEqual(sorted(classes), sorted(main + second))


class LeftOverTest(unittest.TestCase):
    def test_the_classes_with_no_ok_line_are_the_ones_to_run_again(self):
        out = "\n".join([
            "AaReceiverTest: OK (12 tests)",
            "VideoLoopTest: OK (9 tests), 1 PULADO(S)",
            "DashCompositorTest: FALHOU Tests run: 0,  Failures: 0  [emu_out/DashCompositorTest.txt]",
            "adb: device 'emulator-5554' not found",
        ])
        ran = [P + "aa.AaReceiverTest", P + "video.VideoLoopTest", P + "video.DashCompositorTest",
               P + "service.ProjectionEndToEndTest"]
        self.assertEqual([P + "video.DashCompositorTest", P + "service.ProjectionEndToEndTest"], duo.left_over(out, ran))

    def test_a_short_name_that_only_ends_like_another_is_not_taken_for_it(self):
        out = "ScreenTest: OK (3 tests)"
        self.assertEqual([P + "ui.MainScreenTest"], duo.left_over(out, [P + "ui.MainScreenTest", P + "ui.ScreenTest"]))
        out = "MainScreenTest: OK (3 tests)"
        self.assertEqual([P + "ui.ScreenTest"], duo.left_over(out, [P + "ui.MainScreenTest", P + "ui.ScreenTest"]))


class EmulatorGoneTest(unittest.TestCase):
    def test_only_a_missing_or_offline_main_emulator_is_gone(self):
        self.assertTrue(duo.gone("List of devices attached\nemulator-5556\tdevice\n", "emulator-5554"))
        self.assertTrue(duo.gone("List of devices attached\nemulator-5554\toffline\n", "emulator-5554"))
        self.assertFalse(duo.gone("List of devices attached\nemulator-5554\tdevice\nemulator-5556\tdevice\n", "emulator-5554"))


class RestartCommandTest(unittest.TestCase):
    def test_the_main_emulator_comes_back_as_it_is_started_by_hand(self):
        command = duo.emulator_command(duo.MAIN)
        self.assertTrue(command[0].endswith(os.path.join("emulator", "emulator" + duo.single.EXE)), command[0])
        main_avd = os.environ.get("REAU_AVD_MAIN", "REau_Test")
        second_avd = os.environ.get("REAU_AVD_SECOND", "REau_Test2")
        self.assertEqual(["-avd", main_avd, "-no-snapshot-load", "-dns-server", "8.8.8.8,1.1.1.1",
                          "-no-boot-anim"], command[1:])
        self.assertEqual(["-avd", second_avd, "-port", "5556", "-no-snapshot-load", "-dns-server", "8.8.8.8,1.1.1.1",
                          "-no-boot-anim"], duo.emulator_command(duo.SECOND)[1:])


if __name__ == "__main__":
    unittest.main()
