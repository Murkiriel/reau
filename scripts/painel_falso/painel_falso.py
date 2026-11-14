#!/usr/bin/env python3
"""The bench panel: a stand-in for the bike's dash that runs on a computer, so a real phone with REau connects and projects with no bike.

It listens on the dash's UDP ports, answers the phone's greeting with an RSA public key made on the spot, takes the session key, sends the
dash's heartbeat, answers each video frame with the dash's notice, puts the RTP video back together into a `.h264` file and lets the bench
press the joystick. What it says and hears goes to the terminal and to a log file. It is written from REau's own code (the app's dash
package and the fake dash of its tests) and answers only what that code shows the dash answering; see painel.py.

Usage:
  python3 painel_falso.py [options]        the panel, until Ctrl+C or `quit`; commands are typed on the standard input (`help` lists them)
  python3 painel_falso.py --ver            the same, and the video is also shown live in an ffplay window as it arrives
  python3 painel_falso.py --selftest       the panel and a minimal phone side on this machine's loopback, with no network; exit code 0 when all is well

The computer has to be what the phone looks for: a Wi-Fi access point whose name begins with the dash's prefix, with this computer at the
dash's address on it. ponto_de_acesso.py, in this folder, makes one; CONTRIBUTING has the steps.
"""
import argparse
import datetime
import os
import queue
import selectors
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import celular_minimo  # noqa: E402
import log_painel  # noqa: E402
import painel  # noqa: E402
import protocolo as p  # noqa: E402
import rtp  # noqa: E402

DEFAULT_OUT = "painel_falso_saida"
LOOPBACK = "127.0.0.1"


VIEWER = "ffplay"
# What the viewer may be behind by, in frames, before the ones that follow are kept from it until the next keyframe.
VIEWER_BACKLOG = 120
VIEWER_LEAVE_S = 2.0


def viewer_command(title, program=VIEWER):
    """ffplay reading raw H.264 from its standard input and showing each frame as soon as it has it: no input buffer, no reordering
    delay, late frames dropped, the 25 frames a second it would assume for a raw stream set aside (`setpts=0`), and gone when the stream ends."""
    return [program, "-hide_banner", "-loglevel", "error", "-nostats", "-fflags", "nobuffer", "-flags", "low_delay", "-framedrop",
            "-analyzeduration", "0", "-vf", "setpts=0", "-autoexit", "-window_title", title, "-f", "h264", "-i", "-"]


def _is_keyframe(data):
    """True when a frame as the sink gives it (a start code, then the NAL) is a parameter set or an IDR: where a decoder can begin."""
    return len(data) > len(rtp.START_CODE) and data[len(rtp.START_CODE)] & 0x1F in (rtp.NAL_IDR, rtp.NAL_SPS)


class LiveView:
    """One session's video shown live: a viewer (ffplay) fed through its standard input.

    The frames go through a queue and a thread of its own, so a viewer that stops reading (paused, or busy) never holds the panel back: past
    [VIEWER_BACKLOG] frames, what follows is kept from it until the next keyframe. A viewer that could not start, or whose window was closed
    (the pipe breaks), is said once and the frames stop going to it; the file is written all the same.
    """

    def __init__(self, log, command, popen=subprocess.Popen, backlog=VIEWER_BACKLOG):
        self.log = log
        self.alive = False
        self.skipped = 0
        self._behind = False
        self._queue = queue.Queue(backlog)
        self._process = None
        try:
            self._process = popen(command, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, bufsize=0)
        except OSError as e:
            log.event("viewer", "%s could not be started (%s): the video is only written to the file" % (command[0], e))
            return
        self.alive = True
        self._thread = threading.Thread(target=self._feed, name="viewer", daemon=True)
        self._thread.start()

    def write(self, data):
        """Hands a frame to the viewer. Never waits for it and never raises."""
        if not self.alive:
            return
        if self._behind and not _is_keyframe(data):
            self.skipped += 1
            return
        try:
            self._queue.put_nowait(data)
            self._behind = False
        except queue.Full:
            if not self._behind:
                self.log.event("viewer", "the viewer is not keeping up: frames are kept from it until the next keyframe (the file gets them all)")
            self._behind = True
            self.skipped += 1

    def _feed(self):
        stdin = self._process.stdin
        while True:
            data = self._queue.get()
            if data is None:
                return
            try:
                view = memoryview(data)
                while len(view):
                    view = view[stdin.write(view) or len(view):]
            except (OSError, ValueError) as e:  # the window was closed (a broken pipe), or the pipe was closed under the thread
                if self.alive:
                    self.alive = False
                    code = self._process.poll()
                    self.log.event("viewer", "the viewer is gone (%s%s): the video is only written to the file from here on" % (
                        type(e).__name__, "" if code is None else ", exit code %d" % code))
                return

    def close(self, leave_s=VIEWER_LEAVE_S):
        """The session is over: the viewer gets what is left, then the end of the stream, and is stopped if it does not leave by itself."""
        if self._process is None:
            return
        was_alive, self.alive = self.alive, False
        try:
            self._queue.put_nowait(None)
        except queue.Full:  # it is behind: what it has not taken yet is given up, so that the end of the stream gets to it
            try:
                while True:
                    self._queue.get_nowait()
            except queue.Empty:
                self._queue.put(None)
        self._thread.join(leave_s if was_alive else 0.1)
        if self._thread.is_alive():  # still writing to a viewer that is not reading: only stopping the viewer frees it
            self._stop()
            self._thread.join(leave_s)
        try:
            self._process.stdin.close()
        except OSError:
            pass
        try:
            self._process.wait(leave_s)
        except subprocess.TimeoutExpired:
            self._stop()

    def _stop(self):
        try:
            self._process.terminate()
            self._process.wait(VIEWER_LEAVE_S)
        except subprocess.TimeoutExpired:
            self._process.kill()
        except OSError:
            pass


