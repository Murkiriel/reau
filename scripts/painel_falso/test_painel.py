"""Tests for painel.py: what the bench panel answers to each thing the phone sends, packet by packet, with no socket and a clock of its own.

The phone's packets are the app's own (the hex of DashCommands, the key packet of DashAuth); test_sincronia.py holds them to the Kotlin.

Run from this folder:  python3 -B -m unittest discover -p "test_*.py"
"""
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import cripto  # noqa: E402
import k1g  # noqa: E402
import log_painel  # noqa: E402
import painel  # noqa: E402
import protocolo as p  # noqa: E402
import rtp  # noqa: E402

PHONE = "192.168.1.20"
SSID = "RE_BENCH_0001"
AES = bytes(range(100, 132))
KEY = None


def setUpModule():
    global KEY
    KEY = cripto.generate_key()


class Video:
    def __init__(self):
        self.path = "memory.h264"
        self.data = b""
        self.closed = False

    @property
    def bytes(self):
        return len(self.data)

    def write(self, data):
        self.data += data

    def close(self):
        self.closed = True


class Bench:
    """A panel, the packets it sent, its log, a clock moved by hand, and a phone that builds its packets as the app does."""

    def __init__(self, **options):
        self.now = 1000.0
        self.sent = []
        self.videos = []
        self.log = log_painel.Log(quiet=True, keep=True)
        self.panel = painel.FakePanel(self.log, self.sent.append, key=KEY, clock=lambda: self.now, open_video=self.open_video, **options)
        self.seq = 0
        self.packetizer = rtp.Packetizer()
        self.clock_ms = 0

    def open_video(self):
        self.videos.append(Video())
        return self.videos[-1]

    def phone(self, *tlvs, source=PHONE):
        self.panel.on_control(k1g.build(*tlvs, seq=self.seq), source)
        self.seq = (self.seq + 1) & 0xFF

    def phone_hex(self, hex_text):
        self.panel.on_control(k1g.patch_seq(bytes.fromhex(hex_text), self.seq), PHONE)
        self.seq = (self.seq + 1) & 0xFF

    def answers(self):
        """The TLVs the panel sent since the last call."""
        packets, self.sent[:] = list(self.sent), []
        return [tlv for packet in packets for tlv in k1g.parse(packet).tlvs]

    def key_packet(self, ssid=SSID, aes=AES, key=None):
        key = key or KEY
        return k1g.tlv(*p.SESSION_KEY, cripto.rsa_encrypt(ssid.encode("utf-8") + aes, key.n, key.e))

    def authenticate(self):
        self.phone_hex(p.AUTH_REQUEST)
        self.phone(self.key_packet())
        self.answers()

    def frame(self, unit):
        self.clock_ms += 250
        packets = self.packetizer.packetize(unit, self.clock_ms)
        for packet in packets:
            self.panel.on_rtp(packet, PHONE)
        return packets

    def goodbye(self):
        for _, type_, sub, value in p.EXIT_SEQUENCE:
            self.phone(k1g.tlv(type_, sub, value))

    def lines(self, tag=None):
        return [line for line in self.log.lines if tag is None or line.startswith("[%s]" % tag)]

    def said(self, text):
        return any(text in line for line in self.log.lines)


def nal(header, size):
    return bytes([header]) + bytes(i & 0xFF for i in range(size - 1))


