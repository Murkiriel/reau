"""The receiving end of the dash's video: takes the RTP packets as they arrive, checks each against the rules of the stream and puts the
frames back together as Annex-B, which any H.264 player opens. It decodes nothing.

The rules are the ones of the app's own test sink (RtpSink.kt): version 2 and one payload type; the sequence number goes up by one, wrapping
at 65536; one source id for the whole stream; every packet of a frame carries the frame's timestamp, and a new frame a different one; the
marker bit is on the last packet of a frame and on no other; a NAL too big for one packet comes as FU-A pieces (type 28) with the start bit
on the first, the end bit on the last and the same NAL type on all; no aggregation packets; no packet over [MAX_PAYLOAD] bytes after the
header. What breaks a rule is kept in [RtpSink.faults], one line each, and the stream goes on.

On a real Wi-Fi link packets get lost, which a loopback test never sees, so this sink also says what a fault did to the picture: a frame
with a hole in it is marked damaged (the bench panel does not write it to the file nor answer it), a packet that comes late is dropped and
the lost ones are counted.
"""
import time

import protocolo

MAX_PAYLOAD = protocolo.MAX_PAYLOAD
HEADER = 12
FU_A = 28
AGGREGATION = range(24, 28)
NAL_IDR = 5
NAL_SPS = 7
START_CODE = b"\x00\x00\x00\x01"
CLOCK_HZ = 90_000
SEQ_MOD = 1 << 16
TS_MOD = 1 << 32
# Packets in a row from one other source id after which the stream is taken to have started again with it (a new encoder on the phone).
SOURCE_SWITCH_AFTER = 5
FAULTS_KEPT = 500


class Frame:
    """One frame put back together: Annex-B bytes, its RTP timestamp, whether it is a keyframe, whether a fault left a hole in it."""

    def __init__(self, data, timestamp, key, damaged, packets, arrival):
        self.data = data
        self.timestamp = timestamp
        self.key = key
        self.damaged = damaged
        self.packets = packets
        self.arrival = arrival


