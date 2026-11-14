#!/usr/bin/env python3
"""The bench panel's access point: makes this computer the Wi-Fi network REau's app looks for, without taking the computer off its own Wi-Fi.

A Wi-Fi card that can be a station and an access point at once usually can do it on one channel only, so the access point goes up on the
channel the computer's own network is on, whatever band that is. The tool adds a second, virtual interface of the access-point kind on the
same card, hands it to NetworkManager with the dash's address and a DHCP server (NetworkManager's "shared" method), and takes it all away
again. It needs Linux with `iw`, `ip` and NetworkManager's `nmcli`.

Usage:
  python3 ponto_de_acesso.py check                 only reads: says whether the access point can go up here, and why
  sudo python3 ponto_de_acesso.py up [--ssid RE_BENCH_0001] [--password 12345678]
  sudo python3 ponto_de_acesso.py down             undoes `up`, in the reverse order, also after an `up` that stopped half-way
  python3 ponto_de_acesso.py up --dry-run          reads, and writes the commands it would run instead of running them (so does `down --dry-run`)

It never calls `sudo` itself: without root it says so and changes nothing. Every command goes through one function (`run`), which the tests
replace with made-up outputs of `iw`, `nmcli` and `ip`.
"""
import argparse
import ipaddress
import os
import re
import secrets
import shlex
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import protocolo as p  # noqa: E402

AP_INTERFACE = "reauap0"
CONNECTION = "reau-bench-ap"
DEFAULT_SSID = p.SSID_PREFIX + "BENCH_0001"
AP_ADDRESS = p.DASH_IP + "/24"
DASH_NETWORK = ipaddress.ip_network(AP_ADDRESS, strict=False)
# With no Wi-Fi network to share a channel with, the access point takes this one (2.4 GHz, channel 6).
FREE_CHANNEL, FREE_MHZ = 6, 2437
UFW_CONF = "/etc/ufw/ufw.conf"
VPN_KINDS = ("wireguard", "tun", "vpn")
# How long NetworkManager is given to see the new interface.
SEEN_TRIES, SEEN_WAIT_S = 20, 0.25
NOT_FOUND = 127

OK, CANNOT, FAILED = 0, 1, 2


class Result:
    """What a command gave: its exit code, its standard output and its standard error."""

    def __init__(self, code=0, out="", err=""):
        self.code = code
        self.out = out
        self.err = err


def run_command(argv):
    """Runs [argv] and gives its [Result]; a program that is not installed gives the code 127. The messages come in English, to be read here."""
    try:
        done = subprocess.run(argv, capture_output=True, text=True, errors="replace", env=dict(os.environ, LC_ALL="C"), check=False)
    except OSError as e:
        return Result(NOT_FOUND, "", str(e))
    return Result(done.returncode, done.stdout, done.stderr)


def read_text(path):
    """The text of a file, or None when it cannot be read."""
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return None


# ── reading what `iw`, `ip` and `nmcli` say ──────────────────────────────────────────────────────────────────────────

class Interface:
    """One interface of `iw dev`: its name (None for one with no network device), its card, its kind, and its channel when it has one."""

    def __init__(self, phy):
        self.phy = phy
        self.name = None
        self.kind = None
        self.channel = None
        self.mhz = None


def parse_iw_dev(text):
    """The interfaces `iw dev` lists, in its order."""
    found, phy, current = [], None, None
    for line in text.splitlines():
        words = line.split()
        if not words:
            continue
        if words[0].startswith("phy#"):
            phy, current = "phy" + words[0][4:], None
        elif words[0] == "Interface" and len(words) > 1:
            current = Interface(phy)
            current.name = words[1]
            found.append(current)
        elif words[0].startswith("Unnamed"):
            current = Interface(phy)
            found.append(current)
        elif current is not None and words[0] == "type" and len(words) > 1:
            current.kind = words[1]
        elif current is not None and words[0] == "channel":
            at = re.match(r"\s*channel\s+(\d+)\s+\((\d+)(?:\.\d+)?\s*MHz\)", line)
            if at:
                current.channel, current.mhz = int(at.group(1)), int(at.group(2))
    return found


class Combination:
    """One line of a card's "valid interface combinations": the groups of kinds with how many of each, the total and the channels."""

    def __init__(self, groups, total, channels):
        self.groups = groups      # [(set of kinds, how many at most)]
        self.total = total
        self.channels = channels

    def station_and_ap(self):
        """True when it lets a station (`managed`) and an access point (`AP`) be up together."""
        if self.total < 2:
            return False
        for i, (kinds, most) in enumerate(self.groups):
            if "managed" not in kinds:
                continue
            for j, (others, _) in enumerate(self.groups):
                if "AP" in others and (i != j or most >= 2):
                    return True
        return False


