"""Tests for ponto_de_acesso.py against a made-up machine: every command the tool runs goes to [Machine], which answers as `iw`, `nmcli` and
`ip` do and keeps what was asked of it. Nothing here touches the real network, and nothing needs root.

The outputs are shaped like the real ones, with invented names. The hardware addresses are put together by [mac], so that no text in this
file has the look of one.

Run from this folder:  python3 -B -m unittest discover -p "test_*.py"
"""
import os
import re
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import ponto_de_acesso as ap  # noqa: E402
import protocolo as p  # noqa: E402
from ponto_de_acesso import Result  # noqa: E402

HARDWARE_ADDRESS = re.compile("[0-9a-f]{2}(?::[0-9a-f]{2}){5}")


def mac(last):
    """An address of the range kept for documentation, ending in [last]."""
    return ":".join("%02x" % b for b in (0x00, 0x00, 0x5E, 0x00, 0x53, last))


def iw_dev(channel=None, mhz=None, with_ap=False):
    """`iw dev` of a card with a station, connected on [channel] or not at all; [with_ap] adds the access point's interface."""
    text = "phy#0\n"
    if with_ap:
        text += "\tInterface reauap0\n\t\tifindex 7\n\t\twdev 0x3\n\t\taddr %s\n\t\ttype AP\n\t\ttxpower 22.00 dBm\n" % mac(3)
    text += "\tUnnamed/non-netdev interface\n\t\twdev 0x2\n\t\taddr %s\n\t\ttype P2P-device\n" % mac(2)
    text += "\tInterface wlan0\n\t\tifindex 3\n\t\twdev 0x1\n\t\taddr %s\n\t\ttype managed\n" % mac(1)
    if channel is not None:
        text += "\t\tssid HomeNet\n\t\tchannel %d (%d MHz), width: 80 MHz, center1: %d MHz\n" % (channel, mhz, mhz + 30)
    return text + "\t\ttxpower 22.00 dBm\n"


BOTH_AT_ONCE = """\tvalid interface combinations:
\t\t * #{ managed } <= 1, #{ P2P-client, P2P-GO } <= 1, #{ P2P-device } <= 1,
\t\t   total <= 3, #channels <= 2
\t\t * #{ managed } <= 1, #{ AP, P2P-client, P2P-GO } <= 1, #{ P2P-device } <= 1,
\t\t   total <= 3, #channels <= 1
"""
# A card that can be an access point, but only alone.
ONE_AT_A_TIME = """\tvalid interface combinations:
\t\t * #{ managed } <= 1, #{ P2P-client, P2P-GO } <= 1, #{ P2P-device } <= 1,
\t\t   total <= 3, #channels <= 2
\t\t * #{ AP } <= 1,
\t\t   total <= 1, #channels <= 1
"""
NO_COMBINATIONS = "\tinterface combinations are not supported\n"


def iw_list(combinations=BOTH_AT_ONCE):
    return """Wiphy phy0
\twiphy index: 0
\tmax # scan SSIDs: 20
\tSupported interface modes:
\t\t * IBSS
\t\t * managed
\t\t * AP
\t\t * P2P-client
\t\t * P2P-GO
\t\t * P2P-device
\tBand 1:
\t\tBitrates (non-HT):
\t\t\t* 1.0 Mbps
\t\t\t* 54.0 Mbps
\t\tFrequencies:
\t\t\t* 2412.0 MHz [1] (22.0 dBm)
\t\t\t* 2437.0 MHz [6] (22.0 dBm)
\t\t\t* 2462.0 MHz [11] (22.0 dBm)
\t\t\t* 2467.0 MHz [12] (22.0 dBm) (no IR)
\t\t\t* 2484.0 MHz [14] (disabled)
\tBand 2:
\t\tFrequencies:
\t\t\t* 5180.0 MHz [36] (22.0 dBm)
\t\t\t* 5260.0 MHz [52] (22.0 dBm) (no IR, radar detection)
\t\t\t* 5500.0 MHz [100] (22.0 dBm) (radar detection)
\t\t\t* 5745.0 MHz [149] (22.0 dBm)
\tBand 4:
\t\tFrequencies:
\t\t\t* 5955.0 MHz [1] (22.0 dBm)
\tSupported commands:
\t\t * new_interface
\t\t * start_ap
""" + combinations + "\tHT Capability overrides:\n\t\t * MCS: ff ff ff ff ff ff ff ff ff ff\n\tDevice supports TX status socket option.\n"


