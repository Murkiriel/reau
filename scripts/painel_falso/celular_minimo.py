"""The least of the phone's side that the bench panel can be tried against on one machine, with no phone: the greeting, the key exchange,
a few frames of made-up video, the answers to the dash's notices and buttons, a caller's name, and the exit sequence.

Every packet is the app's (DashCommands, DashAuth, RtpPacketizer); test_sincronia.py holds the constants to the Kotlin sources. It is not
REau: it has none of its timing, cards or watchdogs, and its "video" is bytes shaped like NAL units, which no player would show.
"""
import secrets
import socket
import threading
import time

import cripto
import k1g
import protocolo as p
import rtp

# The burst the app sends when the sockets open, less the time of day and the phone's status (DashCommands.initialBurst).
HOSTNAME = "REau"


def _packet(hex_text):
    return bytes.fromhex(hex_text)


class MinimalPhone:
    def __init__(self, ctrl_dest, rtp_dest, ssid, bind="127.0.0.1", rx_port=0):
        self.ctrl_dest = ctrl_dest
        self.rtp_dest = rtp_dest
        self.ssid = ssid
        self._tx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._rx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._rx.bind((bind, rx_port))
        self._rx.settimeout(0.1)
        self._video = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.rx_port = self._rx.getsockname()[1]
        self._lock = threading.Lock()
        self._seq = 0
        self._running = True
        self._modulus = None
        self._exponent = None
        self._key_sent = False
        self.session_key = None
        self.confirmed = threading.Event()
        self.rejections = 0
        self.buttons = []         # the codes of the presses that came
        self.notices = {p.NOTICE_IDR: 0, p.NOTICE_P: 0}
        self.heartbeats = []      # the counter of each heartbeat
        self.secure = []          # (sub, plaintext or None) of each 0F value
        self.others = []          # every other TLV the dash sent
        self.packetizer = rtp.Packetizer(seq=secrets.randbelow(rtp.SEQ_MOD), ssrc=secrets.randbits(32), ts_base=secrets.randbits(32))
        self._thread = threading.Thread(target=self._receive, name="minimal-phone-rx", daemon=True)
        self._thread.start()

    # ── control ──────────────────────────────────────────────────────────────────────────────────────────────────

    def send(self, packet):
        """A control packet, with the rolling sequence byte set as the app's socket sets it."""
        with self._lock:
            self._tx.sendto(k1g.patch_seq(packet, self._seq), self.ctrl_dest)
            self._seq = (self._seq + 1) & 0xFF

    def send_tlvs(self, *tlvs):
        self.send(k1g.build(*tlvs))

    def greet(self):
        """The first packets of a session: the greeting, the phone's name, and the three flags that say nothing is projected yet."""
        self.send(_packet(p.AUTH_REQUEST))
        self.send_tlvs(k1g.tlv(*p.HOSTNAME, HOSTNAME.encode("latin-1") + b"\x00"))
        self.send_tlvs(k1g.tlv(0x05, 0x57, p.ON))
        self.send(_packet(p.PROJECTION_STOP))
        self.send(_packet(p.PROJECTION_OFF))
        self.send_tlvs(k1g.tlv(0x05, 0x17, p.OFF))

    def connect(self, timeout=5.0, greet_every=0.5):
        """Greets until the dash's verdict on the key is "confirmed"; False when it does not come in [timeout]."""
        deadline = time.monotonic() + timeout
        self.greet()
        while time.monotonic() < deadline:
            if self.confirmed.wait(greet_every):
                return True
            if not self._key_sent:
                self.send(_packet(p.AUTH_REQUEST))
        return False

    def start_projection(self):
        self.send(_packet(p.PROJECTION_ON))
        self.send(_packet(p.PROJECTION_FRAME))

    def call_card(self, name):
        """The caller's name (05 22): Latin-1 and a NUL, AES-256-CBC under the session key, the IV first (DashCommands.callNotify)."""
        iv = secrets.token_bytes(cripto.BLOCK)
        self.send_tlvs(k1g.tlv(*p.CALLER_NAME, iv + cripto.cbc_encrypt(self.session_key, iv, name.encode("latin-1") + b"\x00")))

    def goodbye(self):
        """The exit sequence, in the order of DashSession.disconnect."""
        for _, type_, sub, value in p.EXIT_SEQUENCE:
            self.send_tlvs(k1g.tlv(type_, sub, value))

    # ── video ────────────────────────────────────────────────────────────────────────────────────────────────────

    def send_frame(self, nal, clock_ms):
        """One frame of one NAL, as the app's stream hands them to its packetizer. Gives the packets, for a test to look at."""
        packets = self.packetizer.packetize(nal, clock_ms)
        for packet in packets:
            self._video.sendto(packet, self.rtp_dest)
        return packets

    # ── what the dash says ───────────────────────────────────────────────────────────────────────────────────────

    def _receive(self):
        while self._running:
            try:
                data, _ = self._rx.recvfrom(65535)
            except socket.timeout:
                continue
            except OSError:
                return
            for tlv in k1g.parse(data).tlvs:
                self._handle(tlv)

    def _handle(self, tlv):
        key = tlv.key
        if key == p.KEY_MODULUS and self._modulus is None:
            self._modulus = int.from_bytes(tlv.value, "big")
            self._offer_key()
        elif key == p.KEY_EXPONENT and self._exponent is None:
            self._exponent = int.from_bytes(tlv.value, "big")
            self._offer_key()
        elif key == p.KEY_VERDICT:
            if tlv.value[:1] == bytes([p.VERDICT_CONFIRMED]):
                self.confirmed.set()
            else:
                # As DashAuth.reset and the session: the key the dash refused is no key, and the greeting goes again.
                self.rejections += 1
                self._modulus = self._exponent = self.session_key = None
                self._key_sent = False
                self.confirmed.clear()
                self.send(_packet(p.AUTH_REQUEST))
        elif key in self.notices and tlv.value[:1] == bytes([p.ON]):
            self.notices[key] += 1
            self.send(_packet(p.FRAME_DECODED_IDR if key == p.NOTICE_IDR else p.FRAME_DECODED_P))
        elif key == p.BUTTON and tlv.value:
            code = tlv.value[-1]
            self.buttons.append(code)
            self.send_tlvs(k1g.tlv(*p.BUTTON_ECHO, code))
            if code in p.ZOOM_CODES:
                self.send_tlvs(k1g.tlv(*p.ZOOM_ICONS, p.ZOOM_BOTH_ICONS))
        elif key == p.HEARTBEAT:
            if tlv.value[:3] == b"K1G" and len(tlv.value) >= 5:
                self.heartbeats.append(int.from_bytes(tlv.value[3:5], "big"))
        elif tlv.type == p.SECURE:
            try:
                plain = cripto.cbc_decrypt(self.session_key, tlv.value[:cripto.BLOCK], tlv.value[cripto.BLOCK:])
            except (ValueError, TypeError):
                plain = None
            self.secure.append((tlv.sub, plain))
        else:
            self.others.append(tlv)

    def _offer_key(self):
        """The session key, once both halves of the dash's public key are in: RSA of the dash's name and a new AES-256 key (DashAuth)."""
        if self._key_sent or self._modulus is None or self._exponent is None:
            return
        aes = secrets.token_bytes(cripto.AES_KEY_BYTES)
        ciphertext = cripto.rsa_encrypt(self.ssid.encode("utf-8") + aes, self._modulus, self._exponent)
        self.session_key = aes
        self._key_sent = True
        self.send_tlvs(k1g.tlv(*p.SESSION_KEY, ciphertext))

    def close(self):
        self._running = False
        self._thread.join(1.0)
        for s in (self._tx, self._rx, self._video):
            s.close()
