"""The constants of the dash's protocol that the bench panel uses, each as REau's app has it.

Nothing here is decided by this tool: every value is the app's (app/src/main/java/com/reau/app/dash/ and the fake dash of its tests), and
test_sincronia.py reads the Kotlin sources and fails when one of them changes there and not here.
"""

# DashSocket: where the phone sends control packets (a broadcast), where the dash answers the phone, and where the video goes.
DASH_IP = "192.168.1.1"
BROADCAST = "192.168.1.255"
CTRL_PORT = 2000
RX_PORT = 2002
RTP_PORT = 5000

# DashPairing: how every dash's Wi-Fi name begins, and the factory passphrase (the access point of the bench is set up with them).
SSID_PREFIX = "RE_"
DEFAULT_PASSWORD = "12345678"

# DashReadiness: the dash's heartbeat counter from which it takes the phone's greeting.
READY_COUNT = 26

# RtpPacketizer and RtpSink: the most bytes after the RTP header, and the payload type of the stream.
MAX_PAYLOAD = 1380
RTP_PAYLOAD_TYPE = 96

# The phone's packets, whole (DashCommands), with the sequence byte at zero.
AUTH_REQUEST = "0016000200000000020100054b314720000804000101"
PROJECTION_FRAME = "0016000200000000020100054B314720000556000155"
PROJECTION_ON = "0016000200000000020100054B314720000605000155"
PROJECTION_STOP = "0016000200000000020100054B3147200005560001AA"
PROJECTION_OFF = "0016000200000000020100054B3147200006050001AA"
FRAME_DECODED_IDR = "0016000200000000020100054B314720000611000155"
FRAME_DECODED_P = "0016000200000000020100054B314720000612000155"

# TLV keys (type, sub). From the phone:
GREETING = (0x08, 0x04)
SESSION_KEY = (0x08, 0x00)
HOSTNAME = (0x06, 0x0B)
PROJECTION = (0x05, 0x56)
PROJECTION_FLAG = (0x06, 0x05)
REPLY_IDR = (0x06, 0x11)
REPLY_P = (0x06, 0x12)
BUTTON_ECHO = (0x06, 0x80)
ZOOM_ICONS = (0x06, 0x0C)
CALLER_NAME = (0x05, 0x22)
# From the dash:
HEARTBEAT = (0x01, 0x01)
KEY_MODULUS = (0x07, 0x00)
KEY_EXPONENT = (0x07, 0x03)
KEY_VERDICT = (0x07, 0x01)
BUTTON = (0x09, 0x00)
NOTICE_IDR = (0x09, 0x06)
NOTICE_P = (0x09, 0x04)
SECURE = 0x0F

ON = 0x55
OFF = 0xAA
VERDICT_CONFIRMED = 0x01
VERDICT_REJECTED = 0x00
NAV_START = 0x0B
# What the phone sends in 06 0C after a zoom or recentre: both zoom icons (DashCommands.zoomIcons).
ZOOM_BOTH_ICONS = 0x30

# The joystick's codes (DashButtonEvent), and the map mode's "down" that DashSession and RingByPress know (0x15, the recentre).
BUTTONS = {
    "zoom-in": 0x14,
    "zoom-out": 0x13,
    "next": 0x09,
    "prev": 0x0A,
    "in": 0x18,
    "back": 0x12,
    "play": 0x05,
    "vol-up": 0x06,
    "vol-down": 0x07,
    "recentre": 0x15,
}
# The same buttons by where the joystick is pushed in the dash's map mode (RingByPress: left 0x14, right 0x13, down 0x15, up 0x12, middle 0x18).
BUTTON_ALIASES = {"left": "zoom-in", "right": "zoom-out", "down": "recentre", "up": "back", "middle": "in"}
# The codes after which the phone also sends the zoom icons (DashSession.ZOOM_CODES).
ZOOM_CODES = frozenset({0x13, 0x14, 0x15})

# The phone's exit sequence, in the order DashSession.disconnect sends it: (the name ExitSequence logs, type, sub, the one byte of the value).
EXIT_SEQUENCE = (
    ("projection-stop", 0x05, 0x56, 0xAA),
    ("projection-off", 0x06, 0x05, 0xAA),
    ("back", 0x06, 0x80, 0x12),
    ("idle", 0x06, 0x05, 0xAA),
    ("idle", 0x06, 0x05, 0xAA),
    ("idle", 0x06, 0x05, 0xAA),
    ("idle", 0x06, 0x05, 0xAA),
    ("boundary", 0x05, 0x17, 0xAA),
    ("boundary", 0x05, 0x17, 0xAA),
)

# What the bench log calls each TLV of the phone, from DashCommands' own notes. A key that is not here is only logged, as unknown.
PHONE_TLVS = {
    (0x08, 0x04): "greeting",
    (0x08, 0x00): "session key",
    (0x06, 0x0B): "phone name",
    (0x06, 0x06): "time of day",
    (0x05, 0x57): "burst flag 05 57",
    (0x05, 0x56): "projection keep-alive",
    (0x06, 0x05): "projection flag",
    (0x05, 0x17): "music flag",
    (0x0A, 0x02): "version query",
    (0x06, 0x08): "weather icon",
    (0x06, 0x10): "temperature",
    (0x06, 0x03): "GPS on",
    (0x06, 0x04): "battery",
    (0x06, 0x0F): "charging",
    (0x06, 0x01): "cell signal",
    (0x05, 0x4C): "music volume",
    (0x05, 0x2D): "heading",
    (0x05, 0x1B): "call volume",
    (0x05, 0x21): "call state",
    (0x05, 0x4D): "after the call state",
    (0x06, 0x11): "keyframe notice answered",
    (0x06, 0x12): "frame notice answered",
    (0x06, 0x80): "button echo",
    (0x06, 0x0C): "zoom icons",
    (0x06, 0x0A): "nav placeholder",
    (0x06, 0x0D): "no intermediate stops",
    (0x05, 0x2E): "nav context",
    (0x05, 0x2F): "favourites list",
    (0x05, 0x30): "favourites list",
    (0x05, 0x31): "favourites list",
    (0x05, 0x32): "favourites list",
    (0x05, 0x33): "favourites list",
    (0x05, 0x01): "route title",
    (0x05, 0x02): "maneuver",
    (0x05, 0x03): "next maneuver",
    (0x05, 0x04): "distance to the turn",
    (0x05, 0x05): "secondary distance",
    (0x05, 0x06): "distance unit",
    (0x05, 0x07): "route card field 05 07",
    (0x05, 0x08): "arrival time",
    (0x05, 0x54): "route card field 05 54",
    (0x05, 0x09): "total distance",
    (0x05, 0x46): "total unit",
    (0x05, 0x0A): "decimal separator",
    (0x05, 0x0C): "proximity bar",
    (0x05, 0x0B): "time left",
    (0x05, 0x55): "time-left unit",
    (0x05, 0x0D): "now playing",
    (0x05, 0x19): "music playing",
    (0x05, 0x58): "album picture flag",
    (0x05, 0x40): "album picture slice",
    (0x05, 0x22): "caller name",
}

# What the app's own dump leaves out of a log (DashDump): the texts the dash shows and the key exchange. Only their size is written.
LEFT_OUT = frozenset({(0x05, 0x01), (0x08, 0x00), (0x05, 0x0D), (0x05, 0x22), (0x05, 0x40)})