def ip_addr(wifi="10.0.0.20"):
    return ("1: lo    inet 127.0.0.1/8 scope host lo\\       valid_lft forever preferred_lft forever\n"
            "3: wlan0    inet %s/24 brd 10.0.0.255 scope global dynamic noprefixroute wlan0\\       valid_lft 7000sec preferred_lft 7000sec\n" % wifi)


class Machine:
    """A computer made up for the tests: it answers the tool's commands and changes as they ask."""

    def __init__(self, channel=36, mhz=5180, combinations=BOTH_AT_ONCE, wifi_address="10.0.0.20", root=True, ufw=None, vpn=False,
                 network_manager="running", missing=(), failing=(), sees_interface=True,
                 ap_states=("disconnected",)):
        self.channel, self.mhz, self.combinations, self.wifi_address = channel, mhz, combinations, wifi_address
        self.root, self.ufw, self.vpn, self.network_manager = root, ufw, vpn, network_manager
        self.missing, self.failing, self.sees_interface = missing, failing, sees_interface
        # The states NetworkManager gives the new interface at each look, the last one kept from then on.
        self.ap_states = list(ap_states)
        self.interface = self.connection = self.active = False
        self.calls, self.said, self.slept = [], [], []

    def run(self, argv):
        self.calls.append(list(argv))
        if argv[0] in self.missing:
            return Result(ap.NOT_FOUND, "", "not installed")
        if any(argv[:len(start)] == list(start) for start in self.failing):
            return Result(1, "", "it was refused")
        if argv == ["iw", "dev"]:
            return Result(0, iw_dev(self.channel, self.mhz, with_ap=self.interface))
        if argv == ["iw", "list"]:
            return Result(0, iw_list(self.combinations))
        if argv[:2] == ["ip", "-4"]:
            return Result(0, ip_addr(self.wifi_address))
        if argv[:2] == ["nmcli", "-t"]:
            return Result(0, self._nmcli_read(argv))
        if argv[:5] == ["iw", "dev", "wlan0", "interface", "add"]:
            self.interface = True
        elif argv == ["iw", "dev", "reauap0", "del"]:
            if not self.interface:
                return Result(237, "", "command failed: No such device (-19)")
            self.interface = False
        elif argv[:3] == ["nmcli", "connection", "add"]:
            self.connection = True
        elif argv[:3] == ["nmcli", "connection", "up"]:
            self.active = True
        elif argv[:3] == ["nmcli", "connection", "down"]:
            self.active = False
        elif argv[:3] == ["nmcli", "connection", "delete"]:
            if not self.connection:
                return Result(10, "", "Error: unknown connection")
            self.connection = self.active = False
        return Result(0)

    def _nmcli_read(self, argv):
        if "RUNNING" in argv:
            return self.network_manager + "\n"
        if argv[4:6] == ["connection", "show"]:
            there = self.active if "--active" in argv else self.connection
            return "HomeNet\n" + ("reau-bench-ap\n" if there else "")
        if "DEVICE,STATE" in argv:
            if not (self.interface and self.sees_interface):
                return "wlan0:connected\nlo:connected (externally)\n"
            state = self.ap_states.pop(0) if len(self.ap_states) > 1 else self.ap_states[0]
            return "wlan0:connected\nlo:connected (externally)\nreauap0:%s\n" % state
        if "DEVICE" in argv:
            return "wlan0\nlo\n" + ("reauap0\n" if self.interface and self.sees_interface else "")
        if "DEVICE,TYPE,STATE" in argv:
            return "wlan0:wifi:connected\nlo:loopback:connected (externally)\n" + ("vpn0:wireguard:connected (externally)\n" if self.vpn else "")
        raise AssertionError("a read the tests do not know: %s" % argv)

    def tool(self, dry_run=False):
        return ap.AccessPoint(run=self.run, say=self.said.append, euid=lambda: 0 if self.root else 1000,
                              read_file=lambda path: self.ufw if path == ap.UFW_CONF else None,
                              random_bytes=lambda n: bytes(range(0x10, 0x10 + n)), sleep=self.slept.append, dry_run=dry_run)

    @property
    def changes(self):
        """The commands run that change something: all but the ones that only read."""
        return [c for c in self.calls if c not in (["iw", "dev"], ["iw", "list"]) and c[:2] not in (["ip", "-4"], ["nmcli", "-t"])]

    @property
    def text(self):
        return "\n".join(self.said)


