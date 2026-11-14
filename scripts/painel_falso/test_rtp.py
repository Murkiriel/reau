"""Tests for rtp.py: the video put back together from its RTP packets, and what a lost, late or foreign packet does to it.

Run from this folder:  python3 -B -m unittest discover -p "test_*.py"
"""
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import rtp  # noqa: E402

START = rtp.START_CODE


def nal(header, size, fill=0):
    return bytes([header]) + bytes((fill + i) & 0xFF for i in range(size - 1))


KEYFRAME = nal(0x67, 4000, 1)   # three FU-A pieces
SMALL = nal(0x41, 500, 2)       # one packet
BIG = nal(0x41, 2900, 3)        # three FU-A pieces


def feed(sink, packets):
    return [frame for frame in (sink.on_packet(packet) for packet in packets) if frame is not None]


class Stream:
    """A sink and a packetizer with a clock that goes 250 ms a frame."""

    def __init__(self, **options):
        self.sink = rtp.RtpSink()
        self.packetizer = rtp.Packetizer(**options)
        self.clock_ms = 0

    def packets(self, unit):
        self.clock_ms += 250
        return self.packetizer.packetize(unit, self.clock_ms)

    def send(self, unit):
        return feed(self.sink, self.packets(unit))


class PacketizerTest(unittest.TestCase):
    def test_a_small_nal_is_one_packet_with_the_marker(self):
        (packet,) = rtp.Packetizer(seq=7, ssrc=0x01020304, ts_base=10).packetize(SMALL, 1000)
        self.assertEqual(bytes([0x80, 0x80 | 96, 0, 7]) + (10 + 90_000).to_bytes(4, "big") + bytes([1, 2, 3, 4]), packet[:12])
        self.assertEqual(SMALL, packet[12:])

    def test_a_nal_of_exactly_the_limit_is_still_one_packet_and_one_byte_more_is_two_pieces(self):
        self.assertEqual(1, len(rtp.Packetizer().packetize(nal(0x41, 1380), 0)))
        pieces = rtp.Packetizer().packetize(nal(0x41, 1381), 0)
        self.assertEqual([12 + 1380, 12 + 2 + 2], [len(piece) for piece in pieces])

    def test_a_big_nal_goes_as_fu_a_pieces_with_start_end_and_the_marker_on_the_last(self):
        pieces = rtp.Packetizer().packetize(KEYFRAME, 0)
        self.assertEqual(3, len(pieces))
        self.assertTrue(all(len(piece) - 12 <= rtp.MAX_PAYLOAD for piece in pieces))
        self.assertEqual([0x60 | 28] * 3, [piece[12] for piece in pieces])
        self.assertEqual([0x80 | 7, 7, 0x40 | 7], [piece[13] for piece in pieces])
        self.assertEqual([0, 0, 0x80], [piece[1] & 0x80 for piece in pieces])
        self.assertEqual(KEYFRAME[1:], b"".join(piece[14:] for piece in pieces))

    def test_the_timestamp_is_90_ticks_a_millisecond_and_wraps(self):
        (packet,) = rtp.Packetizer(ts_base=0xFFFFFFFF).packetize(SMALL, 1)
        self.assertEqual(89, int.from_bytes(packet[4:8], "big"))


