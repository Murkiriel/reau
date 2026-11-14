"""K1G control packets, as REau's app builds and reads them (K1GPacket.kt, DashDump.kt).

One layout for both directions: outer length (2 bytes, the whole packet), segment count (2), four zero bytes, then the segments, each
`type, sub, length (2), value`. The first segment is the header, `02 01` with the value "K1G " and the rolling sequence byte; the app's
fake dash answers with packets built the same way, and the app's parser reads the header as one more TLV.
"""
from typing import NamedTuple

import protocolo

MAGIC = b"K1G "
SEGMENTS_AT = 8
HEADER = (0x02, 0x01)
_FIXED = bytes(4) + bytes([HEADER[0], HEADER[1], 0x00, len(MAGIC) + 1]) + MAGIC


class Tlv(NamedTuple):
    type: int
    sub: int
    value: bytes = b""

    @property
    def key(self):
        return (self.type, self.sub)

    @property
    def name(self):
        return "%02X%02X" % (self.type, self.sub)


class Packet(NamedTuple):
    """What [parse] read: the TLVs after the header, the header's sequence byte (None with no header), what is wrong with the packet, and
    the segment count it declares."""
    tlvs: tuple
    seq: object
    problems: tuple
    declared: int = 0


def tlv(type_, sub, *values):
    """A TLV whose value is the given bytes: integers one byte each, or one `bytes`."""
    if len(values) == 1 and isinstance(values[0], (bytes, bytearray)):
        return Tlv(type_, sub, bytes(values[0]))
    return Tlv(type_, sub, bytes(v & 0xFF for v in values))


def build(*tlvs, seq=0):
    """A whole packet with the header and [tlvs], the sequence byte set to [seq]."""
    body = bytearray()
    for t in tlvs:
        if len(t.value) > 0xFFFF:
            raise ValueError("a TLV value takes at most 65535 bytes")
        body += bytes([t.type & 0xFF, t.sub & 0xFF]) + len(t.value).to_bytes(2, "big") + t.value
    segments = 1 + len(tlvs)
    size = 4 + len(_FIXED) + 1 + len(body)
    if size > 0xFFFF:
        raise ValueError("a packet takes at most 65535 bytes")
    return size.to_bytes(2, "big") + segments.to_bytes(2, "big") + _FIXED + bytes([seq & 0xFF]) + bytes(body)


def patch_seq(packet, seq):
    """[packet] with its sequence byte (the one after "K1G ") set and its outer length made right, as the app's socket does at each send."""
    out = bytearray(packet)
    at = out.find(MAGIC)
    if at >= 0 and at + len(MAGIC) < len(out):
        out[at + len(MAGIC)] = seq & 0xFF
    out[0:2] = len(out).to_bytes(2, "big")
    return bytes(out)


def parse(data):
    """Reads a packet of either side, to its last byte. A TLV the packet ends inside is left out, and said in the problems, as the app's
    parser does.

    The segment count is not what ends the reading, and a count that differs from the segments is not a problem: two packets the app sends
    as they were captured (the status at the end of the first burst and the heartbeat) declare one segment fewer than they carry, and a
    reader that stopped at the count would never see their last field."""
    problems = []
    if len(data) < SEGMENTS_AT:
        return Packet((), None, ("%d bytes, too short for a K1G packet" % len(data),))
    outer = int.from_bytes(data[0:2], "big")
    if outer != len(data):
        problems.append("outer length says %d bytes, the packet has %d" % (outer, len(data)))
    segments = int.from_bytes(data[2:4], "big")
    tlvs = []
    at = SEGMENTS_AT
    cut = False
    while at + 4 <= len(data):
        length = int.from_bytes(data[at + 2:at + 4], "big")
        if at + 4 + length > len(data):
            cut = True
            break
        tlvs.append(Tlv(data[at], data[at + 1], bytes(data[at + 4:at + 4 + length])))
        at += 4 + length
    if cut or at < len(data):
        problems.append("the packet ends inside a TLV (%d whole segments read, %d declared)" % (len(tlvs), segments))
    seq = None
    if tlvs and tlvs[0].key == HEADER and tlvs[0].value[:len(MAGIC)] == MAGIC and len(tlvs[0].value) == len(MAGIC) + 1:
        seq = tlvs[0].value[len(MAGIC)]
        tlvs = tlvs[1:]
    else:
        problems.append('no "K1G " header segment')
    return Packet(tuple(tlvs), seq, tuple(problems), segments)


def describe(tlvs, show_text=False):
    """The TLVs as `TTSS:hex`, one after another. The texts and the key exchange are left out with their size kept, unless [show_text]."""
    parts = []
    for t in tlvs:
        if t.key in protocolo.LEFT_OUT and not show_text:
            parts.append("%s:<%dB left out>" % (t.name, len(t.value)))
        else:
            parts.append("%s:%s" % (t.name, t.value.hex().upper()))
    return " ".join(parts)