MAC_OF_THE_TESTS = ":".join("%02x" % b for b in (0x12, 0x11, 0x12, 0x13, 0x14, 0x15))


def up_commands(band="a", channel="36", ssid="RE_BENCH_0001", password="12345678"):
    return [
        ["iw", "dev", "wlan0", "interface", "add", "reauap0", "type", "__ap", "addr", MAC_OF_THE_TESTS],
        ["nmcli", "device", "set", "reauap0", "managed", "yes"],
        ["nmcli", "connection", "add", "type", "wifi", "ifname", "reauap0", "con-name", "reau-bench-ap", "autoconnect", "no", "ssid", ssid,
         "802-11-wireless.mode", "ap", "802-11-wireless.band", band, "802-11-wireless.channel", channel,
         "802-11-wireless.cloned-mac-address", "preserve",
         "802-11-wireless-security.key-mgmt", "wpa-psk", "802-11-wireless-security.proto", "rsn",
         "802-11-wireless-security.pairwise", "ccmp", "802-11-wireless-security.group", "ccmp",
         "802-11-wireless-security.psk", password,
         "ipv4.method", "shared", "ipv4.addresses", "192.168.1.1/24", "ipv6.method", "disabled"],
        ["nmcli", "connection", "up", "reau-bench-ap", "ifname", "reauap0"],
    ]


DOWN_COMMANDS = [["nmcli", "connection", "down", "reau-bench-ap"], ["nmcli", "connection", "delete", "reau-bench-ap"], ["iw", "dev", "reauap0", "del"]]


class ParsingTest(unittest.TestCase):
    def test_iw_dev_gives_each_interface_with_its_card_kind_and_channel(self):
        ap_, p2p, station = ap.parse_iw_dev(iw_dev(149, 5745, with_ap=True))
        self.assertEqual(("reauap0", "phy0", "AP", None), (ap_.name, ap_.phy, ap_.kind, ap_.channel))
        self.assertEqual((None, "P2P-device"), (p2p.name, p2p.kind))
        self.assertEqual(("wlan0", "phy0", "managed", 149, 5745), (station.name, station.phy, station.kind, station.channel, station.mhz))

    def test_a_station_that_is_not_connected_has_no_channel(self):
        self.assertIsNone(ap.parse_iw_dev(iw_dev())[-1].channel)

    def test_an_older_iw_writes_the_frequency_with_no_decimals(self):
        (station,) = ap.parse_iw_dev("phy#1\n\tInterface wlan1\n\t\ttype managed\n\t\tchannel 6 (2437 MHz), width: 20 MHz\n")
        self.assertEqual(("phy1", 6, 2437), (station.phy, station.channel, station.mhz))
        self.assertEqual((1, ""), ap.parse_iw_list("Wiphy phy1\n\tBand 1:\n\t\tFrequencies:\n\t\t\t* 2412 MHz [1]\n")["phy1"].frequencies[2412])

    def test_the_combinations_are_read_across_their_wrapped_lines(self):
        first, second = ap.parse_iw_list(iw_list())["phy0"].combinations
        self.assertEqual(([({"managed"}, 1), ({"P2P-client", "P2P-GO"}, 1), ({"P2P-device"}, 1)], 3, 2), (first.groups, first.total, first.channels))
        self.assertEqual(([({"managed"}, 1), ({"AP", "P2P-client", "P2P-GO"}, 1), ({"P2P-device"}, 1)], 3, 1), (second.groups, second.total, second.channels))
        self.assertEqual([False, True], [first.station_and_ap(), second.station_and_ap()])

    def test_a_station_and_an_access_point_in_one_group_need_room_for_two(self):
        def fits(text):
            return [c.station_and_ap() for c in ap.parse_iw_list("Wiphy phy0\n\tvalid interface combinations:\n" + text)["phy0"].combinations]
        self.assertEqual([False], fits("\t\t * #{ managed, AP } <= 1,\n\t\t   total <= 1, #channels <= 1\n"))
        self.assertEqual([True], fits("\t\t * #{ managed, AP } <= 2,\n\t\t   total <= 2, #channels <= 1\n"))
        # One of the two at a time, though the card has room for another interface of another kind.
        self.assertEqual([False], fits("\t\t * #{ managed, AP } <= 1, #{ P2P-device } <= 1,\n\t\t   total <= 2, #channels <= 1\n"))
        self.assertEqual([False], fits("\t\t * #{ managed } <= 1, #{ AP } <= 1,\n\t\t   total <= 1, #channels <= 1\n"))
        self.assertEqual([False], fits("\t\t * #{ managed } <= 2, #{ AP/VLAN } <= 1,\n\t\t   total <= 3, #channels <= 1\n"))

    def test_the_frequencies_keep_their_channel_and_their_flags(self):
        frequencies = ap.parse_iw_list(iw_list())["phy0"].frequencies
        self.assertEqual((36, "(22.0 dBm)"), frequencies[5180])
        self.assertEqual((52, "(22.0 dBm) (no IR, radar detection)"), frequencies[5260])
        self.assertEqual((14, "(disabled)"), frequencies[2484])
        self.assertNotIn(1, frequencies)   # the bitrates are not frequencies
        self.assertEqual(10, len(frequencies))

    def test_what_stops_an_access_point_on_a_channel(self):
        self.assertIsNone(ap.channel_trouble("(22.0 dBm)"))
        self.assertIn("no IR", ap.channel_trouble("(22.0 dBm) (no IR)"))
        self.assertIn("radar", ap.channel_trouble("(22.0 dBm) (radar detection)"))
        self.assertIn("disabled", ap.channel_trouble("(disabled)"))

    def test_the_band_of_a_frequency(self):
        self.assertEqual(["bg", "bg", "a", "a", None, None], [ap.band_of(mhz) for mhz in (2412, 2484, 5180, 5825, 5955, 900)])

    def test_the_addresses_of_ip(self):
        self.assertEqual([("lo", "127.0.0.1"), ("wlan0", "192.168.1.7")], ap.parse_addresses(ip_addr("192.168.1.7")))

    def test_the_made_up_address_is_locally_administered_never_a_group_one_and_random(self):
        self.assertEqual("02" + ":00" * 5, ap.local_mac(lambda n: bytes(n)))
        self.assertEqual("fe" + ":ff" * 5, ap.local_mac(lambda n: b"\xff" * n))
        made = {ap.local_mac() for _ in range(50)}
        self.assertEqual(50, len(made))
        for address in made:
            self.assertTrue(HARDWARE_ADDRESS.fullmatch(address), address)
            self.assertEqual(0x02, int(address[:2], 16) & 0x03, address)