class GreetingTest(unittest.TestCase):
    def test_the_greeting_is_answered_with_the_two_halves_of_the_public_key(self):
        bench = Bench()
        bench.phone_hex(p.AUTH_REQUEST)
        modulus, exponent = bench.answers()
        self.assertEqual((0x07, 0x00, KEY.modulus_bytes()), tuple(modulus))
        self.assertEqual((0x07, 0x03, bytes([1, 0, 1])), tuple(exponent))
        self.assertEqual(128, len(modulus.value))

    def test_each_half_goes_in_a_packet_of_its_own_with_a_rolling_sequence(self):
        bench = Bench()
        bench.phone_hex(p.AUTH_REQUEST)
        self.assertEqual([0, 1], [k1g.parse(packet).seq for packet in bench.sent])
        self.assertEqual([(), ()], [k1g.parse(packet).problems for packet in bench.sent])

    def test_nothing_is_sent_before_a_phone_speaks(self):
        bench = Bench()
        bench.panel.tick()
        bench.panel.press(0x14)
        self.assertEqual([], bench.sent)
        self.assertTrue(bench.said("0x14 not sent: no phone has spoken yet"))

    def test_a_dash_younger_than_26_s_ignores_the_greeting_until_it_gets_there(self):
        bench = Bench(age=12)
        bench.phone_hex(p.AUTH_REQUEST)
        self.assertEqual([], bench.answers())
        self.assertTrue(bench.said("greeting ignored: the dash is 12 s old and takes none before 26"))
        bench.now += 13.9
        bench.phone_hex(p.AUTH_REQUEST)
        self.assertEqual([], bench.answers())
        bench.now += 0.1
        bench.phone_hex(p.AUTH_REQUEST)
        self.assertEqual([p.KEY_MODULUS, p.KEY_EXPONENT], [tlv.key for tlv in bench.answers()])

    def test_the_first_greetings_can_be_ignored(self):
        bench = Bench(ignore_greetings=2)
        for expected in (0, 0, 2):
            bench.phone_hex(p.AUTH_REQUEST)
            self.assertEqual(expected, len(bench.answers()))

    def test_the_whole_first_burst_brings_only_the_key_and_no_exit_sequence(self):
        bench = Bench()
        for hex_text in (p.AUTH_REQUEST, "0016000200000000020100054b314720030557000155", "0016000200000000020100054b3147200405560001aa",
                         "0016000200000000020100054b3147200506050001aa", "0016000200000000020100054b3147200605170001aa",
                         "001d000200000000020100054b314720080a020008aa55000000000000",
                         "0044000a00000000020100054b3147200906080001ff060300015506040001a2060f0001aa"
                         "0601000101054c000113052d00020000051b0001190521000132054d000132"):
            bench.panel.on_control(bytes.fromhex(hex_text), PHONE)
        self.assertEqual([p.KEY_MODULUS, p.KEY_EXPONENT], [tlv.key for tlv in bench.answers()])
        self.assertEqual(0, bench.log.errors)
        self.assertEqual({}, bench.panel.unknown)
        self.assertIsNone(bench.panel.last_exit)
        self.assertEqual(0, bench.panel.sessions)