class Phy:
    """One Wi-Fi card of `iw list`: its combinations and what it says of each frequency (MHz -> (channel, the flags in brackets))."""

    def __init__(self, name):
        self.name = name
        self.combinations = []
        self.frequencies = {}


def _indent(line):
    return len(line) - len(line.lstrip())


def parse_iw_list(text):
    """The cards `iw list` (or `iw phy`) describes, by name."""
    phys, phy = {}, None
    combining, combination_text = None, []

    def close_combinations():
        for item in " ".join(combination_text).split("*"):
            groups = [({kind.strip() for kind in kinds.split(",")}, int(most)) for kinds, most in re.findall(r"#\{([^}]*)\}\s*<=\s*(\d+)", item)]
            total = re.search(r"total\s*<=\s*(\d+)", item)
            channels = re.search(r"#channels\s*<=\s*(\d+)", item)
            if groups and total:
                phy.combinations.append(Combination(groups, int(total.group(1)), int(channels.group(1)) if channels else 1))
        del combination_text[:]

    for line in text.splitlines():
        if not line.strip():
            continue
        if combining is not None:
            if _indent(line) > combining:
                combination_text.append(line.strip())
                continue
            close_combinations()
            combining = None
        if line.startswith("Wiphy "):
            phy = phys.setdefault(line.split()[1], Phy(line.split()[1]))
        elif phy is None:
            continue
        elif line.strip() == "valid interface combinations:":
            combining = _indent(line)
        else:
            at = re.match(r"\s*\*\s*(\d+)(?:\.\d+)?\s*MHz\s*\[(\d+)\](.*)$", line)
            if at:
                phy.frequencies[int(at.group(1))] = (int(at.group(2)), at.group(3).strip())
    if combining is not None:
        close_combinations()
    return phys


def parse_addresses(text):
    """(interface, address) of each IPv4 address in `ip -4 -o addr show`."""
    found = []
    for line in text.splitlines():
        at = re.match(r"\s*\d+:\s+(\S+)\s+inet\s+(\d+\.\d+\.\d+\.\d+)", line)
        if at:
            found.append((at.group(1), at.group(2)))
    return found


def band_of(mhz):
    """NetworkManager's name of the band a frequency is in ("bg" for 2.4 GHz, "a" for 5 GHz), None for any other."""
    if 2400 <= mhz <= 2500:
        return "bg"
    if 5000 <= mhz <= 5900:
        return "a"
    return None


def channel_trouble(flags):
    """Why an access point cannot start on a channel `iw` marks with [flags]; None when it can."""
    low = flags.lower()
    if "disabled" in low:
        return "is disabled here"
    if "no ir" in low:
        return "is marked `no IR` (nothing may start transmitting on it)"
    if "radar detection" in low:
        return "needs radar detection (DFS)"
    return None


def local_mac(random_bytes=secrets.token_bytes):
    """A random hardware address of the locally administered kind (and not a group one), as some drivers refuse a second interface with the card's own."""
    raw = bytearray(random_bytes(6))
    raw[0] = (raw[0] & 0xFC) | 0x02
    return ":".join("%02x" % b for b in raw)


class Plan:
    """What `up` needs to know: the interface the access point hangs on, and its channel and band."""

    def __init__(self):
        self.parent = None
        self.channel = None
        self.mhz = None
        self.band = None
        self.shared_channel = False


# ── the tool ─────────────────────────────────────────────────────────────────────────────────────────────────────────