class CheckTest(unittest.TestCase):
    def test_a_card_that_can_do_both_on_a_free_channel_can_go_up(self):
        machine = Machine()
        self.assertEqual(ap.OK, machine.tool().check())
        self.assertIn("ok   the Wi-Fi interface wlan0 (phy0) is connected on channel 36 (5180 MHz)", machine.said)
        self.assertIn("#channels <= 1): the access point has to be on the station's channel", machine.text)
        self.assertIn("ok   channel 36 (5180 MHz, the 5 GHz band) lets an access point start", machine.text)
        self.assertEqual("the access point can go up here", machine.said[-1])
        self.assertNotIn("NO  ", machine.text)
        self.assertEqual([], machine.changes)

    def test_without_root_it_still_reads_and_says_that_up_wants_sudo(self):
        machine = Machine(root=False)
        self.assertEqual(ap.OK, machine.tool().check())
        self.assertEqual("the access point can go up here: run `up` with sudo (it needs root)", machine.said[-1])

    def test_a_card_that_cannot_do_both_is_said_with_the_cable_and_the_usb_adapter(self):
        for combinations in (ONE_AT_A_TIME, NO_COMBINATIONS):
            machine = Machine(combinations=combinations)
            self.assertEqual(ap.CANNOT, machine.tool().check())
            (no,) = [line for line in machine.said if line.startswith("NO  ")]
            self.assertIn("cannot be a station and an access point at once", no)
            self.assertIn("network cable", no)
            self.assertIn("USB Wi-Fi adapter", no)
            self.assertEqual("the access point cannot go up here", machine.said[-1])

    def test_a_channel_marked_no_ir_or_radar_is_refused(self):
        for channel, mhz, why in ((52, 5260, "is marked `no IR`"), (100, 5500, "needs radar detection (DFS)"), (12, 2467, "is marked `no IR`")):
            machine = Machine(channel=channel, mhz=mhz)
            self.assertEqual(ap.CANNOT, machine.tool().check())
            (no,) = [line for line in machine.said if line.startswith("NO  ")]
            self.assertIn("channel %d (%d MHz) %s" % (channel, mhz, why), no)
            self.assertIn("so an access point cannot start on it", no)

    def test_a_channel_the_card_does_not_list_or_of_another_band_is_refused(self):
        machine = Machine(channel=44, mhz=5220)
        self.assertEqual(ap.CANNOT, machine.tool().check())
        self.assertIn("channel 44 (5220 MHz) is not among the card's frequencies", machine.text)
        machine = Machine(channel=1, mhz=5955)
        self.assertEqual(ap.CANNOT, machine.tool().check())
        self.assertIn("5955 MHz is in neither the 2.4 GHz nor the 5 GHz band", machine.text)

    def test_a_network_already_in_the_dashs_range_is_refused(self):
        machine = Machine(wifi_address="192.168.1.57")
        self.assertEqual(ap.CANNOT, machine.tool().check())
        (no,) = [line for line in machine.said if line.startswith("NO  ")]
        self.assertIn("wlan0 already has the address 192.168.1.57, in the dash's own network (192.168.1.0/24)", no)
        # The network next to it is another one.
        self.assertEqual(ap.OK, Machine(wifi_address="192.168.2.57").tool().check())
        self.assertEqual(ap.OK, Machine(wifi_address="192.168.10.1").tool().check())

    def test_a_wifi_that_is_not_connected_gets_a_channel_of_its_own(self):
        machine = Machine(channel=None)
        self.assertEqual(ap.OK, machine.tool().check())
        self.assertIn("is not connected to a network: the access point takes channel 6 (2437 MHz)", machine.text)
        machine.tool().up()
        self.assertEqual(up_commands("bg", "6"), machine.changes)
        self.assertNotIn("If the computer's Wi-Fi moves to another channel", machine.text)

    def test_what_is_missing_is_said(self):
        for missing, running, text in ((("iw",), "running", "`iw` is not installed"), (("nmcli",), "running", "`nmcli` is not installed"),
                                       ((), "not running", "NetworkManager is not running")):
            machine = Machine(missing=missing, network_manager=running)
            self.assertEqual(ap.CANNOT, machine.tool().check())
            self.assertIn(text, machine.text)

    def test_a_machine_with_no_wifi_is_sent_to_a_usb_adapter(self):
        machine = Machine()
        machine.run = lambda argv: Result(0, "running\n" if "RUNNING" in argv else "")
        self.assertEqual(ap.CANNOT, machine.tool().check())
        self.assertIn("no Wi-Fi interface found. Plug in a USB Wi-Fi adapter", machine.text)

    def test_an_access_point_already_up_is_sent_to_down(self):
        machine = Machine()
        machine.tool().up()
        del machine.said[:]
        self.assertEqual(ap.CANNOT, machine.tool().check())
        self.assertIn("is already here (the connection reau-bench-ap, the interface reauap0). Run `down` first", machine.text)