class KeyTest(unittest.TestCase):
    def test_the_apps_key_packet_is_taken_and_confirmed(self):
        bench = Bench()
        bench.phone_hex(p.AUTH_REQUEST)
        bench.answers()
        ciphertext = cripto.rsa_encrypt(SSID.encode() + AES, KEY.n, KEY.e)
        bench.phone_hex("0095000200000000020100054B3147200008000080" + ciphertext.hex())
        self.assertEqual([k1g.tlv(0x07, 0x01, 0x01)], bench.answers())
        self.assertEqual(AES, bench.panel.session_key)
        self.assertEqual(SSID, bench.panel.last_ssid)
        self.assertTrue(bench.panel.authenticated)
        self.assertEqual(0, bench.log.errors)

    def test_the_session_key_is_never_written_in_the_log(self):
        bench = Bench(show_text=True)
        bench.log.verbose = True
        bench.authenticate()
        text = "\n".join(bench.log.lines)
        self.assertNotIn(AES.hex(), text.lower())
        self.assertIn("0800:", text)  # the ciphertext, which only the dash's private key opens

    def test_a_name_with_accents_comes_through_as_utf_8(self):
        bench = Bench()
        bench.phone(bench.key_packet(ssid="RE_Ação_01"))
        self.assertEqual("RE_Ação_01", bench.panel.last_ssid)

    def test_a_key_sent_with_another_dash_name_is_rejected_when_the_name_is_set(self):
        bench = Bench(ssid=SSID)
        bench.phone(bench.key_packet(ssid="RE_OTHER_0002"))
        self.assertEqual([k1g.tlv(0x07, 0x01, 0x00)], bench.answers())
        self.assertFalse(bench.panel.authenticated)
        self.assertEqual(1, bench.log.errors)
        bench.phone(bench.key_packet())
        self.assertEqual([k1g.tlv(0x07, 0x01, 0x01)], bench.answers())

    def test_with_no_name_set_any_name_is_taken(self):
        bench = Bench()
        bench.phone(bench.key_packet(ssid="RE_OTHER_0002"))
        self.assertEqual([k1g.tlv(0x07, 0x01, 0x01)], bench.answers())

    def test_a_key_made_for_another_dash_is_rejected(self):
        bench = Bench()
        bench.phone(bench.key_packet(key=cripto.generate_key()))
        self.assertEqual([k1g.tlv(0x07, 0x01, 0x00)], bench.answers())
        self.assertFalse(bench.panel.authenticated)
        self.assertEqual(1, bench.log.errors)

    def test_a_key_of_the_wrong_size_or_with_too_little_inside_is_rejected(self):
        bench = Bench()
        bench.phone(k1g.tlv(*p.SESSION_KEY, bytes(64)))
        bench.phone(k1g.tlv(*p.SESSION_KEY, cripto.rsa_encrypt(b"RE_" + bytes(28), KEY.n, KEY.e)))
        self.assertEqual([k1g.tlv(0x07, 0x01, 0x00)] * 2, bench.answers())
        self.assertEqual(2, bench.log.errors)
        bench.phone(k1g.tlv(*p.SESSION_KEY, cripto.rsa_encrypt(bytes(32), KEY.n, KEY.e)))  # an empty name and a key: 32 bytes are enough
        self.assertEqual([k1g.tlv(0x07, 0x01, 0x01)], bench.answers())

    def test_the_first_key_can_be_rejected_on_purpose_and_the_next_is_taken(self):
        bench = Bench(reject_first_key=True)
        bench.phone(bench.key_packet())
        self.assertEqual([k1g.tlv(0x07, 0x01, 0x00)], bench.answers())
        self.assertEqual(0, bench.log.errors)
        bench.phone(bench.key_packet())
        self.assertEqual([k1g.tlv(0x07, 0x01, 0x01)], bench.answers())

    def test_a_greeting_after_the_key_is_a_phone_that_started_over(self):
        bench = Bench()
        bench.authenticate()
        bench.phone_hex(p.AUTH_REQUEST)
        self.assertEqual([p.KEY_MODULUS, p.KEY_EXPONENT], [tlv.key for tlv in bench.answers()])
        self.assertEqual(1, bench.panel.sessions)
        self.assertFalse(bench.panel.authenticated)
        self.assertEqual(PHONE, bench.panel.phone)
        self.assertTrue(bench.said("session over: the phone greeted again in an authenticated session"))


class HeartbeatTest(unittest.TestCase):
    def test_once_a_second_with_the_age_in_the_low_byte(self):
        bench = Bench(age=250)
        bench.phone(k1g.tlv(0x06, 0x06, 1, 2, 3))
        counters = []
        for _ in range(80):
            bench.panel.tick()
            bench.now += 0.1
            bench.phone(k1g.tlv(0x05, 0x56, 0x55))
            counters += [tlv.value for tlv in bench.answers() if tlv.key == p.HEARTBEAT]
        self.assertEqual([b"K1G\x00" + bytes([n & 0xFF]) for n in range(250, 258)], counters)

    def test_a_dash_older_than_255_s_still_takes_greetings(self):
        bench = Bench(age=260)
        bench.phone_hex(p.AUTH_REQUEST)
        self.assertEqual(2, len(bench.answers()))


