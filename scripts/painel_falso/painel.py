"""The dash's side of the link, for a bench: what a dash answers to REau's app, written from the app's own code and from the fake dash of
its tests (FakeDashPeer.kt). No socket here: [FakePanel] is handed each packet that arrives and a function to send with, so the tests
drive it packet by packet.

It answers only what the app's code shows the dash answering: the public key to the greeting, the verdict on the session key, the
heartbeat with its counter, the notices of a decoded frame, the joystick. Everything else the phone sends is logged and nothing more; a
TLV the app's code does not name is logged as unknown. Like the fake dash of the tests, it is permissive: it proves the sequence and the
wiring of the phone's side, not that a real dash would take any of it.
"""
import secrets
import time

import cripto
import k1g
import protocolo as p
import rtp

ACK_MODES = ("every", "key", "none")
# After this many lines of one kind in a session (a video fault, a jump in the control sequence), the rest go to the file only.
LOUD_LINES = 20
BUTTON_ECHO_WAIT_S = 2.0
EXIT_STALL_S = 1.0


class ExitTracker:
    """Follows the phone's exit sequence (DashSession.disconnect) among the TLVs that arrive: nine commands in a fixed order.

    The first two also open the phone's first burst, so a sequence is only taken as one from the third (the "back"); cut short after that,
    it is told as incomplete, with how many of its commands came.

    The app cancels its other senders just before the sequence, and a cancelled one still finishes the packet it was at: the answer to a
    frame's notice (06 11, 06 12), a button's echo, the heartbeat, the projection keep-alive, a card. So a TLV that is none of the nine
    commands may land between two of them, and it neither breaks the sequence nor starts it over. One of the commands out of its place
    does, and so does a sequence that stops coming ([stalled]).
    """

    UNMISTAKABLE = 3
    COMMANDS = frozenset((type_, sub, bytes([value])) for _, type_, sub, value in p.EXIT_SEQUENCE)

    def __init__(self):
        self.at = 0
        self.last_step = None

    def feed(self, tlv, now):
        """("complete", 9) at the last command, ("incomplete", n) when a sequence that had begun is broken by [tlv], else None."""
        got = (tlv.type, tlv.sub, tlv.value)
        if got not in self.COMMANDS:
            return None
        if 0 < self.at < self.UNMISTAKABLE and now - self.last_step >= EXIT_STALL_S:
            self.at = 0  # the two commands that also end a projection, long ago: not the beginning of this one
        if got == self._step(self.at):
            self.at += 1
            self.last_step = now
            if self.at == len(p.EXIT_SEQUENCE):
                self.at = 0
                return ("complete", len(p.EXIT_SEQUENCE))
            return None
        broken = self.at
        self.at = 1 if got == self._step(0) else 0
        self.last_step = now if self.at else None
        return ("incomplete", broken) if broken >= self.UNMISTAKABLE else None

    def stalled(self, now):
        """How many commands of a sequence that stopped coming, once [EXIT_STALL_S] went by; None otherwise."""
        if self.at >= self.UNMISTAKABLE and now - self.last_step >= EXIT_STALL_S:
            n, self.at = self.at, 0
            return n
        return None

    @staticmethod
    def _step(i):
        _, type_, sub, value = p.EXIT_SEQUENCE[i]
        return (type_, sub, bytes([value]))

    @staticmethod
    def names(n):
        return " ".join(step[0] for step in p.EXIT_SEQUENCE[:n])


def button_code(word):
    """The code of a button given by name (`zoom-in`, `left`) or by number (`0x14`, `20`); ValueError for anything else."""
    name = p.BUTTON_ALIASES.get(word.lower(), word.lower())
    if name in p.BUTTONS:
        return p.BUTTONS[name]
    try:
        code = int(word, 0)
    except ValueError:
        raise ValueError("no button is called %r (the names: %s)" % (word, ", ".join(sorted(list(p.BUTTONS) + list(p.BUTTON_ALIASES))))) from None
    if not 0 <= code <= 0xFF:
        raise ValueError("a button code is one byte")
    return code