class UpTest(unittest.TestCase):
    def test_on_5_ghz_it_runs_these_commands_in_this_order(self):
        machine = Machine(channel=149, mhz=5745)
        self.assertEqual(ap.OK, machine.tool().up())
        self.assertEqual(up_commands("a", "149"), machine.changes)
        self.assertEqual((True, True, True), (machine.interface, machine.connection, machine.active))
        self.assertIn("the access point RE_BENCH_0001 is up on reauap0: this computer is 192.168.1.1, channel 149", machine.said)

    def test_on_2_4_ghz_the_band_is_bg_and_the_channel_the_stations(self):
        machine = Machine(channel=11, mhz=2462)
        self.assertEqual(ap.OK, machine.tool().up())
        self.assertEqual(up_commands("bg", "11"), machine.changes)

    def test_the_name_and_the_passphrase_asked_for_are_the_ones_set_and_the_passphrase_is_not_shown(self):
        machine = Machine()
        self.assertEqual(ap.OK, machine.tool().up("RE_OTHER_0002", "another passphrase"))
        self.assertEqual(up_commands(ssid="RE_OTHER_0002", password="another passphrase"), machine.changes)
        self.assertNotIn("another passphrase", machine.text)
        self.assertIn("802-11-wireless-security.psk '<password>'", machine.text)
        self.assertIn("--ssid RE_OTHER_0002", machine.text)

    def test_the_access_point_is_the_dashs_wpa2_with_its_address_dhcp_no_ipv6_and_no_autoconnect(self):
        machine = Machine()
        machine.tool().up()
        add = machine.changes[2]
        settings = dict(zip(add[3::2], add[4::2]))
        self.assertEqual(p.SSID_PREFIX + "BENCH_0001", settings["ssid"])
        self.assertEqual(p.DEFAULT_PASSWORD, settings["802-11-wireless-security.psk"])
        self.assertEqual(("wpa-psk", "rsn"), (settings["802-11-wireless-security.key-mgmt"], settings["802-11-wireless-security.proto"]))
        self.assertEqual(("shared", p.DASH_IP + "/24"), (settings["ipv4.method"], settings["ipv4.addresses"]))
        self.assertEqual(("disabled", "no", "ap", "reauap0"), (settings["ipv6.method"], settings["autoconnect"], settings["802-11-wireless.mode"], settings["ifname"]))

    def test_without_root_it_says_sudo_and_changes_nothing(self):
        machine = Machine(root=False)
        self.assertEqual(ap.CANNOT, machine.tool().up())
        self.assertEqual([], machine.changes)
        self.assertIn("`up` needs root and this tool does not call sudo itself. Run it again with sudo: sudo python3 ponto_de_acesso.py up", machine.said)
        self.assertFalse(any(call[0] == "sudo" for call in machine.calls))

    def test_what_check_refuses_up_refuses_with_nothing_changed(self):
        for machine in (Machine(combinations=ONE_AT_A_TIME), Machine(channel=52, mhz=5260), Machine(wifi_address="192.168.1.57")):
            self.assertEqual(ap.CANNOT, machine.tool().up())
            self.assertEqual([], machine.changes)
            self.assertEqual("the access point cannot go up here; nothing was changed", machine.said[-1])

    def test_a_second_up_is_refused_and_leaves_the_first_alone(self):
        machine = Machine()
        machine.tool().up()
        before = list(machine.changes)
        self.assertEqual(ap.CANNOT, machine.tool().up())
        self.assertEqual(before, machine.changes)
        self.assertTrue(machine.active)

    def test_a_name_the_app_would_not_look_for_and_a_passphrase_wpa2_would_not_take_are_refused(self):
        for ssid, password in (("BENCH_0001", "12345678"), ("RE_" + "x" * 30, "12345678"), ("RE_BENCH_0001", "1234567"), ("RE_BENCH_0001", "x" * 64)):
            machine = Machine()
            self.assertEqual(ap.CANNOT, machine.tool().up(ssid, password))
            self.assertEqual([], machine.calls)

    def test_a_step_that_fails_undoes_the_ones_before_it(self):
        machine = Machine(failing=[("nmcli", "connection", "up")])
        self.assertEqual(ap.FAILED, machine.tool().up())
        self.assertEqual(up_commands() + DOWN_COMMANDS[1:], machine.changes)
        self.assertEqual((False, False, False), (machine.interface, machine.connection, machine.active))
        self.assertIn("  FAILED (exit code 1): it was refused", machine.said)
        self.assertIn("`up` stopped half-way: undoing what it did", machine.said)

    def test_an_interface_that_could_not_be_made_leaves_nothing_to_undo(self):
        machine = Machine(failing=[("iw", "dev", "wlan0", "interface", "add")])
        self.assertEqual(ap.FAILED, machine.tool().up())
        self.assertEqual(up_commands()[:1], machine.changes)

    def test_an_interface_network_manager_never_sees_is_taken_away_again(self):
        machine = Machine(sees_interface=False)
        self.assertEqual(ap.FAILED, machine.tool().up())
        self.assertEqual(up_commands()[:1] + DOWN_COMMANDS[2:], machine.changes)
        self.assertEqual([ap.SEEN_WAIT_S] * ap.SEEN_TRIES, machine.slept)
        self.assertFalse(machine.interface)

    def test_it_waits_for_network_manager_to_have_the_interface_ready_before_the_connection(self):
        machine = Machine(ap_states=("unavailable", "unavailable", "disconnected"))
        self.assertEqual(ap.OK, machine.tool().up())
        self.assertEqual(up_commands(), machine.changes)
        self.assertEqual([ap.READY_WAIT_S] * 2, machine.slept)

    def test_an_interface_network_manager_never_has_ready_is_taken_away_and_the_state_is_said(self):
        machine = Machine(ap_states=("unavailable",))
        self.assertEqual(ap.FAILED, machine.tool().up())
        self.assertEqual(up_commands()[:2] + DOWN_COMMANDS[2:], machine.changes)
        self.assertEqual([ap.READY_WAIT_S] * ap.READY_TRIES, machine.slept)
        self.assertIn("  NetworkManager left reauap0 `unavailable` for 10 s; it has to be `disconnected` to take the access point", machine.said)
        self.assertFalse(machine.interface)

    def test_it_says_what_to_do_next(self):
        machine = Machine(ufw="# ufw.conf\nENABLED=yes\nLOGLEVEL=low\n", vpn=True)
        machine.tool().up()
        self.assertIn('1. A VPN seems to be on (vpn0): turn on its "Local network sharing"', machine.text)
        self.assertIn("     sudo ufw allow in on reauap0 to any port 2000,5000 proto udp", machine.said)
        self.assertIn("     python3 %s --ssid RE_BENCH_0001 --ver" % os.path.relpath(os.path.join(HERE, "painel_falso.py")), machine.said)
        self.assertIn("The access point stays on channel 36. If the computer's Wi-Fi moves to another channel, run `down` and `up` again.", machine.said)
        self.assertEqual("When done: sudo python3 ponto_de_acesso.py down", machine.said[-1])

    def test_with_no_vpn_and_no_firewall_seen_it_still_names_both(self):
        for ufw in (None, "ENABLED=no\n", "# ENABLED=yes\n"):
            machine = Machine(ufw=ufw)
            machine.tool().up()
            self.assertIn('1. If a VPN is on, turn on its "Local network sharing"', machine.text)
            self.assertIn("2. ufw is not on. If another firewall is, let UDP 2000 and 5000 in on reauap0.", machine.said)
            self.assertNotIn("ufw allow", machine.text)