class VideoTest(unittest.TestCase):
    def test_each_frame_is_written_and_answered_with_the_notice_of_its_kind(self):
        bench = Bench()
        bench.authenticate()
        units = [nal(0x67, 4000), nal(0x41, 300), nal(0x65, 200), nal(0x41, 3000)]
        for unit in units:
            bench.frame(unit)
        self.assertEqual([k1g.tlv(0x09, 0x06, 0x55), k1g.tlv(0x09, 0x04, 0x55), k1g.tlv(0x09, 0x06, 0x55), k1g.tlv(0x09, 0x04, 0x55)], bench.answers())
        self.assertEqual(b"".join(rtp.START_CODE + unit for unit in units), bench.videos[0].data)
        self.assertEqual(["memory.h264"], bench.panel.videos)
        self.assertEqual(0, bench.log.errors)
        self.assertTrue(bench.said("first frame: a keyframe, 4004 bytes in 3 packets"))

    def test_only_keyframes_or_no_frame_at_all_can_be_answered(self):
        for mode, expected in (("key", [p.NOTICE_IDR]), ("none", [])):
            bench = Bench(ack=mode)
            bench.authenticate()
            bench.frame(nal(0x67, 100))
            bench.frame(nal(0x41, 100))
            self.assertEqual(expected, [tlv.key for tlv in bench.answers()], mode)
        with self.assertRaises(ValueError):
            Bench(ack="some")

    def test_a_frame_with_a_lost_packet_is_neither_written_nor_answered_and_the_fault_is_an_error(self):
        bench = Bench()
        bench.authenticate()
        bench.frame(nal(0x67, 100))
        bench.clock_ms += 250
        first, _, third = bench.packetizer.packetize(nal(0x41, 4000), bench.clock_ms)
        bench.panel.on_rtp(first, PHONE)
        bench.panel.on_rtp(third, PHONE)
        bench.frame(nal(0x41, 200))
        self.assertEqual([p.NOTICE_IDR, p.NOTICE_P], [tlv.key for tlv in bench.answers()])
        self.assertEqual(rtp.START_CODE + nal(0x67, 100) + rtp.START_CODE + nal(0x41, 200), bench.videos[0].data)
        self.assertEqual(1, bench.log.errors)
        self.assertTrue(bench.said("ERROR: video packet 3: sequence 3 after 1 (1 lost)"))

    def test_video_before_the_key_is_an_error_told_once(self):
        bench = Bench()
        bench.phone_hex(p.AUTH_REQUEST)
        bench.frame(nal(0x67, 100))
        bench.frame(nal(0x41, 100))
        self.assertEqual(1, bench.log.errors)
        self.assertTrue(bench.said("video arrives before the session key was accepted"))

    def test_many_faults_are_all_counted_but_only_the_first_twenty_are_told_aloud(self):
        bench = Bench()
        bench.authenticate()
        for _ in range(30):
            bench.panel.on_rtp(bytes(5), PHONE)
        self.assertEqual(30, bench.log.errors)
        shown = []
        loud = log_painel.Log(quiet=True, keep=True)
        loud._write = lambda tag, text, visible: shown.append(visible)
        bench.panel.log = loud
        bench.panel.on_rtp(bytes(5), PHONE)
        self.assertEqual([False], shown)

    def test_faults_past_what_the_sink_keeps_are_still_counted(self):
        bench = Bench()
        bench.authenticate()
        for _ in range(rtp.FAULTS_KEPT + 7):
            bench.panel.on_rtp(bytes(5), PHONE)
        self.assertEqual(rtp.FAULTS_KEPT + 7, bench.log.errors)

    def test_the_figures_are_told_every_few_seconds_while_frames_come(self):
        bench = Bench(stats_every=2.0, silence_s=100)
        bench.authenticate()
        for _ in range(9):
            bench.frame(nal(0x41, 1000))
            bench.now += 0.25
            bench.panel.tick()
        self.assertEqual(["[video] 4.0 fps, 32 kbit/s, 8 frames (0 key), 1004/1004/1004 bytes a frame (least/mean/most); so far 0 lost, 0 late, 0 damaged frames"],
                         [line for line in bench.lines("video") if "fps" in line])
        bench.now += 2.0
        bench.panel.tick()
        bench.now += 2.0
        bench.panel.tick()
        self.assertTrue(bench.said("[video] no frame in the last 2 s"))