def button_name(code):
    return next((name for name, c in p.BUTTONS.items() if c == code), "unnamed")


class FakePanel:
    """One dash. [send] takes a whole control packet for the phone; [open_video], called at a session's first good frame, gives something
    with `write(bytes)` and `close()` (and `path`, `bytes`) to keep the video in."""

    def __init__(self, log, send, key=None, ssid=None, age=30, ack="every", reject_first_key=False, ignore_greetings=0,
                 show_text=False, silence_s=6.0, stats_every=5.0, clock=time.monotonic, open_video=None, keep_frames=False):
        if ack not in ACK_MODES:
            raise ValueError("ack is one of %s" % ", ".join(ACK_MODES))
        self.log = log
        self._send_packet = send
        self.key = key or cripto.generate_key()
        self.expected_ssid = ssid
        self.ack = ack
        self.reject_first_key = reject_first_key
        self.ignore_greetings = ignore_greetings
        self.show_text = show_text
        self.silence_s = silence_s
        self.stats_every = stats_every
        self.clock = clock
        self.open_video = open_video
        self.silent = False
        self._born = clock() - age
        self._seq = 0
        self._last_heartbeat = None
        self.greetings = 0
        self.key_attempts = 0
        self.sessions = 0
        self.keep_frames = keep_frames
        self.videos = []          # the path of each video file written
        self.last_caller = None   # the caller's name of the last call card read, as bytes (Latin-1, as the dash reads it)
        self.last_exit = None     # ("complete", 9) or ("incomplete", n) of the last exit sequence seen
        self._new_session()

    # ── the session ──────────────────────────────────────────────────────────────────────────────────────────────

    def _new_session(self):
        self.session_key = None
        self.last_ssid = None
        self.phone = None
        self.exit = ExitTracker()
        self.sink = rtp.RtpSink(keep=self.keep_frames, clock=self.clock)
        self.stats = rtp.StreamStats()
        self.notices = {p.NOTICE_IDR: 0, p.NOTICE_P: 0}
        self.replies = {p.REPLY_IDR: 0, p.REPLY_P: 0}
        self.presses = 0
        self.echoes = 0
        self.unknown = {}
        self.sequence_jumps = 0
        self.rtp_packets = 0
        self._phone_seq = None
        self._last_rx = None
        self._pending = {}        # button code -> when it was pressed, until the phone echoes it
        self._flags = {}          # the last value of the TLVs told only when they change
        self._faults_told = 0
        self._video = None
        self._video_frames = 0
        self._video_before_key = False
        self._stats_at = None

    @property
    def authenticated(self):
        return self.session_key is not None

    def age(self, now=None):
        """The dash's age in seconds: what its heartbeat counts."""
        return int((self.clock() if now is None else now) - self._born)

    def _end_session(self, why):
        self.sessions += 1
        self.log.event("session", "session over: " + why)
        self.sink.restart()  # a frame left without its marker counts as damaged
        self.log.event("video", self.stats.total_line(self.sink))
        clock = self.stats.clock_line()
        if clock:
            if self.stats.clock_wrong:
                self.log.error("video timestamps: " + clock)
            else:
                self.log.event("video", "timestamps: " + clock)
        if self._video is not None:
            self._video.close()
            self.log.event("video", "%d frames written to %s (%d bytes)" % (self._video_frames, self._video.path, self._video.bytes))
        for notice, reply, name in ((p.NOTICE_IDR, p.REPLY_IDR, "09 06 55"), (p.NOTICE_P, p.REPLY_P, "09 04 55")):
            if self.notices[notice] or self.replies[reply]:
                self.log.event("session", "notices %s sent: %d, answered by the phone: %d" % (name, self.notices[notice], self.replies[reply]))
        if self.presses:
            self.log.event("session", "buttons pressed: %d, echoed by the phone: %d" % (self.presses, self.echoes))
        if self.unknown:
            self.log.event("session", "TLVs the app's code does not name, only logged: " +
                           ", ".join("%02X%02X x%d" % (key + (n,)) for key, n in sorted(self.unknown.items())))
        if self.sequence_jumps:
            self.log.event("session", "the control sequence jumped %d times (packets lost or out of order)" % self.sequence_jumps)
        self.log.event("session", "protocol errors so far: %d" % self.log.errors)
        self._new_session()

    # ── sending ──────────────────────────────────────────────────────────────────────────────────────────────────

    def _send(self, *tlvs):
        if self.silent or self.phone is None:
            return False
        self._send_packet(k1g.build(*tlvs, seq=self._seq))
        self._seq = (self._seq + 1) & 0xFF
        self.log.detail("tx", k1g.describe(tlvs, self.show_text))
        return True

    # ── control packets from the phone ───────────────────────────────────────────────────────────────────────────

    def on_control(self, data, source):
        """A control packet arrived from the address [source]."""
        now = self.clock()
        if self.phone is None:
            self.log.event("session", "a phone at %s speaks" % source)
            self._last_heartbeat = None
        elif source != self.phone:
            self.log.event("session", "control packets now come from %s, not %s: answered there" % (source, self.phone))
        self.phone = source
        self._last_rx = now
        packet = k1g.parse(data)
        for problem in packet.problems:
            self.log.error("control packet of %d bytes: %s" % (len(data), problem))
        if packet.seq is not None:
            if self._phone_seq is not None and packet.seq != (self._phone_seq + 1) & 0xFF:
                self.sequence_jumps += 1
                line = "the control sequence went from %d to %d" % (self._phone_seq, packet.seq)
                (self.log.event if self.sequence_jumps <= LOUD_LINES else self.log.detail)("session", line)
            self._phone_seq = packet.seq
        self.log.detail("rx", "seq=%s %s" % (packet.seq, k1g.describe(packet.tlvs, self.show_text)))
        for tlv in packet.tlvs:
            self._handle(tlv, now)
            if self.phone is None:  # the session ended at this TLV: the rest of the packet belongs to nothing
                break

    def _handle(self, tlv, now):
        verdict = self.exit.feed(tlv, now)
        if verdict:
            self._exit_seen(verdict)
            if verdict[0] == "complete":
                return
        key = tlv.key
        if key == p.GREETING:
            self._greeting(now)
        elif key == p.SESSION_KEY:
            self._session_key(tlv.value)
        elif key in self.replies:
            self.replies[key] += 1
        elif key == p.BUTTON_ECHO:
            self._button_echo(tlv.value, now)
        elif key == p.CALLER_NAME:
            self._caller(tlv.value)
        elif key == p.HOSTNAME:
            self._changed(key, tlv.value, "the phone calls itself %r" % tlv.value.rstrip(b"\x00").decode("latin-1"))
        elif key == p.PROJECTION:
            self._changed(key, tlv.value, "projection keep-alive (05 56) says " + self._on_off(tlv.value))
        elif key == p.PROJECTION_FLAG:
            self._changed(key, tlv.value, "projection flag (06 05) says " + self._on_off(tlv.value))
        elif key not in p.PHONE_TLVS:
            self.unknown[key] = self.unknown.get(key, 0) + 1
            if self.unknown[key] == 1:
                self.log.event("session", "TLV %s (%d bytes) is not one the app's code names: only logged" % (tlv.name, len(tlv.value)))

    @staticmethod
    def _on_off(value):
        return {bytes([p.ON]): "on (55)", bytes([p.OFF]): "off (AA)"}.get(value, "0x" + value.hex().upper())

    def _changed(self, key, value, line):
        if self._flags.get(key) != value:
            self._flags[key] = value
            self.log.event("session", line)

    def _greeting(self, now):
        if self.authenticated:
            # The app greets again only before it has the key: a greeting after the verdict is a phone that started over without saying goodbye.
            source = self.phone
            self._end_session("the phone greeted again in an authenticated session (it started over with no exit sequence)")
            self.phone = source
            self._last_rx = now
        self.greetings += 1
        if self.greetings <= self.ignore_greetings:
            self.log.event("auth", "greeting %d ignored (the first %d are, by --ignore-greetings)" % (self.greetings, self.ignore_greetings))
        elif self.age(now) < p.READY_COUNT:
            self.log.event("auth", "greeting ignored: the dash is %d s old and takes none before %d" % (self.age(now), p.READY_COUNT))
        else:
            self._send(k1g.tlv(*p.KEY_MODULUS, self.key.modulus_bytes()))
            self._send(k1g.tlv(*p.KEY_EXPONENT, self.key.exponent_bytes()))
            self.log.event("auth", "greeting (08 04) answered with the RSA public key (07 00, 07 03)")

    def _session_key(self, ciphertext):
        self.key_attempts += 1
        if self.reject_first_key and self.key_attempts == 1:
            self._send(k1g.tlv(*p.KEY_VERDICT, p.VERDICT_REJECTED))
            self.log.event("auth", "session key rejected (07 01 00) on purpose, by --reject-first-key")
            return
        try:
            payload = self.key.decrypt(ciphertext)
            if len(payload) < cripto.AES_KEY_BYTES:
                raise ValueError("%d bytes inside, fewer than an AES-256 key" % len(payload))
        except ValueError as e:
            self._send(k1g.tlv(*p.KEY_VERDICT, p.VERDICT_REJECTED))
            self.log.error("session key (08 00, %d bytes) refused: %s; rejected (07 01 00)" % (len(ciphertext), e))
            return
        ssid = payload[:-cripto.AES_KEY_BYTES].decode("utf-8", "replace")
        if self.expected_ssid is not None and ssid != self.expected_ssid:
            self._send(k1g.tlv(*p.KEY_VERDICT, p.VERDICT_REJECTED))
            self.log.error("the phone sent the dash name %r with its key, not %r; rejected (07 01 00)" % (ssid, self.expected_ssid))
            return
        self.session_key = payload[-cripto.AES_KEY_BYTES:]
        self.last_ssid = ssid
        self._send(k1g.tlv(*p.KEY_VERDICT, p.VERDICT_CONFIRMED))
        self.log.event("auth", "session key accepted (07 01 01): AES-256, sent with the dash name %r" % ssid)

    def _button_echo(self, value, now):
        code = value[-1] if value else None
        if code in self._pending:
            self.echoes += 1
            self.log.event("button", "0x%02X echoed by the phone (06 80) after %.0f ms" % (code, (now - self._pending.pop(code)) * 1000))
        elif code == p.NAV_START:
            self.log.event("session", "start of navigation (06 80 0B)")
        elif code != p.EXIT_SEQUENCE[2][3]:  # the exit's "back" is told by the exit sequence
            self.log.event("button", "an echo (06 80) of 0x%s that no press here asked for" % value.hex().upper())

    def _caller(self, value):
        if not self.authenticated:
            self.log.error("a caller's name (05 22) with no session key to read it with")
            return
        try:
            plain = cripto.cbc_decrypt(self.session_key, value[:cripto.BLOCK], value[cripto.BLOCK:])
        except ValueError as e:
            self.log.error("the caller's name (05 22, %d bytes) could not be read with the session key: %s" % (len(value), e))
            return
        self.last_caller = plain.rstrip(b"\x00")
        shown = repr(self.last_caller.decode("latin-1")) if self.show_text else "%d characters, not shown" % len(self.last_caller)
        self.log.event("session", "caller's name (05 22) read with the session key: " + shown)

    def _exit_seen(self, verdict):
        self.last_exit = verdict
        kind, n = verdict
        if kind == "complete":
            self._end_session("the phone's exit sequence came whole (%s)" % ExitTracker.names(n))
        else:
            self.log.error("the phone's exit sequence came only up to command %d of %d (%s)" % (n, len(p.EXIT_SEQUENCE), ExitTracker.names(n)))

    # ── the video ────────────────────────────────────────────────────────────────────────────────────────────────

    def on_rtp(self, data, source):
        """A video packet arrived from the address [source]."""
        if self.silent:
            return
        self.rtp_packets += 1
        if self.rtp_packets == 1:
            self.log.event("video", "first video packet, from %s" % source)
        if not self.authenticated and not self._video_before_key:
            self._video_before_key = True
            self.log.error("video arrives before the session key was accepted")
        kept, counted = len(self.sink.faults), self.sink.fault_count
        frame = self.sink.on_packet(data)
        new = self.sink.faults[kept:]
        self.log.errors += self.sink.fault_count - counted - len(new)  # past what the sink keeps, a fault is only counted
        for fault in new:
            self._faults_told += 1
            if self._faults_told <= LOUD_LINES:
                self.log.error("video " + fault)
            else:
                self.log.errors += 1
                self.log.detail("protocol", "ERROR: video " + fault)
        if frame is None:
            return
        self.stats.add(frame)
        if self._stats_at is None:
            self._stats_at = frame.arrival
        if self.stats.frames == 1:
            self.log.event("video", "first frame: %s, %d bytes in %d packets" % ("a keyframe" if frame.key else "NOT a keyframe", len(frame.data), frame.packets))
        if frame.damaged:
            self.log.detail("video", "a damaged frame (%d bytes got) is not written nor answered" % len(frame.data))
            return
        if self.open_video is not None:
            if self._video is None:
                self._video = self.open_video()
                self.videos.append(self._video.path)
            self._video.write(frame.data)
            self._video_frames += 1
        if self.ack == "every" or (self.ack == "key" and frame.key):
            self.notice(frame.key)

    def notice(self, keyframe):
        """The dash's notice of a decoded frame: 09 06 55 for a keyframe, 09 04 55 for any other."""
        key = p.NOTICE_IDR if keyframe else p.NOTICE_P
        if self._send(k1g.tlv(*key, p.ON)):
            self.notices[key] += 1

    # ── what the bench does ──────────────────────────────────────────────────────────────────────────────────────

    def press(self, code):
        """A press of the joystick, as the dash sends it: 09 00, one byte, the code."""
        if self._send(k1g.tlv(*p.BUTTON, code)):
            self.presses += 1
            self._pending[code] = self.clock()
            self.log.event("button", "0x%02X (%s) sent (09 00)" % (code, button_name(code)))
        else:
            self.log.event("button", "0x%02X not sent: %s" % (code, "the panel is silent" if self.silent else "no phone has spoken yet"))

    def reject_now(self):
        """A rejection of the key (07 01 00) at any time, as the app's fake dash can send."""
        if self._send(k1g.tlv(*p.KEY_VERDICT, p.VERDICT_REJECTED)):
            self.log.event("auth", "rejection (07 01 00) sent")

    def raw(self, type_, sub, value):
        """Any TLV, as given: what it means is the bench's business."""
        if self._send(k1g.tlv(type_, sub, value)):
            self.log.event("raw", "%02X%02X sent with %d bytes" % (type_, sub, len(value)))
        else:
            self.log.event("raw", "not sent: no phone has spoken yet, or the panel is silent")

    def secure(self, sub, plain):
        """A 0F TLV: [plain] under the session key (AES-256-CBC, the IV first), as the app reads the dash's encrypted values."""
        if not self.authenticated:
            self.log.event("raw", "0F%02X not sent: no session key yet" % sub)
            return
        iv = secrets.token_bytes(cripto.BLOCK)
        if self._send(k1g.tlv(p.SECURE, sub, iv + cripto.cbc_encrypt(self.session_key, iv, plain))):
            self.log.event("raw", "0F%02X sent: %d bytes under the session key" % (sub, len(plain)))

    def status(self):
        state = "no phone" if self.phone is None else "phone at %s, %s" % (self.phone, "authenticated" if self.authenticated else "no session key yet")
        self.log.event("status", "%s; dash age %d s; %s; protocol errors %d" % (state, self.age(), self.stats.total_line(self.sink), self.log.errors))

    COMMANDS = """commands:
  press NAME|CODE     a joystick press (09 00): zoom-in/left 0x14, zoom-out/right 0x13, recentre/down 0x15, back/up 0x12,
                      in/middle 0x18, play 0x05, vol-up 0x06, vol-down 0x07, next 0x09, prev 0x0A, or any code (0x14, 20)
  notice key|frame    one notice of a decoded frame by hand (09 06 55, 09 04 55)
  reject              a rejection of the key (07 01 00)
  silent on|off       stop answering (and sending the heartbeat), as a dash out of reach; answer again
  raw TT SS [HEX]     any TLV, type and sub in hex, the value in hex
  secure SS HEX       a 0F TLV with HEX encrypted under the session key
  status              what the panel has seen so far
  quit                stop the tool (a dash switched off just goes quiet)"""

    def command(self, line):
        """One line typed at the bench. False when it asks the tool to stop."""
        words = line.split()
        if not words:
            return True
        verb, args = words[0].lower(), words[1:]
        try:
            if verb in ("quit", "exit"):
                return False
            if verb == "press" and len(args) == 1:
                self.press(button_code(args[0]))
            elif verb == "notice" and args in (["key"], ["frame"]):
                self.notice(args[0] == "key")
            elif verb == "reject" and not args:
                self.reject_now()
            elif verb == "silent" and args in (["on"], ["off"]):
                self.silent = args[0] == "on"
                self.log.event("status", "the panel is silent: nothing is sent" if self.silent else "the panel answers again")
            elif verb == "raw" and len(args) in (2, 3):
                self.raw(int(args[0], 16), int(args[1], 16), bytes.fromhex(args[2]) if len(args) == 3 else b"")
            elif verb == "secure" and len(args) == 2:
                self.secure(int(args[0], 16), bytes.fromhex(args[1]))
            elif verb == "status" and not args:
                self.status()
            else:
                self.log.event("status", ("" if verb == "help" else "not understood: %r\n" % line) + self.COMMANDS)
        except ValueError as e:
            self.log.event("status", "not understood: %r (%s)" % (line, e))
        return True

    # ── time ─────────────────────────────────────────────────────────────────────────────────────────────────────

    def tick(self, now=None):
        """Called several times a second: the heartbeat, the echoes that did not come, the figures of the video, the phone gone quiet."""
        now = self.clock() if now is None else now
        if self.phone is None:
            return
        if self._last_heartbeat is None or now - self._last_heartbeat >= 1.0:
            self._last_heartbeat = now
            # "K1G" and the counter in two bytes, of which the dash only ever uses the low one (DashReadiness).
            self._send(k1g.tlv(*p.HEARTBEAT, b"K1G" + bytes([0, self.age(now) & 0xFF])))
        for code, at in list(self._pending.items()):
            if now - at >= BUTTON_ECHO_WAIT_S:
                del self._pending[code]
                self.log.error("button 0x%02X was not echoed by the phone (06 80) in %.0f s" % (code, BUTTON_ECHO_WAIT_S))
        stalled = self.exit.stalled(now)
        if stalled:
            self._exit_seen(("incomplete", stalled))
            self._end_session("the exit sequence stopped at command %d" % stalled)
            return
        if self._stats_at is not None and now - self._stats_at >= self.stats_every:
            self._stats_at = now
            line = self.stats.window_line(now, self.sink)
            self.log.event("video", line if line else "no frame in the last %.0f s" % self.stats_every)
        if self._last_rx is not None and now - self._last_rx >= self.silence_s:
            self._end_session("the phone has sent no control packet for %.0f s (no exit sequence came)" % (now - self._last_rx))

    def close(self):
        """The tool stops: an open session is closed and told."""
        if self.phone is not None:
            self._end_session("the bench panel was stopped")