class RtpSink:
    def __init__(self, max_payload=MAX_PAYLOAD, keep=True, clock=time.monotonic):
        self.max_payload = max_payload
        self.keep = keep
        self.clock = clock
        self.faults = []        # what broke a rule, in arrival order (the first FAULTS_KEPT)
        self.fault_count = 0
        self.frames = []        # every frame, when [keep]
        self.packets = 0
        self.lost = 0           # packets the sequence numbers say never came
        self.late = 0           # packets that came after a later one (or twice): dropped
        self.complete = 0       # frames with no hole
        self.damaged = 0        # frames with one, and frames that never got their marker
        self._reset_stream()

    def _reset_stream(self):
        self._last_seq = -1
        self._ssrc = None
        self._payload_type = -1
        self._last_frame_ts = None
        self._other = None
        self._other_count = 0
        self._reset_frame()

    def _reset_frame(self):
        self._frame = bytearray()
        self._frame_ts = None
        self._frame_packets = 0
        self._frame_damaged = False
        self._frame_key = False
        self._in_pieces = False
        self._pieces_type = -1

    def restart(self):
        """A new stream is coming (a new session): what is left of a frame is counted as damaged; the counters stay."""
        self._drop_unfinished()
        self._reset_stream()

    @property
    def ended_inside_a_frame(self):
        """Whether packets came after the last marker."""
        return self._frame_ts is not None

    def annex_b(self):
        """Every complete frame kept, in order."""
        return b"".join(f.data for f in self.frames if not f.damaged)

    def _fault(self, text, damages=False):
        self.fault_count += 1
        if len(self.faults) < FAULTS_KEPT:
            self.faults.append(text)
        if damages:
            self._frame_damaged = True

    def _drop_unfinished(self):
        if self._frame_ts is not None:
            self.damaged += 1
        self._reset_frame()

    def _other_source(self, source, n):
        """A packet of another source id: dropped, unless enough of them came in a row to be the stream starting again. True when taken."""
        if source == self._other:
            self._other_count += 1
        else:
            self._other, self._other_count = source, 1
        if self._other_count < SOURCE_SWITCH_AFTER:
            self._fault("packet %d: source id %08X in a stream of source id %08X, dropped" % (n, source, self._ssrc))
            return False
        self._fault("packet %d: %d packets in a row of source id %08X: the stream starts again with it" % (n, self._other_count, source))
        self.restart()
        self._ssrc = source
        return True

    def on_packet(self, packet):
        """Takes one RTP packet; gives the [Frame] it completes, or None."""
        self.packets += 1
        n = self.packets
        if len(packet) < HEADER + 1:
            self._fault("packet %d: %d bytes, too short for an RTP header and a payload" % (n, len(packet)))
            return None
        if packet[0] & 0xC0 != 0x80:
            self._fault("packet %d: RTP version %d, not 2" % (n, (packet[0] & 0xC0) >> 6))
        marker = packet[1] & 0x80 != 0
        payload_type = packet[1] & 0x7F
        seq = int.from_bytes(packet[2:4], "big")
        ts = int.from_bytes(packet[4:8], "big")
        source = int.from_bytes(packet[8:12], "big")

        if self._ssrc is None:
            self._ssrc = source
        elif source != self._ssrc:
            if not self._other_source(source, n):
                return None
        else:
            self._other_count = 0
        if self._payload_type < 0:
            self._payload_type = payload_type
        elif payload_type != self._payload_type:
            self._fault("packet %d: payload type %d, the stream began with %d" % (n, payload_type, self._payload_type))

        if self._last_seq >= 0:
            ahead = (seq - self._last_seq - 1) % SEQ_MOD
            if ahead >= SEQ_MOD // 2:
                self.late += 1
                self._fault("packet %d: sequence %d after %d (late or repeated), dropped" % (n, seq, self._last_seq))
                return None
            if ahead:
                self.lost += ahead
                # A hole in the frame being put together; between two frames, the pieces themselves say whether one is missing.
                self._fault("packet %d: sequence %d after %d (%d lost)" % (n, seq, self._last_seq, ahead), damages=self._frame_ts is not None)
        self._last_seq = seq
        if len(packet) - HEADER > self.max_payload:
            self._fault("packet %d: a payload of %d bytes, over %d" % (n, len(packet) - HEADER, self.max_payload))

        if self._frame_ts is not None and ts != self._frame_ts:
            self._fault("packet %d: timestamp %d inside the frame of timestamp %d (a frame ended without its marker?)" % (n, ts, self._frame_ts))
            self._drop_unfinished()
        if self._frame_ts is None:
            self._frame_ts = ts
            if self._last_frame_ts is not None:
                if ts == self._last_frame_ts:
                    self._fault("packet %d: a new frame with the timestamp of the one before" % n)
                elif (ts - self._last_frame_ts) % TS_MOD >= TS_MOD // 2:
                    self._fault("packet %d: timestamp %d, before the frame before (%d)" % (n, ts, self._last_frame_ts))
        self._frame_packets += 1

        nal_type = packet[HEADER] & 0x1F
        if nal_type == FU_A:
            self._piece(packet, n)
        elif nal_type in AGGREGATION:
            self._fault("packet %d: an aggregation packet (type %d), which the dash refuses" % (n, nal_type), damages=True)
        elif nal_type == 0 or nal_type > FU_A:
            self._fault("packet %d: NAL type %d is not one the stream may carry" % (n, nal_type), damages=True)
        else:
            if self._in_pieces:
                self._fault("packet %d: a whole NAL in the middle of a NAL that came in pieces" % n, damages=True)
            self._in_pieces = False
            self._frame_key = self._frame_key or nal_type in (NAL_IDR, NAL_SPS)
            self._frame += START_CODE + packet[HEADER:]

        if not marker:
            return None
        if self._in_pieces:
            self._fault("packet %d: the marker on a piece that is not the last of its NAL" % n, damages=True)
        frame = Frame(bytes(self._frame), ts, self._frame_key, self._frame_damaged, self._frame_packets, self.clock())
        if frame.damaged:
            self.damaged += 1
        else:
            self.complete += 1
        if self.keep:
            self.frames.append(frame)
        self._last_frame_ts = ts
        self._reset_frame()
        return frame

    def _piece(self, packet, n):
        if len(packet) < HEADER + 3:
            self._fault("packet %d: an FU-A piece with no bytes of the NAL" % n, damages=True)
            return
        fu = packet[HEADER + 1]
        start = fu & 0x80 != 0
        end = fu & 0x40 != 0
        nal_type = fu & 0x1F
        if fu & 0x20:
            self._fault("packet %d: the reserved bit of the FU header is set" % n)
        if start and end:
            self._fault("packet %d: an FU-A piece that is both the first and the last" % n)
        if start:
            if self._in_pieces:
                self._fault("packet %d: a first piece before the last piece of the NAL before" % n, damages=True)
            self._in_pieces = True
            self._pieces_type = nal_type
            self._frame_key = self._frame_key or nal_type in (NAL_IDR, NAL_SPS)
            self._frame += START_CODE + bytes([(packet[HEADER] & 0xE0) | nal_type])
        elif not self._in_pieces:
            self._fault("packet %d: a piece of a NAL whose first piece did not come" % n, damages=True)
        elif nal_type != self._pieces_type:
            self._fault("packet %d: a piece of NAL type %d inside a NAL of type %d" % (n, nal_type, self._pieces_type), damages=True)
        self._frame += packet[HEADER + 2:]
        if end:
            self._in_pieces = False