class ButtonTest(unittest.TestCase):
    def test_every_button_goes_as_09_00_with_its_code_in_one_byte(self):
        bench = Bench()
        bench.authenticate()
        for name, code in p.BUTTONS.items():
            self.assertTrue(bench.panel.command("press " + name))
            self.assertEqual([k1g.Tlv(0x09, 0x00, bytes([code]))], bench.answers(), name)
        self.assertEqual(len(p.BUTTONS), bench.panel.presses)

    def test_the_codes_are_the_apps(self):
        self.assertEqual({"zoom-in": 0x14, "zoom-out": 0x13, "next": 0x09, "prev": 0x0A, "in": 0x18, "back": 0x12, "play": 0x05,
                          "vol-up": 0x06, "vol-down": 0x07, "recentre": 0x15}, p.BUTTONS)

    def test_a_button_by_where_the_joystick_is_pushed_or_by_number(self):
        for word, code in (("left", 0x14), ("right", 0x13), ("down", 0x15), ("up", 0x12), ("middle", 0x18), ("LEFT", 0x14), ("0x2A", 0x2A), ("20", 0x14)):
            self.assertEqual(code, painel.button_code(word), word)
        for word in ("sideways", "0x100", "-1"):
            with self.assertRaises(ValueError):
                painel.button_code(word)

    def test_the_phones_echo_is_matched_to_the_press_and_timed(self):
        bench = Bench()
        bench.authenticate()
        bench.panel.press(0x14)
        bench.now += 0.012
        bench.phone(k1g.tlv(0x06, 0x80, 0x14))
        bench.phone(k1g.tlv(0x06, 0x0C, 0x30))
        self.assertEqual(1, bench.panel.echoes)
        self.assertTrue(bench.said("0x14 echoed by the phone (06 80) after 12 ms"))
        bench.now += 5
        bench.phone(k1g.tlv(0x05, 0x56, 0x55))
        bench.panel.tick()
        self.assertEqual(0, bench.log.errors)

    def test_a_press_the_phone_does_not_echo_in_two_seconds_is_an_error(self):
        bench = Bench()
        bench.authenticate()
        bench.panel.press(0x13)
        bench.now += 1.9
        bench.phone(k1g.tlv(0x06, 0x80, 0x14))  # the echo of another button
        bench.panel.tick()
        self.assertEqual(0, bench.log.errors)
        bench.now += 0.1
        bench.panel.tick()
        self.assertEqual(1, bench.log.errors)
        self.assertTrue(bench.said("button 0x13 was not echoed by the phone (06 80) in 2 s"))
        bench.now += 3
        bench.phone(k1g.tlv(0x05, 0x56, 0x55))
        bench.panel.tick()
        self.assertEqual(1, bench.log.errors)

    def test_the_start_of_navigation_is_not_taken_for_an_echo(self):
        bench = Bench()
        bench.authenticate()
        bench.phone(k1g.tlv(0x06, 0x80, 0x0B))
        self.assertTrue(bench.said("start of navigation (06 80 0B)"))
        self.assertEqual(0, bench.panel.echoes)


