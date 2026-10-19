package com.reau.app.stream

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The debug option that keeps a copy of the H.264 sent to the dash: a plain Annex-B file any player opens, and
 * next to it one line per unit with the time it was stamped with.
 */
class H264RecorderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val log = mutableListOf<String>()
    private val startCode = bytes(0, 0, 0, 1)
    private val keyframe = bytes(0x67, 0x42, 0, 0x29, 0, 0, 0, 1, 0x68, 0x22, 0, 0, 0, 1, 0x65, 0x33) // SPS + PPS + IDR, bundled
    private fun pFrame(marker: Int) = bytes(0x41, marker, marker)

    private fun recorder(maxFrames: Int, dir: File = File(tmp.root, "video"), name: String = "reau_20261002_150000") =
        H264Recorder(dir, maxFrames, name) { log += it }

    @Test
    fun `every unit is written after a start code, in the order it was sent`() {
        val r = recorder(maxFrames = 10)
        r.write(keyframe, isKey = true, ptsMs = 0)
        r.write(pFrame(1), isKey = false, ptsMs = 62)
        r.write(pFrame(2), isKey = false, ptsMs = 124)
        r.close()

        assertEquals(File(tmp.root, "video/reau_20261002_150000.h264"), r.videoFile)
        assertArrayEquals(startCode + keyframe + startCode + pFrame(1) + startCode + pFrame(2), r.videoFile.readBytes())
        assertEquals(3, r.frames)
    }

    @Test
    fun `the index has one line per unit with its time, its size and whether it is a keyframe`() {
        val r = recorder(maxFrames = 10)
        r.write(keyframe, isKey = true, ptsMs = 0)
        r.write(pFrame(1), isKey = false, ptsMs = 62)
        r.write(keyframe, isKey = true, ptsMs = 1_000)
        r.close()

        assertEquals(File(tmp.root, "video/reau_20261002_150000.csv"), r.indexFile)
        assertEquals(listOf("frame,pts_ms,bytes,key", "1,0,16,1", "2,62,3,0", "3,1000,16,1"), r.indexFile.readLines())
    }

    @Test
    fun `the recording ends by itself at the limit and says where it is`() {
        val r = recorder(maxFrames = 3)
        r.write(keyframe, isKey = true, ptsMs = 0)
        r.write(pFrame(1), isKey = false, ptsMs = 62)
        assertFalse(r.isDone)
        r.write(pFrame(2), isKey = false, ptsMs = 124)
        assertTrue(r.isDone)
        r.write(pFrame(3), isKey = false, ptsMs = 186) // past the limit: ignored
        r.write(keyframe, isKey = true, ptsMs = 248)

        assertEquals(3, r.frames)
        assertArrayEquals(startCode + keyframe + startCode + pFrame(1) + startCode + pFrame(2), r.videoFile.readBytes())
        assertEquals(4, r.indexFile.readLines().size)
        assertEquals(
            listOf("video recording complete: 3 frames, 34 bytes, ${r.videoFile.path}"),
            log,
        )
    }

    @Test
    fun `a recording opens with a keyframe, whatever comes first`() {
        val r = recorder(maxFrames = 10)
        r.write(pFrame(1), isKey = false, ptsMs = 0)
        r.write(pFrame(2), isKey = false, ptsMs = 62)
        assertFalse("nothing is created before the keyframe", r.videoFile.exists())
        r.write(keyframe, isKey = true, ptsMs = 124)
        r.write(pFrame(3), isKey = false, ptsMs = 186)
        r.close()

        assertArrayEquals(startCode + keyframe + startCode + pFrame(3), r.videoFile.readBytes())
        assertEquals(listOf("frame,pts_ms,bytes,key", "1,124,16,1", "2,186,3,0"), r.indexFile.readLines())
    }

    @Test
    fun `closing early keeps what was written and ends the recording`() {
        val r = recorder(maxFrames = 100)
        r.write(keyframe, isKey = true, ptsMs = 0)
        r.close()
        r.write(pFrame(1), isKey = false, ptsMs = 62)
        r.close() // twice is fine

        assertTrue(r.isDone)
        assertArrayEquals(startCode + keyframe, r.videoFile.readBytes())
        assertEquals(listOf("video recording stopped early: 1 frames, 20 bytes, ${r.videoFile.path}"), log)
    }

    @Test
    fun `a recorder closed before its first keyframe records nothing afterwards`() {
        val r = recorder(maxFrames = 10)
        r.write(pFrame(1), isKey = false, ptsMs = 0)
        r.close()
        r.write(keyframe, isKey = true, ptsMs = 62)

        assertTrue(r.isDone)
        assertFalse(r.videoFile.exists())
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `a limit of zero records nothing and creates nothing`() {
        val r = recorder(maxFrames = 0)
        r.write(keyframe, isKey = true, ptsMs = 0)
        r.close()

        assertFalse(File(tmp.root, "video").exists())
        assertEquals(0, r.frames)
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `a folder that cannot be written costs the recording, never the stream`() {
        val notAFolder = tmp.newFile("video") // a file where the folder should be
        val r = recorder(maxFrames = 10, dir = notAFolder)
        r.write(keyframe, isKey = true, ptsMs = 0) // must not throw
        assertTrue("the recording is over at the first failure", r.isDone)
        r.write(pFrame(1), isKey = false, ptsMs = 62)
        r.write(keyframe, isKey = true, ptsMs = 1_000) // it does not try again at the next keyframe
        r.close()

        assertTrue(r.isDone)
        assertEquals(0, r.frames)
        assertEquals("one line, not one per frame: $log", 1, log.size)
        assertTrue(log.single(), log.single().startsWith("video recording failed: "))
    }

    @Test
    fun `only the last fifty recordings are kept`() {
        // It used to be five; changed on purpose.
        val dir = tmp.newFolder("video")
        val old = (1..51).map { "reau_20261002_14%04d".format(it) }
        old.forEach {
            File(dir, "$it.h264").writeBytes(startCode)
            File(dir, "$it.csv").writeText("frame,pts_ms,bytes,key\n")
        }
        File(dir, "notes.txt").writeText("not a recording")

        val r = recorder(maxFrames = 10, dir = dir, name = "reau_20261002_150000")
        r.write(keyframe, isKey = true, ptsMs = 0)
        r.close()

        val kept = dir.list().orEmpty().sorted()
        val expected = (old.takeLast(49) + "reau_20261002_150000").flatMap { listOf("$it.csv", "$it.h264") } + "notes.txt"
        assertEquals(expected.sorted(), kept)
    }

    @Test
    fun `file names carry the date and time the recording began, to the millisecond`() {
        // 2026-10-02 15:00:00.250 in the JVM's own time zone. It used to be to the second: two recordings begun in
        // the same second (a stream restarted at once) shared one file and the second overwrote the first. Changed on
        // purpose.
        val at = java.util.GregorianCalendar(2026, java.util.Calendar.OCTOBER, 2, 15, 0, 0).timeInMillis + 250
        assertEquals("reau_20261002_150000_250", H264Recorder.nameFor(at))
        assertTrue("names still sort by time", H264Recorder.nameFor(at) < H264Recorder.nameFor(at + 1))
    }

    @Test
    fun `a recording never overwrites one that already has its name`() {
        val dir = tmp.newFolder("video")
        File(dir, "reau_20261002_150000_250.h264").writeBytes(bytes(9, 9, 9))
        File(dir, "reau_20261002_150000_250.csv").writeText("earlier\n")
        val r = recorder(maxFrames = 10, dir = dir, name = "reau_20261002_150000_250")
        r.write(keyframe, isKey = true, ptsMs = 0)
        r.close()
        assertArrayEquals("the earlier recording is untouched", bytes(9, 9, 9), File(dir, "reau_20261002_150000_250.h264").readBytes())
        assertEquals("reau_20261002_150000_250_2.h264", r.videoFile.name)
        assertEquals("reau_20261002_150000_250_2.csv", r.indexFile.name)
        assertArrayEquals(startCode + keyframe, r.videoFile.readBytes())
    }

    @Test
    fun `everything up to the last keyframe is on disk even if the app is killed before closing`() {
        // The file was buffered until it was
        // closed; an app killed mid-recording lost the tail. Now each keyframe is flushed with what came before it.
        val r = recorder(maxFrames = 100)
        r.write(keyframe, isKey = true, ptsMs = 0)
        assertEquals("the first keyframe is on disk", (startCode + keyframe).size.toLong(), r.videoFile.length())
        r.write(pFrame(1), isKey = false, ptsMs = 62)
        r.write(keyframe, isKey = true, ptsMs = 124)
        val written = (startCode + keyframe + startCode + pFrame(1) + startCode + keyframe).size.toLong()
        assertEquals("everything up to the second keyframe, without close()", written, r.videoFile.length())
        assertEquals("the index too", 4, r.indexFile.readLines().size)
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