class DownTest(unittest.TestCase):
    def test_it_undoes_up_in_the_reverse_order(self):
        machine = Machine()
        machine.tool().up()
        del machine.calls[:]
        self.assertEqual(ap.OK, machine.tool().down())
        self.assertEqual(DOWN_COMMANDS, machine.changes)
        self.assertEqual((False, False, False), (machine.interface, machine.connection, machine.active))
        self.assertEqual("the access point is gone", machine.said[-1])

    def test_a_second_down_finds_nothing_and_ends_well(self):
        machine = Machine()
        machine.tool().up()
        self.assertEqual(ap.OK, machine.tool().down())
        del machine.calls[:]
        self.assertEqual(ap.OK, machine.tool().down())
        self.assertEqual([], machine.changes)
        self.assertEqual("nothing to undo: neither the connection reau-bench-ap nor the interface reauap0 is here", machine.said[-1])

    def test_with_nothing_to_undo_it_ends_well_even_without_root(self):
        machine = Machine(root=False)
        self.assertEqual(ap.OK, machine.tool().down())
        self.assertEqual([], machine.changes)

    def test_after_an_up_that_stopped_half_way_it_undoes_only_what_is_there(self):
        for interface, connection, active, expected in ((True, False, False, DOWN_COMMANDS[2:]), (True, True, False, DOWN_COMMANDS[1:]),
                                                        (False, True, False, DOWN_COMMANDS[1:2]), (True, True, True, DOWN_COMMANDS)):
            machine = Machine()
            machine.interface, machine.connection, machine.active = interface, connection, active
            self.assertEqual(ap.OK, machine.tool().down())
            self.assertEqual(expected, machine.changes)
            self.assertEqual((False, False, False), (machine.interface, machine.connection, machine.active))

    def test_a_connection_that_would_not_go_down_is_deleted_all_the_same(self):
        machine = Machine(failing=[("nmcli", "connection", "down")])
        machine.interface = machine.connection = machine.active = True
        self.assertEqual(ap.OK, machine.tool().down())
        self.assertEqual(DOWN_COMMANDS, machine.changes)
        self.assertIn("  did not work (exit code 1): it was refused", machine.said)

    def test_what_could_not_be_taken_away_is_said_and_the_rest_still_goes(self):
        machine = Machine(failing=[("nmcli", "connection", "delete")])
        machine.interface = machine.connection = True
        self.assertEqual(ap.FAILED, machine.tool().down())
        self.assertEqual(DOWN_COMMANDS[1:], machine.changes)
        self.assertFalse(machine.interface)
        self.assertEqual("something of the access point may be left: see the messages above", machine.said[-1])

    def test_without_root_and_with_something_to_undo_it_says_sudo_and_changes_nothing(self):
        machine = Machine(root=False)
        machine.interface = machine.connection = True
        self.assertEqual(ap.CANNOT, machine.tool().down())
        self.assertEqual([], machine.changes)
        self.assertIn("sudo python3 ponto_de_acesso.py down", machine.said[-1])
        self.assertTrue(machine.interface and machine.connection)