class ExitTest(unittest.TestCase):
    def test_the_sequence_is_the_nine_commands_of_the_apps_disconnect(self):
        self.assertEqual(["projection-stop", "projection-off", "back", "idle", "idle", "idle", "idle", "boundary", "boundary"],
                         [step[0] for step in p.EXIT_SEQUENCE])
        self.assertEqual([(0x05, 0x56, 0xAA), (0x06, 0x05, 0xAA), (0x06, 0x80, 0x12)] + [(0x06, 0x05, 0xAA)] * 4 + [(0x05, 0x17, 0xAA)] * 2,
                         [step[1:] for step in p.EXIT_SEQUENCE])

    def test_the_whole_sequence_ends_the_session_and_closes_the_video(self):
        bench = Bench()
        bench.authenticate()
        bench.frame(nal(0x67, 100))
        bench.goodbye()
        self.assertEqual(("complete", 9), bench.panel.last_exit)
        self.assertEqual(1, bench.panel.sessions)
        self.assertFalse(bench.panel.authenticated)
        self.assertIsNone(bench.panel.phone)
        self.assertTrue(bench.videos[0].closed)
        self.assertEqual(0, bench.log.errors)
        self.assertTrue(bench.said("session over: the phone's exit sequence came whole "
                                   "(projection-stop projection-off back idle idle idle idle boundary boundary)"))
        self.assertTrue(bench.said("1 frames written to memory.h264 (104 bytes)"))

    def test_nothing_is_answered_to_the_exit_and_the_heartbeat_stops(self):
        bench = Bench()
        bench.authenticate()
        bench.goodbye()
        bench.now += 3
        bench.panel.tick()
        self.assertEqual([], bench.answers())

    def test_the_next_session_starts_clean_and_gets_a_video_of_its_own(self):
        bench = Bench()
        bench.authenticate()
        bench.frame(nal(0x67, 100))
        bench.goodbye()
        bench.authenticate()
        bench.packetizer = rtp.Packetizer(seq=500, ssrc=0x00C0FFEE)
        bench.frame(nal(0x67, 300))
        bench.goodbye()
        self.assertEqual(2, bench.panel.sessions)
        self.assertEqual([104, 304], [video.bytes for video in bench.videos])
        self.assertEqual(0, bench.log.errors)

    def test_a_sequence_broken_after_its_back_is_an_error(self):
        bench = Bench()
        bench.authenticate()
        for _, type_, sub, value in p.EXIT_SEQUENCE[:5]:
            bench.phone(k1g.tlv(type_, sub, value))
        bench.phone(k1g.tlv(0x05, 0x56, 0x55))
        self.assertEqual(("incomplete", 5), bench.panel.last_exit)
        self.assertEqual(1, bench.log.errors)
        self.assertTrue(bench.said("exit sequence came only up to command 5 of 9 (projection-stop projection-off back idle idle)"))
        self.assertEqual(0, bench.panel.sessions)

    def test_a_sequence_that_stops_coming_ends_the_session_after_a_second(self):
        bench = Bench()
        bench.authenticate()
        for _, type_, sub, value in p.EXIT_SEQUENCE[:8]:
            bench.phone(k1g.tlv(type_, sub, value))
        bench.now += 0.9
        bench.panel.tick()
        self.assertEqual(0, bench.panel.sessions)
        bench.now += 0.1
        bench.panel.tick()
        self.assertEqual(("incomplete", 8), bench.panel.last_exit)
        self.assertEqual(1, bench.panel.sessions)
        self.assertEqual(1, bench.log.errors)

    def test_the_two_commands_the_first_burst_also_sends_are_no_exit(self):
        bench = Bench()
        bench.authenticate()
        bench.phone(k1g.tlv(0x05, 0x56, 0xAA))
        bench.phone(k1g.tlv(0x06, 0x05, 0xAA))
        bench.phone(k1g.tlv(0x05, 0x17, 0xAA))
        bench.now += 2
        bench.phone(k1g.tlv(0x05, 0x56, 0x55))
        bench.panel.tick()
        self.assertIsNone(bench.panel.last_exit)
        self.assertEqual(0, bench.log.errors)

    def test_a_sequence_may_begin_right_after_a_false_start(self):
        bench = Bench()
        bench.authenticate()
        bench.phone(k1g.tlv(0x05, 0x56, 0xAA))
        bench.goodbye()
        self.assertEqual(("complete", 9), bench.panel.last_exit)

    def test_a_phone_that_goes_quiet_ends_the_session_too(self):
        bench = Bench(silence_s=6.0)
        bench.authenticate()
        bench.now += 5.9
        bench.panel.tick()
        self.assertEqual(0, bench.panel.sessions)
        bench.now += 0.1
        bench.panel.tick()
        self.assertEqual(1, bench.panel.sessions)
        self.assertTrue(bench.said("the phone has sent no control packet for 6 s (no exit sequence came)"))

    def test_stopping_the_tool_closes_the_open_session(self):
        bench = Bench()
        bench.authenticate()
        bench.frame(nal(0x67, 100))
        bench.panel.close()
        self.assertTrue(bench.videos[0].closed)
        self.assertTrue(bench.said("session over: the bench panel was stopped"))
        bench.panel.close()
        self.assertEqual(1, bench.panel.sessions)


