"""The bench panel's log: every line to a file, and to the terminal the ones that say something happened (all of them with `verbose`).

Three kinds of line: an event (the key exchange, a button, the video's figures), a detail (each packet in and out) and a protocol error
(something the phone sent that the protocol does not allow, or that the panel expected and did not come), which is also counted.
"""
import datetime
import sys


class Log:
    def __init__(self, path=None, verbose=False, quiet=False, stream=None, keep=False):
        self.verbose = verbose
        self.stream = None if quiet else (sys.stdout if stream is None else stream)
        self.errors = 0
        self.lines = [] if keep else None  # every line, for the tests and the self-test
        self.path = path
        self._file = open(path, "a", encoding="utf-8") if path else None

    def _write(self, tag, text, shown):
        now = datetime.datetime.now()
        line = "%s.%03d [%s] %s" % (now.strftime("%H:%M:%S"), now.microsecond // 1000, tag, text)
        if self.lines is not None:
            self.lines.append("[%s] %s" % (tag, text))
        if self._file:
            self._file.write(line + "\n")
            self._file.flush()
        if shown and self.stream:
            print(line, file=self.stream, flush=True)

    def event(self, tag, text):
        self._write(tag, text, True)

    def detail(self, tag, text):
        self._write(tag, text, self.verbose)

    def error(self, text):
        self.errors += 1
        self._write("protocol", "ERROR: " + text, True)

    def close(self):
        if self._file:
            self._file.close()
            self._file = None
