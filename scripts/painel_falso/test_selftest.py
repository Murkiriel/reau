"""Tests for painel_falso.py: the panel behind real sockets on this machine's loopback, against the minimal phone side; the self-test; and
the command line. No network but the loopback, on ports the system picks.

Run from this folder:  python3 -B -m unittest discover -p "test_*.py"
"""
import contextlib
import io
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import celular_minimo  # noqa: E402
import log_painel  # noqa: E402
import painel_falso  # noqa: E402
import protocolo as p  # noqa: E402

TOOL = os.path.join(HERE, "painel_falso.py")
LOOPBACK = "127.0.0.1"


def until(what, timeout=5.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if what():
            return True
        time.sleep(0.01)
    return bool(what())


class Loopback:
    """A panel server and a minimal phone joined over the loopback."""

    def __init__(self, phone_ssid="RE_BENCH_0001", script=(), **options):
        self.log = log_painel.Log(quiet=True, keep=True)
        self.phone = celular_minimo.MinimalPhone((LOOPBACK, 1), (LOOPBACK, 1), phone_ssid)
        self.server = painel_falso.PanelServer(self.log, bind=LOOPBACK, ctrl_port=0, rtp_port=0, phone_port=self.phone.rx_port, **options)
        self.phone.ctrl_dest = (LOOPBACK, self.server.ctrl_port)
        self.phone.rtp_dest = (LOOPBACK, self.server.rtp_port)
        self.panel = self.server.panel
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.server.serve, args=(self.stop, script), daemon=True)
        self.thread.start()

    def close(self):
        self.stop.set()
        self.thread.join(3.0)
        self.phone.close()


class SelfTestTest(unittest.TestCase):
    def test_the_self_test_passes_and_says_each_thing_it_checked(self):
        out = io.StringIO()
        passed, failures = painel_falso.selftest(stream=out)
        self.assertEqual([], failures)
        self.assertEqual(17, len(passed))
        text = out.getvalue()
        self.assertIn("SELFTEST OK: 17 checks passed, 0 failed", text)
        for needle in ("the key exchange ends with the dash's confirmation", "the frames put back together are the ones sent, byte for byte", "every button of the joystick reaches the phone",
                       "the phone's exit sequence is seen whole", "the .h264 file holds the video sent", "no protocol error in the whole run (0)"):
            self.assertIn("ok   " + needle, text)

    def test_from_the_command_line_it_ends_with_zero_and_leaves_the_log_and_the_video_where_asked(self):
        with tempfile.TemporaryDirectory() as folder:
            done = subprocess.run([sys.executable, "-B", TOOL, "--selftest", "--out", folder], capture_output=True, text=True, timeout=60)
            self.assertEqual(0, done.returncode, done.stdout + done.stderr)
            self.assertIn("SELFTEST OK", done.stdout)
            names = sorted(os.listdir(folder))
            self.assertEqual(["selftest.log"], [name for name in names if name.endswith(".log")])
            self.assertEqual(1, len([name for name in names if name.endswith(".h264")]))
            with open(os.path.join(folder, "selftest.log"), encoding="utf-8") as f:
                log = f.read()
            self.assertIn("[auth] session key accepted (07 01 01)", log)
            self.assertIn("[rx] seq=", log)
            self.assertIn("0800:<128B left out>", log)

    def test_a_phone_that_cuts_its_exit_sequence_short_fails_it(self):
        whole = p.EXIT_SEQUENCE
        original = celular_minimo.MinimalPhone.goodbye

        def short(phone):
            for _, type_, sub, value in whole[:6]:
                phone.send_tlvs(painel_falso.painel.k1g.tlv(type_, sub, value))
        celular_minimo.MinimalPhone.goodbye = short
        try:
            out = io.StringIO()
            _, failures = painel_falso.selftest(stream=out)
        finally:
            celular_minimo.MinimalPhone.goodbye = original
        self.assertIn("the phone's exit sequence is seen whole and ends the session", failures)
        self.assertTrue(any(failure.startswith("no protocol error in the whole run") for failure in failures))
        self.assertIn("SELFTEST FAILED", out.getvalue())

    def test_the_exit_code_says_a_failed_self_test(self):
        original = painel_falso.selftest
        painel_falso.selftest = lambda **_: (["one"], ["another"])
        try:
            self.assertEqual(1, painel_falso.main(["--selftest"]))
            painel_falso.selftest = lambda **_: (["one"], [])
            self.assertEqual(0, painel_falso.main(["--selftest"]))
        finally:
            painel_falso.selftest = original