class AccessPoint:
    """[run] takes a command as a list and gives a [Result]; [say] writes a line; [euid] gives the user's number (0 is root)."""

    def __init__(self, run=run_command, say=print, euid=os.geteuid, read_file=read_text, random_bytes=secrets.token_bytes, sleep=time.sleep,
                 dry_run=False, default_password=p.DEFAULT_PASSWORD):
        self.run = run
        self.say = say
        self.euid = euid
        self.read_file = read_file
        self.random_bytes = random_bytes
        self.sleep = sleep
        self.dry_run = dry_run
        self.default_password = default_password
        self._hidden = ()

    # ── reading ──────────────────────────────────────────────────────────────────────────────────────────────────

    def _read(self, *argv):
        return self.run(list(argv))

    def _leftovers(self, interfaces=None):
        """(the connection is active, the connection exists, the interface exists): what an earlier `up` left."""
        active = CONNECTION in self._read("nmcli", "-t", "-f", "NAME", "connection", "show", "--active").out.splitlines()
        exists = CONNECTION in self._read("nmcli", "-t", "-f", "NAME", "connection", "show").out.splitlines()
        if interfaces is None:
            interfaces = parse_iw_dev(self._read("iw", "dev").out)
        return active, exists, any(i.name == AP_INTERFACE for i in interfaces)

    def survey(self):
        """Reads the machine. Gives (a [Plan], the findings as (ok, text) in the order they were looked at); the plan is only whole when
        every finding is ok."""
        plan, findings = Plan(), []

        def finding(ok, text):
            findings.append((ok, text))
            return ok

        dev = self._read("iw", "dev")
        if dev.code == NOT_FOUND:
            finding(False, "`iw` is not installed (it comes in the package of the same name)")
            return plan, findings
        running = self._read("nmcli", "-t", "-f", "RUNNING", "general")
        if running.code == NOT_FOUND:
            finding(False, "`nmcli` is not installed: this tool sets the access point up through NetworkManager")
            return plan, findings
        if not finding(running.out.strip() == "running", "NetworkManager is running" if running.out.strip() == "running"
                       else "NetworkManager is not running: this tool sets the access point up through it"):
            return plan, findings

        interfaces = parse_iw_dev(dev.out)
        stations = [i for i in interfaces if i.name and i.kind == "managed" and i.name != AP_INTERFACE]
        if not stations:
            finding(False, "no Wi-Fi interface found. Plug in a USB Wi-Fi adapter that can be an access point")
            return plan, findings
        station = next((i for i in stations if i.channel is not None), None)
        if station is not None:
            plan.parent, plan.channel, plan.mhz, plan.shared_channel = station.name, station.channel, station.mhz, True
            finding(True, "the Wi-Fi interface %s (%s) is connected on channel %d (%d MHz)" % (station.name, station.phy, station.channel, station.mhz))
        else:
            station = stations[0]
            plan.parent, plan.channel, plan.mhz = station.name, FREE_CHANNEL, FREE_MHZ
            finding(True, "the Wi-Fi interface %s (%s) is not connected to a network: the access point takes channel %d (%d MHz)" % (
                station.name, station.phy, FREE_CHANNEL, FREE_MHZ))

        phys = parse_iw_list(self._read("iw", "list").out)
        phy = phys.get(station.phy) or (list(phys.values())[0] if len(phys) == 1 else None)
        fitting = [c for c in (phy.combinations if phy else []) if c.station_and_ap()]
        if fitting:
            channels = max(c.channels for c in fitting)
            finding(True, "the card can be a station and an access point at once (`managed` and `AP` in one combination, #channels <= %d)%s" % (
                channels, ": the access point has to be on the station's channel" if channels == 1 else ""))
        else:
            finding(False, "the card cannot be a station and an access point at once (`iw list` has no combination with `managed` and `AP` "
                           "together). Put the computer on the internet by a network cable and make the access point by hand on the Wi-Fi, "
                           "or plug in a USB Wi-Fi adapter for the access point")

        plan.band = band_of(plan.mhz)
        if plan.band is None:
            finding(False, "%d MHz is in neither the 2.4 GHz nor the 5 GHz band: this tool does not make an access point there. "
                           "Move the computer to a 2.4 GHz or 5 GHz network" % plan.mhz)
        elif phy is not None:
            listed = phy.frequencies.get(plan.mhz)
            trouble = "is not among the card's frequencies" if listed is None else channel_trouble(listed[1])
            if trouble:
                finding(False, "channel %d (%d MHz) %s, so an access point cannot start on it. Move the computer's Wi-Fi to a network on "
                               "another channel (2.4 GHz: 1 to 11; 5 GHz: 36 to 48 or 149 to 165, where the country allows them)" % (
                                   plan.channel, plan.mhz, trouble))
            else:
                finding(True, "channel %d (%d MHz, the %s band) lets an access point start: no `no IR`, no radar detection" % (
                    plan.channel, plan.mhz, "5 GHz" if plan.band == "a" else "2.4 GHz"))

        clashes = [(name, address) for name, address in parse_addresses(self._read("ip", "-4", "-o", "addr", "show").out)
                   if name != AP_INTERFACE and ipaddress.ip_address(address) in DASH_NETWORK]
        if clashes:
            finding(False, "%s already has the address %s, in the dash's own network (%s): the two would clash. Put the computer on a "
                           "network with other addresses, or change the router's range" % (clashes[0][0], clashes[0][1], DASH_NETWORK))
        else:
            finding(True, "no interface is in the dash's network (%s)" % DASH_NETWORK)

        active, exists, interface = self._leftovers(interfaces)
        if active or exists or interface:
            finding(False, "the access point, or what is left of one, is already here (%s). Run `down` first" % ", ".join(
                what for what, there in (("the connection %s" % CONNECTION, active or exists), ("the interface %s" % AP_INTERFACE, interface)) if there))
        return plan, findings

    def _tell(self, findings):
        for ok, text in findings:
            self.say("%s %s" % ("ok  " if ok else "NO  ", text))

    def check(self):
        """Only reads. 0 when `up` can go ahead, 1 when it cannot."""
        _, findings = self.survey()
        self._tell(findings)
        if not all(ok for ok, _ in findings):
            self.say("the access point cannot go up here")
            return CANNOT
        self.say("the access point can go up here%s" % ("" if self.euid() == 0 else ": run `up` with sudo (it needs root)"))
        return OK

    # ── changing ─────────────────────────────────────────────────────────────────────────────────────────────────

    def _shown(self, argv):
        return " ".join(shlex.quote("<password>" if word in self._hidden else word) for word in argv)

    def _change(self, argv, may_fail=False):
        """Runs a command that changes something, or only writes it with --dry-run. True when it went well (or was not run)."""
        if self.dry_run:
            self.say("would run: " + self._shown(argv))
            return True
        self.say("+ " + self._shown(argv))
        done = self.run(argv)
        if done.code != 0:
            self.say("  %s (exit code %d): %s" % ("did not work" if may_fail else "FAILED", done.code, (done.err or done.out).strip() or "no message"))
        return done.code == 0

    def _needs_root(self, what):
        if self.dry_run or self.euid() == 0:
            return False
        self.say("`%s` needs root and this tool does not call sudo itself. Run it again with sudo: sudo python3 %s %s" % (
            what, os.path.basename(__file__), what))
        return True

    def _seen_by_network_manager(self):
        for _ in range(SEEN_TRIES):
            if AP_INTERFACE in self._read("nmcli", "-t", "-f", "DEVICE", "device", "status").out.splitlines():
                return True
            self.sleep(SEEN_WAIT_S)
        return False

    def up(self, ssid=DEFAULT_SSID, password=None):
        password = self.default_password if password is None else password
        if not ssid.startswith(p.SSID_PREFIX) or len(ssid.encode("utf-8")) > 32:
            self.say("the name has to begin with %s, as the app looks for the dash by it, and fit in 32 bytes" % p.SSID_PREFIX)
            return CANNOT
        if not 8 <= len(password) <= 63:
            self.say("a WPA2 passphrase has 8 to 63 characters")
            return CANNOT
        self._hidden = () if password == self.default_password else (password,)
        plan, findings = self.survey()
        self._tell(findings)
        if not all(ok for ok, _ in findings):
            self.say("the access point cannot go up here; nothing was changed")
            return CANNOT
        if self._needs_root("up"):
            return CANNOT
        if self.dry_run:
            self.say("--dry-run: nothing is changed; these are the commands `up` would run")

        steps = (
            ["iw", "dev", plan.parent, "interface", "add", AP_INTERFACE, "type", "__ap", "addr", local_mac(self.random_bytes)],
            ["nmcli", "device", "set", AP_INTERFACE, "managed", "yes"],
            ["nmcli", "connection", "add", "type", "wifi", "ifname", AP_INTERFACE, "con-name", CONNECTION, "autoconnect", "no", "ssid", ssid,
             "802-11-wireless.mode", "ap", "802-11-wireless.band", plan.band, "802-11-wireless.channel", str(plan.channel),
             "802-11-wireless.cloned-mac-address", "preserve",
             "802-11-wireless-security.key-mgmt", "wpa-psk", "802-11-wireless-security.proto", "rsn",
             "802-11-wireless-security.pairwise", "ccmp", "802-11-wireless-security.group", "ccmp",
             "802-11-wireless-security.psk", password,
             "ipv4.method", "shared", "ipv4.addresses", AP_ADDRESS, "ipv6.method", "disabled"],
            ["nmcli", "connection", "up", CONNECTION],
        )
        for n, step in enumerate(steps):
            ok = self._change(step)
            if ok and n == 0 and not self.dry_run and not self._seen_by_network_manager():
                self.say("  NetworkManager did not take the new interface %s in %.0f s" % (AP_INTERFACE, SEEN_TRIES * SEEN_WAIT_S))
                ok = False
            if not ok:
                self.say("`up` stopped half-way: undoing what it did")
                self.down()
                return FAILED

        self.say("")
        if self.dry_run:
            self.say("and then it would say:")
        else:
            self.say("the access point %s is up on %s: this computer is %s, channel %d" % (ssid, AP_INTERFACE, p.DASH_IP, plan.channel))
        self._next_steps(ssid, plan)
        return OK

    def _next_steps(self, ssid, plan):
        devices = [line.split(":") for line in self._read("nmcli", "-t", "-f", "DEVICE,TYPE,STATE", "device", "status").out.splitlines()]
        vpn = [d[0] for d in devices if len(d) >= 3 and d[1] in VPN_KINDS and d[2].startswith("connected")]
        if vpn:
            self.say("1. A VPN seems to be on (%s): turn on its \"Local network sharing\" (or \"allow LAN access\"), or it drops what the "
                     "phone sends to %s." % (", ".join(vpn), p.DASH_IP))
        else:
            self.say("1. If a VPN is on, turn on its \"Local network sharing\" (or \"allow LAN access\"), or it drops what the phone sends to %s." % p.DASH_IP)
        conf = self.read_file(UFW_CONF)
        if conf is not None and re.search(r"^\s*ENABLED\s*=\s*yes\s*$", conf, re.MULTILINE | re.IGNORECASE):
            self.say("2. The firewall (ufw) is on. Let the dash's ports in:")
            self.say("     sudo ufw allow in on %s to any port %d,%d proto udp" % (AP_INTERFACE, p.CTRL_PORT, p.RTP_PORT))
            self.say("   (if the phone gets no address, let UDP 67 in on %s too; `sudo ufw status numbered` and `sudo ufw delete N` take a rule away)" % AP_INTERFACE)
        else:
            self.say("2. ufw is not on. If another firewall is, let UDP %d and %d in on %s." % (p.CTRL_PORT, p.RTP_PORT, AP_INTERFACE))
        self.say("3. Start the bench panel (--ver shows the video live):")
        self.say("     python3 %s --ssid %s --ver" % (os.path.relpath(os.path.join(HERE, "painel_falso.py")), shlex.quote(ssid)))
        self.say("4. On the phone, forget the dash in REau, pick %s and connect as you would to the bike." % ssid)
        if plan.shared_channel:
            self.say("The access point stays on channel %d. If the computer's Wi-Fi moves to another channel, run `down` and `up` again." % plan.channel)
        self.say("When done: sudo python3 %s down" % os.path.basename(__file__))

    def down(self):
        """Undoes `up` in the reverse order, only what is there. 0 also when there was nothing to undo."""
        active, exists, interface = self._leftovers()
        if not (active or exists or interface):
            self.say("nothing to undo: neither the connection %s nor the interface %s is here" % (CONNECTION, AP_INTERFACE))
            return OK
        if self._needs_root("down"):
            return CANNOT
        ok = True
        if active:
            # The connection may have gone down by itself meanwhile: deleting it is what counts.
            self._change(["nmcli", "connection", "down", CONNECTION], may_fail=True)
        if active or exists:
            ok = self._change(["nmcli", "connection", "delete", CONNECTION]) and ok
        if interface:
            ok = self._change(["iw", "dev", AP_INTERFACE, "del"]) and ok
        if not self.dry_run:
            self.say("the access point is gone" if ok else "something of the access point may be left: see the messages above")
        return OK if ok else FAILED