class DryRunTest(unittest.TestCase):
    def test_up_writes_its_commands_and_runs_none_even_without_root(self):
        machine = Machine(root=False)
        self.assertEqual(ap.OK, machine.tool(dry_run=True).up())
        self.assertEqual([], machine.changes)
        self.assertEqual((False, False, False), (machine.interface, machine.connection, machine.active))
        self.assertEqual([], machine.slept)
        written = [line[len("would run: "):].split() for line in machine.said if line.startswith("would run: ")]
        self.assertEqual(up_commands(), written)
        self.assertIn("--dry-run: nothing is changed; these are the commands `up` would run", machine.said)
        self.assertIn("and then it would say:", machine.said)
        self.assertFalse(any(line.startswith("+ ") for line in machine.said))

    def test_up_still_refuses_what_cannot_go_up(self):
        machine = Machine(combinations=ONE_AT_A_TIME)
        self.assertEqual(ap.CANNOT, machine.tool(dry_run=True).up())
        self.assertFalse(any(line.startswith("would run") for line in machine.said))

    def test_down_writes_its_commands_and_runs_none(self):
        machine = Machine(root=False)
        machine.interface = machine.connection = machine.active = True
        self.assertEqual(ap.OK, machine.tool(dry_run=True).down())
        self.assertEqual([], machine.changes)
        self.assertEqual(["would run: " + " ".join(command) for command in DOWN_COMMANDS], machine.said)
        self.assertTrue(machine.interface and machine.connection and machine.active)