class LoopbackTest(unittest.TestCase):
    def setUp(self):
        self.bench = None

    def tearDown(self):
        if self.bench:
            self.bench.close()

    def test_a_phone_with_another_dash_name_is_rejected_and_greets_again(self):
        self.bench = Loopback(phone_ssid="RE_OTHER_0002", ssid="RE_BENCH_0001")
        self.assertFalse(self.bench.phone.connect(timeout=0.8, greet_every=0.2))
        self.assertGreaterEqual(self.bench.phone.rejections, 1)
        self.assertFalse(self.bench.panel.authenticated)
        self.assertGreaterEqual(self.bench.log.errors, 1)

    def test_a_first_key_rejected_on_purpose_is_followed_by_one_that_is_taken(self):
        self.bench = Loopback(reject_first_key=True)
        self.assertTrue(self.bench.phone.connect())
        self.assertEqual(1, self.bench.phone.rejections)
        self.assertTrue(until(lambda: self.bench.panel.session_key == self.bench.phone.session_key))

    def test_a_young_dash_answers_only_when_it_is_old_enough(self):
        self.bench = Loopback(age=p.READY_COUNT - 1)
        started = time.monotonic()
        self.assertTrue(self.bench.phone.connect(greet_every=0.2))
        self.assertGreaterEqual(time.monotonic() - started, 0.7)
        self.assertEqual(p.READY_COUNT - 1, self.bench.phone.heartbeats[0])

    def test_the_commands_given_beforehand_wait_for_the_key_and_run_in_order(self):
        self.bench = Loopback(script=["press left", "wait 0.3", "press right"])
        time.sleep(0.3)
        self.assertEqual([], self.bench.phone.buttons)
        self.assertTrue(self.bench.phone.connect())
        self.assertTrue(until(lambda: self.bench.phone.buttons == [0x14]))
        first = time.monotonic()
        self.assertTrue(until(lambda: self.bench.phone.buttons == [0x14, 0x13]))
        self.assertGreaterEqual(time.monotonic() - first, 0.2)

    def test_quit_stops_the_server_and_ends_the_session(self):
        self.bench = Loopback()
        self.assertTrue(self.bench.phone.connect())
        self.bench.server.submit("quit")
        self.bench.thread.join(3.0)
        self.assertFalse(self.bench.thread.is_alive())
        self.assertEqual(1, self.bench.panel.sessions)

    def test_a_packet_the_panel_trips_on_costs_that_packet_and_not_the_session(self):
        self.bench = Loopback()

        def trip(packet):
            raise RuntimeError("tripped")
        self.bench.panel.sink.on_packet = trip
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sender:
            sender.sendto(bytes(20), (LOOPBACK, self.bench.server.rtp_port))
            self.assertTrue(until(lambda: any("could not be handled (RuntimeError: tripped); it is dropped" in line for line in self.bench.log.lines)))
        self.assertTrue(self.bench.phone.connect())

    def test_the_answers_go_to_the_phones_fixed_port_whatever_port_it_sent_from(self):
        self.bench = Loopback()
        self.assertTrue(self.bench.phone.connect())
        self.assertNotEqual(self.bench.phone.rx_port, self.bench.phone._tx.getsockname()[1])


class CommandLineTest(unittest.TestCase):
    def test_the_defaults_are_the_dashs_ports_and_every_interface(self):
        args = painel_falso.arguments([])
        self.assertEqual(("", 2000, 5000, 2002), (args.bind, args.ctrl_port, args.rtp_port, args.phone_port))
        self.assertEqual((p.CTRL_PORT, p.RTP_PORT, p.RX_PORT), (args.ctrl_port, args.rtp_port, args.phone_port))
        self.assertEqual(("every", 30, None, False), (args.ack, args.age, args.ssid, args.selftest))

    def test_the_broadcast_address_of_an_address(self):
        self.assertEqual(p.BROADCAST, painel_falso.broadcast_of(p.DASH_IP))
        self.assertIsNone(painel_falso.broadcast_of("127.0.0.1"))
        self.assertIsNone(painel_falso.broadcast_of("0.0.0.0"))
        self.assertIsNone(painel_falso.broadcast_of("localhost"))

    def test_a_broadcast_address_this_machine_does_not_have_is_said_and_the_panel_goes_on(self):
        log = log_painel.Log(quiet=True, keep=True)
        server = painel_falso.PanelServer(log, bind=LOOPBACK, ctrl_port=0, rtp_port=0, broadcast="203.0.113.255")
        try:
            self.assertTrue(any("could not also listen on the broadcast address 203.0.113.255" in line for line in log.lines), log.lines)
            self.assertGreater(server.ctrl_port, 0)
        finally:
            server.close()

    def test_a_port_already_taken_is_said_and_the_exit_code_is_2(self):
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as taken, tempfile.TemporaryDirectory() as folder:
            taken.bind((LOOPBACK, 0))
            err = io.StringIO()
            with contextlib.redirect_stderr(err):
                code = painel_falso.main(["--bind", LOOPBACK, "--ctrl-port", str(taken.getsockname()[1]), "--rtp-port", "0", "--out", folder])
            self.assertEqual(2, code)
            self.assertIn("could not open the dash's ports", err.getvalue())

    def test_commands_typed_on_the_standard_input_reach_the_panel(self):
        with tempfile.TemporaryDirectory() as folder:
            done = subprocess.run([sys.executable, "-B", TOOL, "--bind", LOOPBACK, "--ctrl-port", "0", "--rtp-port", "0", "--out", folder],
                                  input="status\npress left\nquit\n", capture_output=True, text=True, timeout=60)
            self.assertEqual(0, done.returncode, done.stdout + done.stderr)
            self.assertIn("[panel] listening on 127.0.0.1, control UDP ", done.stdout)
            self.assertIn("RSA-1024 key pair made for this run (kept in memory only)", done.stdout)
            self.assertIn("[status] no phone; dash age 30 s; no video frame; protocol errors 0", done.stdout)
            self.assertIn("[button] 0x14 not sent: no phone has spoken yet", done.stdout)
            self.assertIn("[panel] stopped; 0 session(s), 0 protocol error(s)", done.stdout)
            (log,) = [name for name in os.listdir(folder) if name.endswith(".log")]
            with open(os.path.join(folder, log), encoding="utf-8") as f:
                self.assertIn("[panel] stopped", f.read())
            # Nothing of the key pair is left behind: the log is the only file.
            self.assertEqual([log], os.listdir(folder))


if __name__ == "__main__":
    unittest.main()