class VideoFile:
    """The frames of one session, Annex-B, as they come; with a [viewer], each frame written also goes to it."""

    def __init__(self, path, viewer=None):
        self.path = path
        self.bytes = 0
        self.viewer = viewer
        self._file = open(path, "wb")

    def write(self, data):
        self._file.write(data)
        self.bytes += len(data)
        if self.viewer is not None:
            self.viewer.write(data)

    def close(self):
        self._file.close()
        if self.viewer is not None:
            self.viewer.close()


def broadcast_of(address):
    """The broadcast address of [address]'s network taken as a /24, as the dash's is (192.168.1.1 and 192.168.1.255); None for a
    loopback or a wildcard address, which need none."""
    parts = address.split(".")
    if len(parts) != 4 or address.startswith("127.") or address == "0.0.0.0":
        return None
    return ".".join(parts[:3] + ["255"])


class PanelServer:
    """The sockets around a [painel.FakePanel]: the control port, the video port, and the answers to the phone's own fixed port."""

    def __init__(self, log, bind="", ctrl_port=p.CTRL_PORT, rtp_port=p.RTP_PORT, phone_port=p.RX_PORT, broadcast=None, out_dir=None,
                 view=False, popen=subprocess.Popen, find=shutil.which, **options):
        self.log = log
        self.phone_port = phone_port
        self.out_dir = out_dir
        self.popen = popen
        # --ver: each session's video also goes to a viewer of its own. Without the viewer installed, the video is only written.
        self.viewer = find(VIEWER) if view else None
        if view and not self.viewer:
            log.event("viewer", "%s was not found (it comes with FFmpeg): the video is only written to the file" % VIEWER)
        self.commands = queue.Queue()
        self._stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        self._videos = 0
        self._sockets = []
        try:
            self._ctrl = self._open(bind, ctrl_port)
            self._ctrl.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            self._rtp = self._open(bind, rtp_port)
            self.ctrl_port = self._ctrl.getsockname()[1]
            self.rtp_port = self._rtp.getsockname()[1]
            # A socket bound to one address does not hear that network's broadcasts, and the phone sends its control packets as one.
            self._extra = None
            wanted = broadcast if broadcast is not None else (broadcast_of(bind) if bind else None)
            if wanted:
                try:
                    self._extra = self._open(wanted, self.ctrl_port)
                except OSError as e:
                    log.event("panel", "could not also listen on the broadcast address %s (%s): bind to all the interfaces instead" % (wanted, e))
        except OSError:
            self.close()
            raise
        self.panel = painel.FakePanel(log, self._send, open_video=self._open_video if out_dir else None, **options)

    def _open(self, address, port):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._sockets.append(s)
        s.bind((address, port))
        return s

    def _send(self, packet):
        try:
            self._ctrl.sendto(packet, (self.panel.phone, self.phone_port))
        except OSError as e:
            self.log.detail("panel", "a packet to the phone was not sent: %s" % e)

    def _open_video(self):
        self._videos += 1
        viewer = None
        if self.viewer:
            viewer = LiveView(self.log, viewer_command("REau bench panel - session %d" % self._videos, self.viewer), popen=self.popen)
        return VideoFile(os.path.join(self.out_dir, "video-%s-%02d.h264" % (self._stamp, self._videos)), viewer)

    def submit(self, line):
        """A command for the panel, from another thread (the keyboard, a script, the self-test)."""
        self.commands.put(line)

    def serve(self, stop, script=()):
        """Runs until [stop] is set or a `quit` comes. [script] is commands done in order once the phone has its key (`wait S` pauses)."""
        script = list(script)
        wait_until = 0.0
        selector = selectors.DefaultSelector()
        selector.register(self._ctrl, selectors.EVENT_READ, self.panel.on_control)
        selector.register(self._rtp, selectors.EVENT_READ, self.panel.on_rtp)
        if self._extra:
            selector.register(self._extra, selectors.EVENT_READ, self.panel.on_control)
        try:
            while not stop.is_set():
                for key, _ in selector.select(0.05):
                    try:
                        data, source = key.fileobj.recvfrom(65535)
                    except OSError:
                        continue  # a refused answer to a phone that left comes back here on some systems
                    try:
                        key.data(data, source[0])
                    except Exception as e:  # noqa: BLE001 - a packet the panel trips on costs that packet, not the bench session
                        self.log.error("a packet of %d bytes from %s could not be handled (%s: %s); it is dropped" % (
                            len(data), source[0], type(e).__name__, e))
                while not self.commands.empty():
                    if not self.panel.command(self.commands.get()):
                        stop.set()
                now = time.monotonic()
                if script and self.panel.authenticated and now >= wait_until:
                    line = script.pop(0)
                    words = line.split()
                    if len(words) == 2 and words[0] == "wait":
                        wait_until = now + float(words[1])
                    elif not self.panel.command(line):
                        stop.set()
                self.panel.tick()
        finally:
            selector.close()
            self.panel.close()
            self.close()

    def close(self):
        for s in self._sockets:
            s.close()