class StreamStats:
    """Frames a second, the frames' sizes and the stream's clock, from the frames a sink completes: one line per window, one for the whole."""

    # A stream this long whose timestamps run this far from the wall clock is not on a 90 kHz clock.
    CLOCK_CHECK_AFTER_S = 5.0
    CLOCK_TOLERANCE = 0.10

    def __init__(self):
        self.frames = 0
        self.keyframes = 0
        self.bytes = 0
        self.smallest = None
        self.largest = 0
        self.first_arrival = None
        self.last_arrival = None
        self.media_ticks = 0
        self._last_ts = None
        self._window = []
        self._window_start = None

    def add(self, frame):
        size = len(frame.data)
        self.frames += 1
        self.keyframes += frame.key
        self.bytes += size
        self.smallest = size if self.smallest is None else min(self.smallest, size)
        self.largest = max(self.largest, size)
        if self.first_arrival is None:
            self.first_arrival = frame.arrival
            self._window_start = frame.arrival
        else:
            step = (frame.timestamp - self._last_ts) % TS_MOD
            if step < TS_MOD // 2:
                self.media_ticks += step
        self._last_ts = frame.timestamp
        self.last_arrival = frame.arrival
        self._window.append((size, frame.key))

    def window_line(self, now, sink=None):
        """The line of the frames since the last call (None when there were none), and the window starts over at [now]."""
        frames, self._window = self._window, []
        start, self._window_start = self._window_start, now
        if not frames or start is None:
            return None
        seconds = max(now - start, 1e-3)
        sizes = [s for s, _ in frames]
        line = "%.1f fps, %.0f kbit/s, %d frames (%d key), %d/%d/%d bytes a frame (least/mean/most)" % (
            len(frames) / seconds, sum(sizes) * 8 / 1000 / seconds, len(frames), sum(k for _, k in frames),
            min(sizes), sum(sizes) // len(sizes), max(sizes))
        if sink is not None:
            line += "; so far %d lost, %d late, %d damaged frames" % (sink.lost, sink.late, sink.damaged)
        return line

    def clock_line(self):
        """How the frames' timestamps ran against the clock of their arrival, and whether that is a 90 kHz clock; None with under two frames."""
        if self.frames < 2:
            return None
        wall = self.last_arrival - self.first_arrival
        media = self.media_ticks / CLOCK_HZ
        line = "%.2f s of timestamps in %.2f s of arrivals" % (media, wall)
        if wall < self.CLOCK_CHECK_AFTER_S:
            return line + " (too short a stream to judge the 90 kHz clock)"
        if abs(media / wall - 1) > self.CLOCK_TOLERANCE:
            return line + ": NOT a 90 kHz clock"
        return line + " (a 90 kHz clock)"

    @property
    def clock_wrong(self):
        line = self.clock_line()
        return line is not None and "NOT" in line

    def total_line(self, sink):
        if not self.frames:
            return "no video frame"
        seconds = (self.last_arrival - self.first_arrival) if self.frames > 1 else 0
        rate = "%.1f fps" % ((self.frames - 1) / seconds) if seconds > 0 else "one frame"
        return "%d frames (%d key), %s, %d/%d/%d bytes a frame (least/mean/most); %d packets, %d lost, %d late, %d damaged frames, %d faults" % (
            self.frames, self.keyframes, rate, self.smallest, self.bytes // self.frames, self.largest,
            sink.packets, sink.lost, sink.late, sink.damaged, sink.fault_count)


class Packetizer:
    """The phone's side, for the self-test and the tests: one NAL as RTP packets, as the app's RtpPacketizer makes them (FU-A over
    [MAX_PAYLOAD] bytes, the marker on the last packet, a 90 kHz timestamp from a clock in milliseconds)."""

    def __init__(self, seq=0, ssrc=0x52456175, ts_base=0, payload_type=protocolo.RTP_PAYLOAD_TYPE):
        self.seq = seq % SEQ_MOD
        self.ssrc = ssrc
        self.ts_base = ts_base
        self.payload_type = payload_type

    def packetize(self, nal, clock_ms, end_of_frame=True):
        ts = (self.ts_base + clock_ms * (CLOCK_HZ // 1000)) % TS_MOD
        if len(nal) <= MAX_PAYLOAD:
            return [self._packet(nal, end_of_frame, ts)]
        out = []
        indicator = (nal[0] & 0xE0) | FU_A
        nal_type = nal[0] & 0x1F
        at = 1
        while at < len(nal):
            chunk = nal[at:at + MAX_PAYLOAD - 2]
            last = at + len(chunk) >= len(nal)
            header = (0x80 if at == 1 else 0) | (0x40 if last else 0) | nal_type
            out.append(self._packet(bytes([indicator, header]) + chunk, last and end_of_frame, ts))
            at += len(chunk)
        return out

    def _packet(self, payload, marker, ts):
        head = bytes([0x80, (0x80 if marker else 0) | self.payload_type]) + self.seq.to_bytes(2, "big") + ts.to_bytes(4, "big") + self.ssrc.to_bytes(4, "big")
        self.seq = (self.seq + 1) % SEQ_MOD
        return head + payload