class SecretsTest(unittest.TestCase):
    def call_card(self, name, key=AES, iv=bytes(range(16))):
        return k1g.tlv(0x05, 0x22, iv + cripto.cbc_encrypt(key, iv, name.encode("latin-1") + b"\x00"))

    def test_the_callers_name_is_read_with_the_session_key_and_not_shown(self):
        bench = Bench()
        bench.authenticate()
        bench.phone(self.call_card("João da Silva"))
        self.assertEqual("João da Silva".encode("latin-1"), bench.panel.last_caller)
        self.assertTrue(bench.said("caller's name (05 22) read with the session key: 13 characters, not shown"))
        self.assertFalse(bench.said("Silva"))

    def test_asked_for_the_name_is_shown(self):
        bench = Bench(show_text=True)
        bench.authenticate()
        bench.phone(self.call_card("João da Silva"))
        self.assertTrue(bench.said("read with the session key: 'João da Silva'"))

    def test_a_name_under_another_key_or_with_no_key_yet_is_an_error(self):
        bench = Bench()
        bench.phone(self.call_card("Someone"))
        self.assertEqual(1, bench.log.errors)
        bench.authenticate()
        bench.phone(self.call_card("Someone", key=bytes(32)))
        bench.phone(k1g.tlv(0x05, 0x22, bytes(20)))
        self.assertEqual(3, bench.log.errors)
        self.assertIsNone(bench.panel.last_caller)

    def test_a_secure_value_goes_under_the_session_key_with_its_iv_first(self):
        bench = Bench()
        bench.panel.command("secure 01 0A0B")
        bench.authenticate()
        self.assertEqual([], bench.answers())
        bench.panel.command("secure 01 0A0B0C")
        bench.panel.command("secure 01 0A0B0C")
        one, two = bench.answers()
        self.assertEqual((0x0F, 0x01), one.key)
        self.assertEqual(32, len(one.value))
        self.assertEqual(bytes([0x0A, 0x0B, 0x0C]), cripto.cbc_decrypt(AES, one.value[:16], one.value[16:]))
        self.assertNotEqual(one.value[:16], two.value[:16])