class WholeStreamTest(unittest.TestCase):
    def test_frames_come_back_as_annex_b_byte_for_byte(self):
        stream = Stream(seq=0xFFFE)  # the sequence wraps at 65536 inside the first frame
        frames = stream.send(KEYFRAME) + stream.send(SMALL) + stream.send(BIG)
        self.assertEqual([START + KEYFRAME, START + SMALL, START + BIG], [frame.data for frame in frames])
        self.assertEqual([True, False, False], [frame.key for frame in frames])
        self.assertEqual([3, 1, 3], [frame.packets for frame in frames])
        self.assertEqual([], stream.sink.faults)
        self.assertEqual((3, 0, 0, 0), (stream.sink.complete, stream.sink.damaged, stream.sink.lost, stream.sink.late))
        self.assertEqual(START + KEYFRAME + START + SMALL + START + BIG, stream.sink.annex_b())
        self.assertFalse(stream.sink.ended_inside_a_frame)

    def test_an_idr_slice_is_a_keyframe_too_and_a_plain_slice_is_not(self):
        stream = Stream()
        self.assertEqual([True, False, True], [stream.send(unit)[0].key for unit in (nal(0x65, 100), nal(0x41, 100), nal(0x65, 3000))])

    def test_two_nals_of_one_frame_share_its_timestamp_and_the_marker_ends_it(self):
        sink = rtp.RtpSink()
        packetizer = rtp.Packetizer()
        packets = packetizer.packetize(nal(0x67, 20), 100, end_of_frame=False) + packetizer.packetize(nal(0x65, 2000), 100)
        (frame,) = feed(sink, packets)
        self.assertEqual(START + nal(0x67, 20) + START + nal(0x65, 2000), frame.data)
        self.assertEqual([], sink.faults)

    def test_a_stream_cut_after_a_first_piece_ended_inside_a_frame(self):
        stream = Stream()
        feed(stream.sink, stream.packets(KEYFRAME)[:1])
        self.assertTrue(stream.sink.ended_inside_a_frame)
        stream.sink.restart()
        self.assertEqual(1, stream.sink.damaged)
        self.assertFalse(stream.sink.ended_inside_a_frame)


class LossTest(unittest.TestCase):
    def test_a_lost_middle_piece_damages_that_frame_only(self):
        stream = Stream()
        stream.send(SMALL)
        pieces = stream.packets(KEYFRAME)
        (damaged,) = feed(stream.sink, [pieces[0], pieces[2]])
        (after,) = stream.send(SMALL)
        self.assertTrue(damaged.damaged)
        self.assertFalse(after.damaged)
        self.assertEqual((1, 1, 2), (stream.sink.lost, stream.sink.damaged, stream.sink.complete))
        self.assertEqual(["packet 3: sequence 3 after 1 (1 lost)"], stream.sink.faults)
        self.assertEqual(START + SMALL + START + SMALL, stream.sink.annex_b())

    def test_a_lost_first_piece_damages_the_frame(self):
        stream = Stream()
        stream.send(SMALL)
        (damaged,) = feed(stream.sink, stream.packets(KEYFRAME)[1:])
        self.assertTrue(damaged.damaged)
        self.assertEqual(1, stream.sink.lost)
        self.assertIn("packet 2: a piece of a NAL whose first piece did not come", stream.sink.faults)

    def test_a_lost_last_piece_ends_the_frame_at_the_next_ones_timestamp(self):
        stream = Stream()
        feed(stream.sink, stream.packets(KEYFRAME)[:2])
        (after,) = stream.send(SMALL)
        self.assertFalse(after.damaged)
        self.assertEqual(START + SMALL, after.data)
        self.assertEqual((1, 1, 1), (stream.sink.lost, stream.sink.damaged, stream.sink.complete))
        self.assertTrue(any("a frame ended without its marker?" in fault for fault in stream.sink.faults))

    def test_a_whole_frame_lost_between_two_is_counted_and_damages_none(self):
        stream = Stream()
        stream.send(SMALL)
        stream.packets(BIG)  # three packets that never arrive
        (after,) = stream.send(SMALL)
        self.assertFalse(after.damaged)
        self.assertEqual((3, 0, 2), (stream.sink.lost, stream.sink.damaged, stream.sink.complete))

    def test_packets_out_of_order_the_late_one_is_dropped_and_the_frame_is_damaged(self):
        stream = Stream()
        first, second, third = stream.packets(KEYFRAME)
        (damaged,) = feed(stream.sink, [first, third, second])
        self.assertTrue(damaged.damaged)
        self.assertEqual((1, 1), (stream.sink.lost, stream.sink.late))
        self.assertEqual("packet 3: sequence 1 after 2 (late or repeated), dropped", stream.sink.faults[-1])
        (after,) = stream.send(SMALL)
        self.assertFalse(after.damaged)
        self.assertEqual(2, len(stream.sink.faults))

    def test_a_packet_that_comes_twice_is_dropped_and_harms_nothing(self):
        stream = Stream()
        first, second, third = stream.packets(KEYFRAME)
        (frame,) = feed(stream.sink, [first, second, second, third])
        self.assertFalse(frame.damaged)
        self.assertEqual(START + KEYFRAME, frame.data)
        self.assertEqual((0, 1), (stream.sink.lost, stream.sink.late))

    def test_a_gap_across_the_sequence_wrap_is_a_loss_and_not_a_late_packet(self):
        stream = Stream(seq=0xFFFF)
        stream.send(SMALL)
        stream.packets(SMALL)
        stream.send(SMALL)
        self.assertEqual((1, 0), (stream.sink.lost, stream.sink.late))