# ── the self-test ────────────────────────────────────────────────────────────────────────────────────────────────────

SELFTEST_SSID = p.SSID_PREFIX + "BENCH_0001"
SELFTEST_CALLER = "Bench Caller"
SELFTEST_SECRET = bytes.fromhex("0102030405")


def selftest_nals():
    """The made-up video of the self-test: a "keyframe" too big for one packet (it comes as FU-A pieces), then small and big frames."""
    def nal(header, size, fill):
        return bytes([header]) + bytes((fill + i) & 0xFF for i in range(size - 1))
    return [nal(0x67, 4000, 1)] + [nal(0x41, 300 + 40 * i, i) for i in range(6)] + [nal(0x41, 2900, 9), nal(0x65, 1381, 3), nal(0x41, 1380, 5)]


def _until(what, timeout=5.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if what():
            return True
        time.sleep(0.01)
    return bool(what())


def selftest(out_dir=None, verbose=False, stream=None):
    """The panel and the minimal phone side over this machine's loopback, on ports the system picks. Gives (checks passed, failures)."""
    stream = sys.stdout if stream is None else stream
    passed, failures = [], []

    def check(ok, text):
        (passed if ok else failures).append(text)
        print("%s %s" % ("ok  " if ok else "FAIL", text), file=stream, flush=True)

    with tempfile.TemporaryDirectory(prefix="painel-falso-") as scratch:
        folder = out_dir or scratch
        os.makedirs(folder, exist_ok=True)
        log = log_painel.Log(os.path.join(folder, "selftest.log"), verbose=verbose, quiet=not verbose, stream=stream, keep=True)
        phone = celular_minimo.MinimalPhone((LOOPBACK, 1), (LOOPBACK, 1), SELFTEST_SSID)
        server = PanelServer(log, bind=LOOPBACK, ctrl_port=0, rtp_port=0, phone_port=phone.rx_port, out_dir=folder, ssid=SELFTEST_SSID,
                             keep_frames=True, stats_every=0.5, silence_s=30.0)
        phone.ctrl_dest, phone.rtp_dest = (LOOPBACK, server.ctrl_port), (LOOPBACK, server.rtp_port)
        panel_ = server.panel
        stop = threading.Event()
        thread = threading.Thread(target=server.serve, args=(stop,), name="bench-panel", daemon=True)
        thread.start()
        try:
            check(phone.connect(), "the key exchange ends with the dash's confirmation (07 01 01)")
            check(panel_.session_key is not None and panel_.session_key == phone.session_key, "both sides hold the same AES-256 session key")
            check(panel_.last_ssid == SELFTEST_SSID, "the panel reads the dash's name sent with the key")
            check(_until(lambda: phone.heartbeats) and phone.heartbeats[0] in (30, 31), "the panel's heartbeat carries its age")

            phone.start_projection()
            nals = selftest_nals()
            keyframes = sum(1 for n in nals if n[0] & 0x1F in (rtp.NAL_IDR, rtp.NAL_SPS))
            sink = panel_.sink
            for i, nal in enumerate(nals):
                phone.send_frame(nal, 1000 + 250 * i)
                # One frame at a time, as a stream is: a burst of them would only test this machine's socket buffers.
                _until(lambda: sink.complete + sink.damaged > i, 2.0)
            check(sink.complete == len(nals), "every frame of the video arrives whole (%d of %d)" % (sink.complete, len(nals)))
            check(sink.fault_count == 0 and sink.lost == 0, "no RTP rule is broken and no packet is lost")
            check(sink.annex_b() == b"".join(rtp.START_CODE + n for n in nals), "the frames put back together are the ones sent, byte for byte")
            check(_until(lambda: phone.notices[p.NOTICE_IDR] == keyframes and phone.notices[p.NOTICE_P] == len(nals) - keyframes),
                  "each frame is answered with the dash's notice (09 06 55 for a keyframe, 09 04 55 for the others)")
            check(_until(lambda: panel_.replies[p.REPLY_IDR] == keyframes and panel_.replies[p.REPLY_P] == len(nals) - keyframes),
                  "the panel reads the phone's answer to each notice (06 11, 06 12)")

            codes = sorted(p.BUTTONS.values())
            for code in codes:
                server.submit("press 0x%02X" % code)
            check(_until(lambda: sorted(phone.buttons) == codes), "every button of the joystick reaches the phone (09 00)")
            check(_until(lambda: panel_.echoes == len(codes)), "the panel reads the phone's echo of each button (06 80)")

            phone.call_card(SELFTEST_CALLER)
            check(_until(lambda: panel_.last_caller == SELFTEST_CALLER.encode("latin-1")), "the panel reads the caller's name under the session key (05 22)")
            server.submit("secure 01 " + SELFTEST_SECRET.hex())
            check(_until(lambda: phone.secure == [(0x01, SELFTEST_SECRET)]), "the phone reads a value the panel encrypts under the session key (0F)")

            phone.goodbye()
            check(_until(lambda: panel_.sessions == 1) and panel_.last_exit == ("complete", len(p.EXIT_SEQUENCE)),
                  "the phone's exit sequence is seen whole and ends the session")
            video = panel_.videos[0] if panel_.videos else None
            written = None
            if video and os.path.isfile(video):
                with open(video, "rb") as f:
                    written = f.read()
            check(written == b"".join(rtp.START_CODE + n for n in nals), "the .h264 file holds the video sent")
            check(log.errors == 0, "no protocol error in the whole run (%d)" % log.errors)
            check(any("session over" in line for line in log.lines) and os.path.getsize(log.path) > 0, "the log file tells the session")
        finally:
            stop.set()
            thread.join(3.0)
            phone.close()
            log.close()
        if out_dir:
            print("the self-test's log and video are in %s" % folder, file=stream)
    print("SELFTEST %s: %d checks passed, %d failed" % ("OK" if not failures else "FAILED", len(passed), len(failures)), file=stream, flush=True)
    return passed, failures


# ── the command line ─────────────────────────────────────────────────────────────────────────────────────────────────

def arguments(argv):
    parser = argparse.ArgumentParser(
        prog="painel_falso.py", description="A stand-in for the bike's dash on a computer: REau on a real phone connects and projects to it.",
        epilog=painel.FakePanel.COMMANDS + "\n\nThe same commands are typed on the standard input while the panel runs.",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--selftest", action="store_true", help="try the panel against a minimal phone side on this machine's loopback, and leave")
    parser.add_argument("--bind", default="", metavar="ADDRESS", help="the address to listen on (default: all the interfaces)")
    parser.add_argument("--broadcast", metavar="ADDRESS", help="with --bind: the broadcast address to listen on too (default: the /24 of --bind)")
    parser.add_argument("--ctrl-port", type=int, default=p.CTRL_PORT, help="the control port (default %d, the dash's)" % p.CTRL_PORT)
    parser.add_argument("--rtp-port", type=int, default=p.RTP_PORT, help="the video port (default %d, the dash's)" % p.RTP_PORT)
    parser.add_argument("--phone-port", type=int, default=p.RX_PORT, help="the phone's port the answers go to (default %d, the app's)" % p.RX_PORT)
    parser.add_argument("--out", default=DEFAULT_OUT, metavar="FOLDER", help="where the log and the .h264 files go (default ./%s)" % DEFAULT_OUT)
    parser.add_argument("--ver", action="store_true",
                        help="also show the video live, in an ffplay window for each session (the .h264 file is written all the same)")
    parser.add_argument("--ssid", metavar="NAME", help="the access point's name: a session key sent with another name is rejected (default: any name is taken)")
    parser.add_argument("--age", type=int, default=30, metavar="SECONDS",
                        help="the dash's age when the tool starts; under %d it ignores greetings until it gets there (default 30)" % p.READY_COUNT)
    parser.add_argument("--ack", choices=painel.ACK_MODES, default="every", help="which frames get the dash's notice: every one, only keyframes, none (default every)")
    parser.add_argument("--reject-first-key", action="store_true", help="reject the first session key, accept the next ones")
    parser.add_argument("--ignore-greetings", type=int, default=0, metavar="N", help="ignore the first N greetings")
    parser.add_argument("--silence", type=float, default=6.0, metavar="SECONDS", help="a phone quiet for this long has left (default 6)")
    parser.add_argument("--stats", type=float, default=5.0, metavar="SECONDS", help="how often the video's figures are told (default 5)")
    parser.add_argument("--show-text", action="store_true", help="write the texts the phone sends (titles, songs, the caller) instead of only their size")
    parser.add_argument("--cmd", action="append", default=[], metavar="COMMAND",
                        help="a command done once the phone has its key, in the order given; `wait SECONDS` pauses (may be repeated)")
    parser.add_argument("-v", "--verbose", action="store_true", help="show every packet on the terminal too (the log file always has them)")
    return parser.parse_args(argv)


def _keyboard(server):
    for line in sys.stdin:
        server.submit(line.strip())


def main(argv=None):
    args = arguments(sys.argv[1:] if argv is None else argv)
    if args.selftest:
        _, failures = selftest(out_dir=args.out if args.out != DEFAULT_OUT else None, verbose=args.verbose)
        return 1 if failures else 0
    os.makedirs(args.out, exist_ok=True)
    log = log_painel.Log(os.path.join(args.out, "painel-%s.log" % datetime.datetime.now().strftime("%Y%m%d-%H%M%S")), verbose=args.verbose)
    try:
        server = PanelServer(log, bind=args.bind, ctrl_port=args.ctrl_port, rtp_port=args.rtp_port, phone_port=args.phone_port,
                             broadcast=args.broadcast, out_dir=args.out, view=args.ver, ssid=args.ssid, age=args.age, ack=args.ack,
                             reject_first_key=args.reject_first_key, ignore_greetings=args.ignore_greetings, show_text=args.show_text,
                             silence_s=args.silence, stats_every=args.stats)
    except OSError as e:
        print("could not open the dash's ports (%s). Is another copy running, or another program on UDP %d or %d?" % (e, args.ctrl_port, args.rtp_port),
              file=sys.stderr)
        log.close()
        return 2
    log.event("panel", "listening on %s, control UDP %d, video UDP %d; answers go to the phone's UDP %d" % (
        args.bind or "all the interfaces", server.ctrl_port, server.rtp_port, args.phone_port))
    log.event("panel", "RSA-%d key pair made for this run (kept in memory only); log and video in %s" % (server.panel.key.n.bit_length(), args.out))
    log.event("panel", "type `help` for the commands, `quit` or Ctrl+C to stop")
    threading.Thread(target=_keyboard, args=(server,), name="keyboard", daemon=True).start()
    stop = threading.Event()
    try:
        server.serve(stop, script=args.cmd)
    except KeyboardInterrupt:
        pass
    log.event("panel", "stopped; %d session(s), %d protocol error(s)" % (server.panel.sessions, log.errors))
    log.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
