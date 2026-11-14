"""Tests for k1g.py: the packets of the dash's control protocol, built and read as the app builds and reads them.

Run from this folder:  python3 -B -m unittest discover -p "test_*.py"
"""
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import k1g  # noqa: E402
import protocolo as p  # noqa: E402


class BuildTest(unittest.TestCase):
    def test_a_built_packet_is_the_apps_own_bytes(self):
        self.assertEqual(bytes.fromhex(p.AUTH_REQUEST), k1g.build(k1g.tlv(0x08, 0x04, 0x01)))
        self.assertEqual(bytes.fromhex(p.PROJECTION_STOP), k1g.build(k1g.tlv(0x05, 0x56, 0xAA)))
        self.assertEqual(bytes.fromhex(p.FRAME_DECODED_IDR), k1g.build(k1g.tlv(0x06, 0x11, 0x55)))

    def test_the_lengths_count_the_whole_packet_and_the_header_segment(self):
        packet = k1g.build(k1g.tlv(0x07, 0x00, bytes(128)), k1g.tlv(0x07, 0x03, bytes([1, 0, 1])), seq=0x2A)
        self.assertEqual(len(packet), int.from_bytes(packet[0:2], "big"))
        self.assertEqual(3, int.from_bytes(packet[2:4], "big"))
        self.assertEqual(b"K1G ", packet[12:16])
        self.assertEqual(0x2A, packet[16])

    def test_the_sequence_byte_is_patched_after_the_magic(self):
        packet = k1g.patch_seq(bytes.fromhex(p.PROJECTION_FRAME), 0x1FF)
        self.assertEqual(0xFF, packet[16])
        self.assertEqual(bytes.fromhex(p.PROJECTION_FRAME)[17:], packet[17:])
        self.assertEqual(0xFF, k1g.parse(packet).seq)

    def test_a_value_may_be_bytes_or_single_byte_integers(self):
        self.assertEqual(k1g.Tlv(0x09, 0x00, b"\x00\x14"), k1g.tlv(0x09, 0x00, 0x00, 0x14))
        self.assertEqual(k1g.Tlv(0x09, 0x00, b"\x14"), k1g.tlv(0x09, 0x00, b"\x14"))
        self.assertEqual(k1g.Tlv(0x0A, 0x02), k1g.tlv(0x0A, 0x02))

    def test_a_value_too_long_for_its_length_field_is_refused(self):
        with self.assertRaises(ValueError):
            k1g.build(k1g.tlv(0x05, 0x40, bytes(0x10000)))


class ParseTest(unittest.TestCase):
    def test_what_is_built_is_read_back(self):
        tlvs = (k1g.tlv(0x05, 0x56, 0x55), k1g.tlv(0x06, 0x0B, b"REau\x00"), k1g.tlv(0x0A, 0x02))
        packet = k1g.parse(k1g.build(*tlvs, seq=9))
        self.assertEqual(tlvs, packet.tlvs)
        self.assertEqual(9, packet.seq)
        self.assertEqual((), packet.problems)

    def test_the_apps_heartbeat_is_read_to_its_last_field_though_it_declares_one_segment_fewer(self):
        heartbeat = bytes.fromhex("0049000b00000000020100054b3147200006080001050610000139060300015506040001a2060f0001aa"
                                  "0601000101054c000113052d00020000051b0001190521000132054d000132")
        packet = k1g.parse(heartbeat)
        self.assertEqual((), packet.problems)
        self.assertEqual(["0608", "0610", "0603", "0604", "060F", "0601", "054C", "052D", "051B", "0521", "054D"], [t.name for t in packet.tlvs])
        self.assertEqual(11, packet.declared)  # the header and eleven fields are twelve segments
        self.assertTrue(all(t.key in p.PHONE_TLVS for t in packet.tlvs))

    def test_a_packet_that_ends_inside_a_tlv_keeps_the_whole_ones_and_says_so(self):
        whole = k1g.build(k1g.tlv(0x05, 0x56, 0x55), k1g.tlv(0x08, 0x00, bytes(128)))
        packet = k1g.parse(whole[:-10])
        self.assertEqual((k1g.tlv(0x05, 0x56, 0x55),), packet.tlvs)
        self.assertTrue(any("ends inside a TLV" in problem for problem in packet.problems), packet.problems)
        self.assertTrue(any("outer length" in problem for problem in packet.problems), packet.problems)
        # Cut inside the TLV's own four-byte header, too.
        self.assertTrue(any("ends inside a TLV" in problem for problem in k1g.parse(whole[:24]).problems))

    def test_a_wrong_outer_length_and_stray_bytes_at_the_end_are_problems(self):
        good = k1g.build(k1g.tlv(0x05, 0x56, 0x55))
        self.assertEqual(2, k1g.parse(good).declared)
        self.assertTrue(any("outer length says 23" in problem for problem in k1g.parse(b"\x00\x17" + good[2:]).problems))
        longer = k1g.patch_seq(good + b"\x00", 0)
        self.assertEqual(["the packet ends inside a TLV (2 whole segments read, 2 declared)"], list(k1g.parse(longer).problems))

    def test_a_packet_with_no_header_or_too_short_is_a_problem(self):
        self.assertEqual(None, k1g.parse(b"\x00\x05\x00\x01\x00").seq)
        self.assertIn("too short", k1g.parse(b"\x00\x05\x00\x01\x00").problems[0])
        bare = b"\x00\x0d\x00\x01\x00\x00\x00\x00" + bytes([0x05, 0x56, 0x00, 0x01, 0x55])
        packet = k1g.parse(bare)
        self.assertEqual((k1g.tlv(0x05, 0x56, 0x55),), packet.tlvs)
        self.assertEqual(None, packet.seq)
        self.assertTrue(any("header" in problem for problem in packet.problems))

    def test_garbage_never_raises(self):
        for size in range(0, 40):
            k1g.parse(bytes((31 * i + size) & 0xFF for i in range(size)))


class DescribeTest(unittest.TestCase):
    def test_the_texts_and_the_key_are_left_out_with_their_size(self):
        tlvs = (k1g.tlv(0x05, 0x56, 0x55), k1g.tlv(0x05, 0x01, b"Some Street\x00"), k1g.tlv(0x08, 0x00, bytes(128)),
                k1g.tlv(0x05, 0x0D, b"a\x00b\x00c"), k1g.tlv(0x05, 0x22, bytes(32)), k1g.tlv(0x05, 0x40, bytes(10)))
        self.assertEqual("0556:55 0501:<12B left out> 0800:<128B left out> 050D:<5B left out> 0522:<32B left out> 0540:<10B left out>", k1g.describe(tlvs))

    def test_asked_for_they_are_written(self):
        self.assertEqual("0501:414200", k1g.describe((k1g.tlv(0x05, 0x01, b"AB\x00"),), show_text=True))


if __name__ == "__main__":
    unittest.main()