class SourceTest(unittest.TestCase):
    def test_a_packet_of_another_source_is_dropped_and_the_frame_stays_whole(self):
        stream = Stream()
        first, second, third = stream.packets(KEYFRAME)
        stranger = rtp.Packetizer(seq=40, ssrc=0x0BADF00D).packetize(SMALL, 5)[0]
        (frame,) = feed(stream.sink, [first, stranger, second, third])
        self.assertFalse(frame.damaged)
        self.assertEqual(START + KEYFRAME, frame.data)
        self.assertEqual(["packet 2: source id 0BADF00D in a stream of source id 52456175, dropped"], stream.sink.faults)
        self.assertEqual((0, 0), (stream.sink.lost, stream.sink.late))

    def test_four_strangers_are_dropped_and_the_fifth_in_a_row_starts_the_stream_again(self):
        stream = Stream()
        stream.send(SMALL)
        other = rtp.Packetizer(seq=900, ssrc=0x0BADF00D)
        frames = [feed(stream.sink, other.packetize(SMALL, 250 * i)) for i in range(1, 7)]
        self.assertEqual([0, 0, 0, 0, 1, 1], [len(f) for f in frames])
        self.assertIn("5 packets in a row of source id 0BADF00D: the stream starts again with it", stream.sink.faults[4])
        self.assertEqual(5, len(stream.sink.faults))
        self.assertEqual((0, 3), (stream.sink.lost, stream.sink.complete))

    def test_strangers_that_are_not_in_a_row_never_take_the_stream(self):
        stream = Stream()
        other = rtp.Packetizer(seq=900, ssrc=0x0BADF00D)
        stream.send(SMALL)
        for i in range(8):
            feed(stream.sink, other.packetize(SMALL, i))
            if i % 4 == 3:
                stream.send(SMALL)
        stream.send(SMALL)
        self.assertEqual(4, stream.sink.complete)
        self.assertEqual(8, stream.sink.fault_count)