class LoggingTest(unittest.TestCase):
    def test_a_tlv_the_app_does_not_name_is_only_logged_once_and_counted(self):
        bench = Bench()
        bench.authenticate()
        for _ in range(3):
            bench.phone(k1g.tlv(0x0E, 0x77, 1, 2))
        self.assertEqual([], bench.answers())
        self.assertEqual({(0x0E, 0x77): 3}, bench.panel.unknown)
        self.assertEqual(1, len([line for line in bench.log.lines if "TLV 0E77 (2 bytes) is not one the app's code names: only logged" in line]))
        self.assertEqual(0, bench.log.errors)
        bench.goodbye()
        self.assertTrue(bench.said("TLVs the app's code does not name, only logged: 0E77 x3"))

    def test_every_packet_in_and_out_is_in_the_log(self):
        bench = Bench()
        bench.phone_hex(p.AUTH_REQUEST)
        self.assertIn("[rx] seq=0 0804:01", bench.log.lines)
        self.assertIn("[tx] 0703:010001", bench.log.lines)

    def test_a_broken_packet_is_a_protocol_error_and_what_is_whole_in_it_is_still_handled(self):
        bench = Bench()
        whole = k1g.build(k1g.tlv(0x08, 0x04, 0x01), k1g.tlv(0x05, 0x01, b"A street\x00"))
        bench.panel.on_control(whole[:-3], PHONE)
        self.assertEqual(2, bench.log.errors)
        self.assertTrue(bench.said("ERROR: control packet of 32 bytes: the packet ends inside a TLV"))
        self.assertEqual(2, len(bench.answers()))
        bench.panel.on_control(b"\x01\x02\x03", PHONE)
        self.assertEqual(3, bench.log.errors)

    def test_a_jump_in_the_phones_sequence_is_told(self):
        bench = Bench()
        bench.phone(k1g.tlv(0x05, 0x56, 0x55))
        bench.seq = 5
        bench.phone(k1g.tlv(0x05, 0x56, 0x55))
        bench.seq = 255
        bench.phone(k1g.tlv(0x05, 0x56, 0x55))
        bench.phone(k1g.tlv(0x05, 0x56, 0x55))  # 255 to 0 is the roll, not a jump
        self.assertEqual(2, bench.panel.sequence_jumps)
        self.assertTrue(bench.said("the control sequence went from 0 to 5"))
        self.assertEqual(0, bench.log.errors)

    def test_what_changes_is_told_once(self):
        bench = Bench()
        for value in (0x55, 0x55, 0xAA, 0x55):
            bench.phone(k1g.tlv(0x05, 0x56, value))
        self.assertEqual(["[session] projection keep-alive (05 56) says on (55)", "[session] projection keep-alive (05 56) says off (AA)",
                          "[session] projection keep-alive (05 56) says on (55)"], [line for line in bench.lines("session") if "05 56" in line])
        bench.phone(k1g.tlv(0x06, 0x0B, b"REau\x00"))
        self.assertTrue(bench.said("the phone calls itself 'REau'"))

    def test_packets_from_another_address_are_answered_there_and_said(self):
        bench = Bench()
        bench.phone_hex(p.AUTH_REQUEST)
        bench.phone(k1g.tlv(0x05, 0x56, 0x55), source="192.168.1.21")
        self.assertEqual("192.168.1.21", bench.panel.phone)
        self.assertTrue(bench.said("control packets now come from 192.168.1.21, not 192.168.1.20"))


class CommandTest(unittest.TestCase):
    def test_the_bench_commands(self):
        bench = Bench()
        bench.authenticate()
        for line in ("notice key", "notice frame", "reject", "raw 0C 01 0102", "raw 0B 02", "  ", "status"):
            self.assertTrue(bench.panel.command(line), line)
        self.assertEqual([k1g.tlv(0x09, 0x06, 0x55), k1g.tlv(0x09, 0x04, 0x55), k1g.tlv(0x07, 0x01, 0x00), k1g.tlv(0x0C, 0x01, 1, 2), k1g.tlv(0x0B, 0x02)],
                         bench.answers())
        self.assertTrue(bench.said("phone at 192.168.1.20, authenticated; dash age 30 s; no video frame; protocol errors 0"))

    def test_quit_stops_and_nonsense_is_said_with_the_list_of_commands(self):
        bench = Bench()
        self.assertFalse(bench.panel.command("quit"))
        self.assertFalse(bench.panel.command("exit"))
        for line in ("press", "press sideways", "raw 0C", "raw ZZ 01", "secure 01 xyz", "notice all", "dance"):
            self.assertTrue(bench.panel.command(line), line)
            self.assertTrue(bench.said("not understood: %r" % line), line)
        self.assertTrue(bench.panel.command("help"))
        self.assertTrue(bench.said("press NAME|CODE"))
        self.assertEqual([], bench.sent)

    def test_a_silent_panel_hears_and_sends_nothing_until_told_to_answer_again(self):
        bench = Bench()
        bench.authenticate()
        bench.panel.command("silent on")
        bench.phone_hex(p.PROJECTION_FRAME)
        bench.frame(nal(0x67, 100))
        bench.panel.press(0x14)
        bench.now += 1.5
        bench.panel.tick()
        self.assertEqual([], bench.answers())
        self.assertEqual([], bench.videos)
        self.assertTrue(bench.said("0x14 not sent: the panel is silent"))
        bench.panel.command("silent off")
        bench.now += 1.0
        bench.panel.tick()
        self.assertEqual([p.HEARTBEAT], [tlv.key for tlv in bench.answers()])


if __name__ == "__main__":
    unittest.main()
