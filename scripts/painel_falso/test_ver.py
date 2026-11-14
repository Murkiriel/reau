"""Tests for --ver of painel_falso.py: the video shown live in a viewer (ffplay) while it is written. The viewer here is made up: a
process that keeps what its standard input is given, breaks its pipe or stops reading when a test asks. No window is opened and no ffplay runs.

Run from this folder:  python3 -B -m unittest discover -p "test_*.py"
"""
import os
import subprocess
import sys
import tempfile
import threading
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import log_painel  # noqa: E402
import painel_falso  # noqa: E402
import rtp  # noqa: E402
from test_selftest import Loopback, until  # noqa: E402

LOOPBACK = "127.0.0.1"
FFPLAY = "/usr/bin/ffplay"
SPS, IDR, P1, P2, P3 = (bytes([header]) + bytes([fill]) * size for header, fill, size in ((0x67, 1, 20), (0x65, 2, 900), (0x41, 3, 300), (0x41, 4, 310), (0x41, 5, 320)))


def annex_b(*nals):
    return b"".join(rtp.START_CODE + nal for nal in nals)


class FakeStdin:
    def __init__(self, viewer):
        self.viewer = viewer
        self.closed = False

    def write(self, data):
        viewer = self.viewer
        viewer.writing.set()
        if viewer.stuck is not None:
            viewer.stuck.wait(10.0)
        if self.closed:
            raise ValueError("write to closed file")
        if viewer.stopped or (viewer.breaks_after is not None and len(viewer.chunks) >= viewer.breaks_after):
            raise BrokenPipeError(32, "Broken pipe")
        viewer.chunks.append(bytes(data))
        return len(data)

    def close(self):
        self.closed = True


class FakeViewer:
    """What subprocess.Popen gives, for a viewer that never shows anything."""

    def __init__(self, command, breaks_after=None, stuck=False, **options):
        self.command = command
        self.options = options
        self.breaks_after = breaks_after
        self.stuck = threading.Event() if stuck else None
        self.chunks = []
        self.writing = threading.Event()   # set once something was handed to its standard input
        self.stopped = False
        self.terminated = self.killed = 0
        self.returncode = None
        self.stdin = FakeStdin(self)

    @property
    def got(self):
        return b"".join(self.chunks)

    def poll(self):
        return self.returncode

    def wait(self, timeout=None):
        if self.stdin.closed or self.stopped:
            self.returncode = 0 if not self.stopped else -15
            return self.returncode
        raise subprocess.TimeoutExpired(self.command, timeout)

    def terminate(self):
        self.terminated += 1
        self.stopped = True
        if self.stuck is not None:
            self.stuck.set()

    def kill(self):
        self.killed += 1
        self.terminate()


class Viewers:
    """Stands in for subprocess.Popen and keeps each viewer it started."""

    def __init__(self, error=None, **behaviour):
        self.error = error
        self.behaviour = behaviour
        self.started = []

    def __call__(self, command, **options):
        if self.error:
            raise self.error
        self.started.append(FakeViewer(command, **dict(self.behaviour, **options)))
        return self.started[-1]


def feed(server, packetizer, nal, clock_ms, drop=()):
    """One frame to the panel as RTP packets; [drop] are the indexes of the packets lost on the way."""
    for i, packet in enumerate(packetizer.packetize(nal, clock_ms)):
        if i not in drop:
            server.panel.on_rtp(packet, LOOPBACK)


class Bench:
    """A panel server with --ver and a made-up viewer, fed packet by packet with no thread of its own."""

    def __init__(self, folder, viewers, find=lambda name: FFPLAY, **options):
        self.log = log_painel.Log(quiet=True, keep=True)
        self.server = painel_falso.PanelServer(self.log, bind=LOOPBACK, ctrl_port=0, rtp_port=0, out_dir=folder, view=True, popen=viewers,
                                               find=find, **options)
        self.server.panel.session_key = bytes(32)   # as after the key exchange: video before it is a protocol error of its own
        self.packetizer = rtp.Packetizer()
        self.clock_ms = 0

    def frame(self, nal, drop=()):
        self.clock_ms += 33
        feed(self.server, self.packetizer, nal, self.clock_ms, drop)

    def end_session(self):
        self.server.panel._end_session("the test ends it")

    def written(self, n=0):
        with open(self.server.panel.videos[n], "rb") as f:
            return f.read()

    def said(self, text):
        return [line for line in self.log.lines if text in line]