class RuleTest(unittest.TestCase):
    def sink_with(self, *packets):
        sink = rtp.RtpSink()
        frames = feed(sink, packets)
        return sink, frames

    def packet(self, payload, seq=0, ts=1000, marker=True, version=0x80, payload_type=96):
        return bytes([version, (0x80 if marker else 0) | payload_type]) + seq.to_bytes(2, "big") + ts.to_bytes(4, "big") + bytes(4) + payload

    def test_too_short_a_packet(self):
        sink, frames = self.sink_with(bytes(12))
        self.assertEqual((["packet 1: 12 bytes, too short for an RTP header and a payload"], []), (sink.faults, frames))

    def test_the_version_and_the_payload_type(self):
        sink, frames = self.sink_with(self.packet(SMALL, version=0x40), self.packet(SMALL, seq=1, ts=2000, payload_type=97))
        self.assertEqual(["packet 1: RTP version 1, not 2", "packet 2: payload type 97, the stream began with 96"], sink.faults)
        self.assertEqual([False, False], [frame.damaged for frame in frames])

    def test_a_payload_over_the_limit(self):
        sink, _ = self.sink_with(self.packet(nal(0x41, 1381)))
        self.assertEqual(["packet 1: a payload of 1381 bytes, over 1380"], sink.faults)
        sink, _ = self.sink_with(self.packet(nal(0x41, 1380)))
        self.assertEqual([], sink.faults)

    def test_a_new_frame_with_the_old_timestamp_or_an_older_one(self):
        sink, _ = self.sink_with(self.packet(SMALL), self.packet(SMALL, seq=1))
        self.assertEqual(["packet 2: a new frame with the timestamp of the one before"], sink.faults)
        sink, _ = self.sink_with(self.packet(SMALL), self.packet(SMALL, seq=1, ts=999))
        self.assertEqual(["packet 2: timestamp 999, before the frame before (1000)"], sink.faults)
        sink, _ = self.sink_with(self.packet(SMALL, ts=0xFFFFFF00), self.packet(SMALL, seq=1, ts=5))  # the timestamp wraps: not older
        self.assertEqual([], sink.faults)

    def test_an_aggregation_packet_and_a_type_the_stream_may_not_carry(self):
        sink, frames = self.sink_with(self.packet(bytes([24]) + bytes(10)), self.packet(bytes([29]) + bytes(10), seq=1, ts=2000),
                                      self.packet(bytes([0]) + bytes(10), seq=2, ts=3000), self.packet(bytes([27]) + bytes(10), seq=3, ts=4000))
        self.assertEqual(["packet 1: an aggregation packet (type 24), which the dash refuses", "packet 2: NAL type 29 is not one the stream may carry",
                          "packet 3: NAL type 0 is not one the stream may carry", "packet 4: an aggregation packet (type 27), which the dash refuses"], sink.faults)
        self.assertEqual([True] * 4, [frame.damaged for frame in frames])

    def test_the_marker_in_the_middle_of_a_nal_in_pieces(self):
        first = rtp.Packetizer().packetize(KEYFRAME, 0)[0]
        sink, frames = self.sink_with(first[:1] + bytes([first[1] | 0x80]) + first[2:])
        self.assertEqual(["packet 1: the marker on a piece that is not the last of its NAL"], sink.faults)
        self.assertTrue(frames[0].damaged)

    def test_pieces_that_do_not_fit_together(self):
        fu = bytes([0x60 | 28])
        sink, _ = self.sink_with(self.packet(fu + bytes([0x80 | 5]) + b"a", marker=False), self.packet(fu + bytes([0x40 | 1]) + b"b", seq=1))
        self.assertEqual(["packet 2: a piece of NAL type 1 inside a NAL of type 5"], sink.faults)
        sink, _ = self.sink_with(self.packet(fu + bytes([0x80 | 5]) + b"a", marker=False), self.packet(fu + bytes([0x80 | 5]) + b"b", seq=1, marker=False),
                                 self.packet(nal(0x41, 10), seq=2))
        self.assertEqual(["packet 2: a first piece before the last piece of the NAL before", "packet 3: a whole NAL in the middle of a NAL that came in pieces"],
                         sink.faults)
        sink, _ = self.sink_with(self.packet(fu + bytes([0xC0 | 5]) + b"a"), self.packet(fu + bytes([0xA0 | 5]) + b"a", seq=1, ts=2000, marker=False),
                                 self.packet(fu + bytes([0x40 | 5]), seq=2, ts=2000))
        self.assertEqual(["packet 1: an FU-A piece that is both the first and the last", "packet 2: the reserved bit of the FU header is set",
                          "packet 3: an FU-A piece with no bytes of the NAL", "packet 3: the marker on a piece that is not the last of its NAL"], sink.faults)

    def test_only_so_many_faults_are_kept_and_all_are_counted(self):
        sink = rtp.RtpSink()
        for _ in range(rtp.FAULTS_KEPT + 10):
            sink.on_packet(bytes(3))
        self.assertEqual((rtp.FAULTS_KEPT, rtp.FAULTS_KEPT + 10), (len(sink.faults), sink.fault_count))


