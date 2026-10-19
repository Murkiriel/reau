package com.reau.app.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The 200 MB ceiling held only for the logs; the debug recordings (`video`, `sessions`) went by age alone and, with
 * "Nunca" chosen, never (129 MB of video and 78 MB of sessions were measured on a device). Together they now
 * stay under a ceiling of their own, the oldest going first.
 */
class RecordingsSpaceTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var video: File
    private lateinit var sessions: File
    private val now = 1_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    @Before
    fun setUp() {
        video = tmp.newFolder("video")
        sessions = tmp.newFolder("sessions")
    }

    private fun file(dir: File, name: String, kb: Int, ageMs: Long): File =
        File(dir, name).apply { writeBytes(ByteArray(kb * 1024)); setLastModified(now - ageMs) }

    private fun trim(capKb: Long) = RecordingsSpace.trim(listOf(video, sessions), capKb * 1024, now)

    @Test
    fun `under the ceiling nothing goes and nothing is said`() {
        file(video, "a.h264", 100, day)
        file(sessions, "b.aasession", 100, day)
        assertNull(trim(capKb = 300))
        assertEquals(2, video.listFiles()!!.size + sessions.listFiles()!!.size)
    }

    @Test
    fun `over it the oldest go first, across both folders, until the rest fits`() {
        file(sessions, "old.aasession", 100, 5 * day)
        file(video, "mid.h264", 100, 3 * day)
        file(sessions, "new.aasession", 100, day)
        val said = trim(capKb = 250)
        assertEquals("recordings space: 1 file removed (100 KB), 200 KB left of 250 KB", said)
        assertFalse(File(sessions, "old.aasession").exists())
        assertTrue(File(video, "mid.h264").exists() && File(sessions, "new.aasession").exists())
    }

    @Test
    fun `a video and its index go together, whole, never one without the other`() {
        file(video, "reau_1.h264", 100, 4 * day)
        file(video, "reau_1.csv", 10, 4 * day)
        file(video, "reau_2.h264", 100, day)
        file(video, "reau_2.csv", 10, day)
        val said = trim(capKb = 150)
        assertEquals("recordings space: 2 files removed (110 KB), 110 KB left of 150 KB", said)
        assertFalse(File(video, "reau_1.h264").exists() || File(video, "reau_1.csv").exists())
        assertTrue(File(video, "reau_2.h264").exists() && File(video, "reau_2.csv").exists())
    }

    @Test
    fun `a recording written in the last two minutes is in use and stays, even if that leaves the folders over`() {
        file(sessions, "live.aasession", 300, 30_000)
        file(video, "old.h264", 100, 2 * day)
        val said = trim(capKb = 100)
        assertEquals("recordings space: 1 file removed (100 KB), 300 KB left of 100 KB", said)
        assertTrue(File(sessions, "live.aasession").exists())
    }

    @Test
    fun `nothing to remove but a folder over, nothing is said`() {
        file(sessions, "live.aasession", 300, 1_000)
        assertNull(trim(capKb = 100))
    }

    @Test
    fun `a folder that does not exist is no problem, and other files are not touched`() {
        val none = File(tmp.root, "missing")
        file(video, "a.h264", 100, 5 * day)
        assertNull(RecordingsSpace.trim(listOf(none, video), 1_000_000, now))
        val said = RecordingsSpace.trim(listOf(none, video), 50 * 1024, now)
        assertEquals("recordings space: 1 file removed (100 KB), 0 KB left of 50 KB", said)
    }

    @Test
    fun `a recorder makes room in both recording folders, found beside its own`() {
        file(video, "v.h264", 100, 3 * day)
        file(sessions, "s.aasession", 100, 2 * day)
        val said = RecordingsSpace.makeRoom(sessions, capBytes = 120 * 1024, nowMs = now)
        assertEquals("recordings space: 1 file removed (100 KB), 100 KB left of 120 KB", said)
        assertFalse(File(video, "v.h264").exists())
    }

    @Test
    fun `the ceiling is 200 MB`() {
        assertEquals(200L * 1024 * 1024, RecordingsSpace.CAP_BYTES)
    }
}