class ViewerCommandTest(unittest.TestCase):
    def test_ffplay_reads_raw_h264_from_its_standard_input_with_no_buffering(self):
        command = painel_falso.viewer_command("a title", FFPLAY)
        self.assertEqual(FFPLAY, command[0])
        self.assertEqual(["-f", "h264", "-i", "-"], command[-4:])
        text = " ".join(command)
        for options in ("-fflags nobuffer", "-flags low_delay", "-framedrop", "-autoexit"):
            self.assertIn(options, text)
        self.assertEqual("a title", command[command.index("-window_title") + 1])
        # The format has to come before the input it describes.
        self.assertLess(command.index("-f"), command.index("-i"))

    def test_ver_is_off_unless_asked(self):
        self.assertFalse(painel_falso.arguments([]).ver)
        self.assertTrue(painel_falso.arguments(["--ver"]).ver)


class LiveVideoTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.bench = None
        self.addCleanup(lambda: self.bench and self.bench.server.close())

    def test_each_frame_written_also_goes_to_the_viewer_in_order(self):
        viewers = Viewers()
        self.bench = Bench(self.folder.name, viewers)
        for nal in (SPS, IDR, P1, P2):
            self.bench.frame(nal)
        (viewer,) = viewers.started
        self.assertTrue(until(lambda: viewer.got == annex_b(SPS, IDR, P1, P2)))
        self.assertEqual(painel_falso.viewer_command("REau bench panel - session 1", FFPLAY), viewer.command)
        self.assertEqual(subprocess.PIPE, viewer.options["stdin"])
        self.bench.end_session()
        self.assertEqual(annex_b(SPS, IDR, P1, P2), self.bench.written())
        self.assertEqual(viewer.got, self.bench.written())
        self.assertEqual(0, self.bench.log.errors)

    def test_the_viewer_gets_the_end_of_the_stream_when_the_session_ends_and_is_left_to_go_by_itself(self):
        viewers = Viewers()
        self.bench = Bench(self.folder.name, viewers)
        self.bench.frame(SPS)
        self.bench.end_session()
        (viewer,) = viewers.started
        self.assertTrue(viewer.stdin.closed)
        self.assertEqual((0, 0, 0), (viewer.returncode, viewer.terminated, viewer.killed))

    def test_each_session_has_a_viewer_of_its_own(self):
        viewers = Viewers()
        self.bench = Bench(self.folder.name, viewers)
        self.bench.frame(SPS)
        self.bench.frame(IDR)
        self.bench.end_session()
        self.bench.packetizer = rtp.Packetizer(seq=500, ts_base=90000)
        self.bench.frame(SPS)
        self.bench.frame(P3)
        first, second = viewers.started
        self.assertTrue(until(lambda: second.got == annex_b(SPS, P3)))
        self.assertEqual(annex_b(SPS, IDR), first.got)
        self.assertTrue(first.stdin.closed and not second.stdin.closed)
        self.assertEqual("REau bench panel - session 2", second.command[second.command.index("-window_title") + 1])
        self.bench.end_session()
        self.assertEqual([annex_b(SPS, IDR), annex_b(SPS, P3)], [self.bench.written(0), self.bench.written(1)])

    def test_a_damaged_frame_goes_neither_to_the_file_nor_to_the_viewer(self):
        viewers = Viewers()
        self.bench = Bench(self.folder.name, viewers)
        big = bytes([0x65]) + bytes([7]) * 4000   # three packets: the middle one is lost
        self.bench.frame(SPS)
        self.bench.frame(big, drop=(1,))
        self.bench.frame(P1)
        (viewer,) = viewers.started
        self.assertEqual(1, self.bench.server.panel.sink.damaged)
        self.assertTrue(until(lambda: viewer.got == annex_b(SPS, P1)))
        self.bench.end_session()
        self.assertEqual(annex_b(SPS, P1), viewer.got)
        self.assertEqual(annex_b(SPS, P1), self.bench.written())

    def test_without_ffplay_it_says_so_and_only_writes(self):
        viewers = Viewers()
        self.bench = Bench(self.folder.name, viewers, find=lambda name: None)
        self.assertEqual(1, len(self.bench.said("[viewer] ffplay was not found (it comes with FFmpeg): the video is only written to the file")))
        self.bench.frame(SPS)
        self.bench.frame(IDR)
        self.bench.end_session()
        self.assertEqual([], viewers.started)
        self.assertEqual(annex_b(SPS, IDR), self.bench.written())
        self.assertEqual(0, self.bench.log.errors)

    def test_without_ver_no_viewer_is_looked_for_nor_started(self):
        viewers, looked = Viewers(), []
        log = log_painel.Log(quiet=True, keep=True)
        server = painel_falso.PanelServer(log, bind=LOOPBACK, ctrl_port=0, rtp_port=0, out_dir=self.folder.name, popen=viewers, find=looked.append)
        self.addCleanup(server.close)
        feed(server, rtp.Packetizer(), SPS, 33)
        server.panel._end_session("the test ends it")
        self.assertEqual(([], []), (viewers.started, looked))
        self.assertEqual(1, len(server.panel.videos))

    def test_a_viewer_that_does_not_start_is_said_and_the_file_is_written(self):
        viewers = Viewers(error=OSError("no display"))
        self.bench = Bench(self.folder.name, viewers)
        self.bench.frame(SPS)
        self.bench.frame(IDR)
        self.bench.end_session()
        self.assertEqual(1, len(self.bench.said("could not be started (no display): the video is only written to the file")))
        self.assertEqual(annex_b(SPS, IDR), self.bench.written())

    def test_a_window_closed_in_the_middle_costs_the_view_and_not_the_file(self):
        viewers = Viewers(breaks_after=2)
        self.bench = Bench(self.folder.name, viewers)
        for nal in (SPS, IDR, P1, P2, P3):
            self.bench.frame(nal)
        (viewer,) = viewers.started
        self.assertTrue(until(lambda: self.bench.said("the viewer is gone (BrokenPipeError)")))
        self.bench.frame(P1)
        self.bench.end_session()
        self.assertEqual(annex_b(SPS, IDR), viewer.got)
        self.assertEqual(annex_b(SPS, IDR, P1, P2, P3, P1), self.bench.written())
        self.assertEqual(1, len(self.bench.said("the viewer is gone")))
        self.assertEqual(0, self.bench.log.errors)

    def test_the_session_after_a_closed_window_gets_a_new_one(self):
        viewers = Viewers(breaks_after=0)
        self.bench = Bench(self.folder.name, viewers)
        self.bench.frame(SPS)
        self.assertTrue(until(lambda: self.bench.said("the viewer is gone")))
        self.bench.end_session()
        viewers.behaviour = {}
        self.bench.packetizer = rtp.Packetizer(seq=500, ts_base=90000)
        self.bench.frame(SPS)
        self.assertEqual(2, len(viewers.started))
        self.assertTrue(until(lambda: viewers.started[1].got == annex_b(SPS)))
        self.bench.end_session()