class StatsTest(unittest.TestCase):
    def frames(self, count, step_ms, ticks_per_ms=90, size=1000):
        return [rtp.Frame(bytes(size + i), (i * step_ms * ticks_per_ms) % rtp.TS_MOD, i == 0, False, 1, 10.0 + i * step_ms / 1000) for i in range(count)]

    def test_the_window_line_says_the_rate_and_the_sizes_and_starts_over(self):
        stats = rtp.StreamStats()
        for frame in self.frames(8, 250):
            stats.add(frame)
        sink = rtp.RtpSink()
        sink.lost, sink.late, sink.damaged = 3, 1, 2
        self.assertEqual("4.0 fps, 32 kbit/s, 8 frames (1 key), 1000/1003/1007 bytes a frame (least/mean/most); so far 3 lost, 1 late, 2 damaged frames",
                         stats.window_line(12.0, sink))
        self.assertIsNone(stats.window_line(13.0, sink))
        stats.add(rtp.Frame(bytes(500), 0, False, False, 1, 13.5))
        self.assertEqual("1.0 fps, 4 kbit/s, 1 frames (0 key), 500/500/500 bytes a frame (least/mean/most)", stats.window_line(14.0))

    def test_the_whole_stream_in_one_line(self):
        stats = rtp.StreamStats()
        sink = rtp.RtpSink()
        self.assertEqual("no video frame", stats.total_line(sink))
        for frame in self.frames(9, 250):
            stats.add(frame)
        sink.packets = 20
        self.assertEqual("9 frames (1 key), 4.0 fps, 1000/1004/1008 bytes a frame (least/mean/most); 20 packets, 0 lost, 0 late, 0 damaged frames, 0 faults",
                         stats.total_line(sink))

    def test_timestamps_that_follow_the_clock_are_a_90_khz_clock(self):
        stats = rtp.StreamStats()
        self.assertIsNone(stats.clock_line())
        for frame in self.frames(41, 250):
            stats.add(frame)
        self.assertEqual("10.00 s of timestamps in 10.00 s of arrivals (a 90 kHz clock)", stats.clock_line())
        self.assertFalse(stats.clock_wrong)

    def test_timestamps_in_milliseconds_are_not(self):
        stats = rtp.StreamStats()
        for frame in self.frames(41, 250, ticks_per_ms=1):
            stats.add(frame)
        self.assertEqual("0.11 s of timestamps in 10.00 s of arrivals: NOT a 90 kHz clock", stats.clock_line())
        self.assertTrue(stats.clock_wrong)

    def test_a_clock_under_a_tenth_off_is_still_taken_and_one_over_a_tenth_off_is_not(self):
        for ticks, wrong in ((98, False), (82, False), (100, True), (80, True)):
            stats = rtp.StreamStats()
            for frame in self.frames(41, 250, ticks_per_ms=ticks):
                stats.add(frame)
            self.assertEqual(wrong, stats.clock_wrong, ticks)

    def test_a_short_stream_is_not_judged(self):
        stats = rtp.StreamStats()
        for frame in self.frames(20, 250, ticks_per_ms=1):
            stats.add(frame)
        self.assertEqual("0.05 s of timestamps in 4.75 s of arrivals (too short a stream to judge the 90 kHz clock)", stats.clock_line())
        self.assertFalse(stats.clock_wrong)

    def test_the_timestamps_may_wrap(self):
        stats = rtp.StreamStats()
        for i in range(41):
            stats.add(rtp.Frame(b"x", (0xFFFFFF00 + i * 22500) % rtp.TS_MOD, False, False, 1, i * 0.25))
        self.assertFalse(stats.clock_wrong)
        self.assertIn("10.00 s of timestamps", stats.clock_line())


if __name__ == "__main__":
    unittest.main()