# ── the command line ─────────────────────────────────────────────────────────────────────────────────────────────────

def arguments(argv):
    parser = argparse.ArgumentParser(
        prog="ponto_de_acesso.py",
        description="Makes this computer the Wi-Fi access point REau's app looks for (for the bench panel), on the channel the computer's own "
                    "Wi-Fi is on, so that it stays connected.")
    commands = parser.add_subparsers(dest="command", required=True)
    up = commands.add_parser("up", help="create the access point (needs root)")
    up.add_argument("--ssid", default=DEFAULT_SSID, help="the access point's name; it has to begin with %s (default %s)" % (p.SSID_PREFIX, DEFAULT_SSID))
    up.add_argument("--password", default=p.DEFAULT_PASSWORD, help="the passphrase the app has for the dash (default: the factory one)")
    up.add_argument("--dry-run", action="store_true", help="read, and write the commands instead of running them")
    down = commands.add_parser("down", help="take the access point away (needs root)")
    down.add_argument("--dry-run", action="store_true", help="read, and write the commands instead of running them")
    commands.add_parser("check", help="only read: say whether the access point can go up here, and why")
    return parser.parse_args(argv)


def main(argv=None, **injected):
    args = arguments(sys.argv[1:] if argv is None else argv)
    tool = AccessPoint(dry_run=getattr(args, "dry_run", False), **injected)
    if args.command == "check":
        return tool.check()
    if args.command == "up":
        return tool.up(args.ssid, args.password)
    return tool.down()


if __name__ == "__main__":
    sys.exit(main())