class LiveViewTest(unittest.TestCase):
    def view(self, backlog=3, **behaviour):
        self.log = log_painel.Log(quiet=True, keep=True)
        self.viewers = Viewers(**behaviour)
        view = painel_falso.LiveView(self.log, ["viewer"], popen=self.viewers, backlog=backlog)
        self.addCleanup(view.close, 0.2)
        return view, self.viewers.started[0]

    def test_a_viewer_that_stops_reading_never_holds_the_panel_back(self):
        view, viewer = self.view(stuck=True)
        began = time.monotonic()
        for _ in range(200):
            view.write(annex_b(P1))
        self.assertLess(time.monotonic() - began, 1.0)
        self.assertEqual([], viewer.chunks)
        self.assertGreaterEqual(view.skipped, 196)
        self.assertEqual(1, sum("the viewer is not keeping up" in line for line in self.log.lines))

    def test_a_viewer_that_fell_behind_gets_frames_again_from_the_next_keyframe(self):
        view, viewer = self.view(stuck=True)
        for _ in range(10):
            view.write(annex_b(P1))
        self.assertTrue(viewer.writing.wait(5.0))
        viewer.stuck.set()   # it reads again
        self.assertTrue(until(lambda: len(viewer.chunks) >= 3))
        taken = len(viewer.chunks)
        view.write(annex_b(P2))      # still part of the picture it lost: kept from it
        view.write(annex_b(IDR))
        view.write(annex_b(P3))
        self.assertTrue(until(lambda: len(viewer.chunks) == taken + 2))
        self.assertEqual([annex_b(IDR), annex_b(P3)], viewer.chunks[taken:])
        self.assertNotIn(annex_b(P2), viewer.chunks)

    def test_a_keyframe_is_a_parameter_set_or_an_idr(self):
        self.assertEqual([True, True, False, False], [painel_falso._is_keyframe(data) for data in (annex_b(SPS), annex_b(IDR), annex_b(P1), rtp.START_CODE)])

    def test_closing_stops_a_viewer_that_does_not_leave(self):
        view, viewer = self.view(stuck=True)
        for _ in range(10):
            view.write(annex_b(P1))
        self.assertTrue(viewer.writing.wait(5.0))
        began = time.monotonic()
        view.close(0.2)
        self.assertLess(time.monotonic() - began, 3.0)
        self.assertGreaterEqual(viewer.terminated, 1)
        self.assertFalse(view._thread.is_alive())

    def test_after_closing_nothing_more_goes_to_it(self):
        view, viewer = self.view()
        view.write(annex_b(SPS))
        self.assertTrue(until(lambda: viewer.chunks == [annex_b(SPS)]))
        view.close(0.5)
        view.write(annex_b(IDR))
        view.close(0.5)   # closing twice is harmless
        self.assertEqual([annex_b(SPS)], viewer.chunks)
        self.assertFalse(view._thread.is_alive())