class CommandLineTest(unittest.TestCase):
    def run_main(self, machine, *argv):
        return ap.main(list(argv), run=machine.run, say=machine.said.append, euid=lambda: 0 if machine.root else 1000,
                       read_file=lambda path: None, random_bytes=lambda n: bytes(range(0x10, 0x10 + n)), sleep=machine.slept.append)

    def test_the_three_commands(self):
        machine = Machine()
        self.assertEqual(ap.OK, self.run_main(machine, "check"))
        self.assertEqual([], machine.changes)
        self.assertEqual(ap.OK, self.run_main(machine, "up", "--ssid", "RE_OTHER_0002", "--password", "another passphrase"))
        self.assertEqual(up_commands(ssid="RE_OTHER_0002", password="another passphrase"), machine.changes)
        del machine.calls[:]
        self.assertEqual(ap.OK, self.run_main(machine, "down"))
        self.assertEqual(DOWN_COMMANDS, machine.changes)

    def test_dry_run_from_the_command_line_changes_nothing(self):
        machine = Machine()
        self.assertEqual(ap.OK, self.run_main(machine, "up", "--dry-run"))
        self.assertEqual(ap.OK, self.run_main(machine, "down", "--dry-run"))
        self.assertEqual([], machine.changes)

    def test_the_defaults_are_the_apps(self):
        args = ap.arguments(["up"])
        self.assertEqual((p.SSID_PREFIX + "BENCH_0001", p.DEFAULT_PASSWORD, False), (args.ssid, args.password, args.dry_run))
        self.assertEqual("192.168.1.1/24", ap.AP_ADDRESS)

    def test_a_program_that_is_not_installed_gives_127_and_does_not_raise(self):
        done = ap.run_command(["a-program-that-is-not-installed-anywhere"])
        self.assertEqual(ap.NOT_FOUND, done.code)

    def test_no_text_of_the_tool_or_of_these_tests_looks_like_a_hardware_address(self):
        for name in ("ponto_de_acesso.py", "test_ponto_de_acesso.py"):
            with open(os.path.join(HERE, name), encoding="utf-8") as f:
                self.assertIsNone(re.search("[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}", f.read()), name)


if __name__ == "__main__":
    unittest.main()
