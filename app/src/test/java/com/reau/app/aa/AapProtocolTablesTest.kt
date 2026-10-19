package com.reau.app.aa

import com.reau.app.aa.proto.Control
import com.reau.app.aa.proto.Media
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterisation of the small tables and helpers of the copied message layer (channels, message type names,
 * byte helpers, the log shim). Written AFTER the code.
 */
class AapProtocolTablesTest {

    @After
    fun resetLog() {
        AaLog.sink = null
        AaLog.LOG_VERBOSE = false
    }

    // ── channels ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the channel numbers are the ones the service discovery will advertise`() {
        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14),
            listOf(
                Channel.ID_CTR, Channel.ID_SEN, Channel.ID_VID, Channel.ID_INP, Channel.ID_AU1, Channel.ID_AU2, Channel.ID_AUD,
                Channel.ID_MIC, Channel.ID_BTH, Channel.ID_MPB, Channel.ID_NAV, Channel.ID_NOTI, Channel.ID_PHONE, Channel.ID_WIFI,
                Channel.ID_SPE,
            ),
        )
    }

    @Test
    fun `channels have names for the log`() {
        assertEquals("CONTROL", Channel.name(Channel.ID_CTR))
        assertEquals("VIDEO", Channel.name(Channel.ID_VID))
        assertEquals("SENSOR", Channel.name(Channel.ID_SEN))
        assertEquals("INPUT", Channel.name(Channel.ID_INP))
        assertEquals("NAVIGATION_DIRECTIONS", Channel.name(Channel.ID_NAV))
        assertEquals("UNK", Channel.name(99))
    }

    @Test
    fun `the four audio channels are audio and nothing else is`() {
        assertEquals(listOf(Channel.ID_AU1, Channel.ID_AU2, Channel.ID_AUD, Channel.ID_SPE), (0..14).filter { Channel.isAudio(it) })
        assertEquals("SPEECH", Channel.name(Channel.ID_SPE))
    }

    // ── message types ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `control types are 1 to 26`() {
        assertFalse(MsgType.isControl(0))
        assertTrue(MsgType.isControl(1))
        assertTrue(MsgType.isControl(26))
        assertFalse(MsgType.isControl(27))
        assertFalse(MsgType.isControl(Media.MsgType.MEDIA_MESSAGE_ACK_VALUE))
        assertEquals(2, MsgType.SIZE)
    }

    @Test
    fun `message types have names, and three of them depend on the channel`() {
        assertEquals("Version Request", MsgType.name(Control.ControlMsgType.MESSAGE_VERSION_REQUEST_VALUE, Channel.ID_CTR))
        assertEquals("Service Discovery Request", MsgType.name(5, Channel.ID_CTR))
        assertEquals("Ping Request", MsgType.name(11, Channel.ID_CTR))
        assertEquals("Media Data", MsgType.name(Media.MsgType.MEDIA_MESSAGE_DATA_VALUE, Channel.ID_VID))
        assertEquals("Video Focus Notification", MsgType.name(Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION_VALUE, Channel.ID_VID))

        val start = Media.MsgType.MEDIA_MESSAGE_START_VALUE
        assertEquals("Media Start Request", MsgType.name(start, Channel.ID_VID))
        assertEquals("Sensor Start Request", MsgType.name(start, Channel.ID_SEN))
        assertEquals("Input Event", MsgType.name(start, Channel.ID_INP))
        val config = Media.MsgType.MEDIA_MESSAGE_CONFIG_VALUE
        assertEquals("Media Config Response", MsgType.name(config, Channel.ID_VID))
        assertEquals("Sensor Event", MsgType.name(config, Channel.ID_SEN))
        assertEquals("Input Binding Response", MsgType.name(config, Channel.ID_INP))

        assertEquals("Unknown (12345)", MsgType.name(12345, Channel.ID_CTR))
    }

    // ── byte helpers ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `sixteen-bit numbers are written and read big-endian`() {
        val buf = ByteArray(6)
        Utils.intToBytes(0x1234, 2, buf)
        assertArrayEquals(byteArrayOf(0, 0, 0x12, 0x34, 0, 0), buf)
        assertEquals(0x1234, Utils.bytesToInt(buf, 2, true))
        Utils.intToBytes(0xFFFE, 0, buf)
        assertEquals("above 32767 too", 0xFFFE, Utils.bytesToInt(buf, 0, true))
    }

    @Test
    fun `thirty-two-bit numbers are read big-endian`() {
        assertEquals(0x01020304, Utils.bytesToInt(byteArrayOf(9, 1, 2, 3, 4), 1, false))
        assertEquals(-2, Utils.bytesToInt(byteArrayOf(-1, -1, -1, -2), 0, false))
    }

    @Test
    fun `a time is written as eight bytes, big-endian`() {
        val buf = ByteArray(10)
        Utils.put_time(1, buf, 0x0102030405060708L)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 0), buf)
    }

    @Test
    fun `the accessory version is two bytes, little-endian`() {
        assertEquals(0x0102, Utils.getAccVersion(byteArrayOf(2, 1)))
    }

    @Test
    fun `the default buffer holds the largest frame`() {
        assertEquals(131080, Messages.DEF_BUFFER_LENGTH)
    }

    // ── the log shim ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `log lines reach the sink tagged AA, formatted printf-style`() {
        val lines = mutableListOf<String>()
        AaLog.sink = { lines += it }
        AaLog.i("channel %d open, %s", 2, "VIDEO")
        AaLog.w("slow")
        AaLog.e("failed with 0x%02x", 255)
        assertEquals(listOf("[AA] channel 2 open, VIDEO", "[AA] W: slow", "[AA] E: failed with 0xff"), lines)
    }

    @Test
    fun `debug lines are dropped unless verbose logging is on`() {
        val lines = mutableListOf<String>()
        AaLog.sink = { lines += it }
        AaLog.d("quiet")
        AaLog.v("quiet")
        AaLog.LOG_VERBOSE = true
        AaLog.d("loud %d", 1)
        assertEquals(listOf("[AA] loud 1"), lines)
    }

    @Test
    fun `a format that does not fit its arguments still logs them`() {
        val lines = mutableListOf<String>()
        AaLog.sink = { lines += it }
        AaLog.i("needs a number %d", "text")
        assertEquals(listOf("[AA] needs a number %d text"), lines)
    }

    @Test
    fun `an exception passed last is appended, not fed to the format`() {
        val lines = mutableListOf<String>()
        AaLog.sink = { lines += it }
        AaLog.e("read failed on %s", "VIDEO", IllegalStateException("boom"))
        assertEquals(1, lines.size)
        assertTrue(lines.single(), lines.single().startsWith("[AA] E: read failed on VIDEO :: "))
    }

    @Test
    fun `a sink that throws does not break the caller`() {
        AaLog.sink = { error("the log is closed") }
        AaLog.i("still fine")
    }
}