class LoopbackViewTest(unittest.TestCase):
    def test_a_real_session_over_the_loopback_shows_what_it_writes(self):
        viewers = Viewers()
        with tempfile.TemporaryDirectory() as folder:
            bench = Loopback(out_dir=folder, view=True, popen=viewers, find=lambda name: FFPLAY)
            try:
                self.assertTrue(bench.phone.connect())
                bench.phone.start_projection()
                for i, nal in enumerate((SPS, IDR, P1, P2, P3)):
                    bench.phone.send_frame(nal, 1000 + 33 * i)
                    self.assertTrue(until(lambda: bench.panel.sink.complete > i))
                # The phone's answer to the last notice is waited for, so that it is not the first packet of a session after this one.
                self.assertTrue(until(lambda: sum(bench.panel.replies.values()) == 5))
                bench.phone.goodbye()
                self.assertTrue(until(lambda: bench.panel.sessions == 1))
                (viewer,) = viewers.started
                self.assertTrue(until(lambda: viewer.stdin.closed))   # the session counts as over a moment before its video is closed
                self.assertEqual(annex_b(SPS, IDR, P1, P2, P3), viewer.got)
                with open(bench.panel.videos[0], "rb") as f:
                    self.assertEqual(viewer.got, f.read())
                self.assertEqual(0, bench.log.errors)
            finally:
                bench.close()


if __name__ == "__main__":
    unittest.main()
