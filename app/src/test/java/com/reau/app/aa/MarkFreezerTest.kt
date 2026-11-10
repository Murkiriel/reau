package com.reau.app.aa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone

/**
 * The rider's mark keeps a copy of the session recording that the ring does not overwrite (plan 22, item 2): the name, the copy of the
 * two halves as they were at the mark, one copy per stretch of the ring, the ceiling, and the free-space check.
 */
class MarkFreezerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val utc = TimeZone.getTimeZone("UTC")
    private val log = mutableListOf<String>()
    private var nowMs = 1_762_024_551_000L // 2025-11-01 19:15:51 UTC
    private val sessions get() = File(tmp.root, "sessions")

    private fun ring(maxBytes: Long = 400, name: String = "reau_20261101_184137_902") =
        SessionRecorder(sessions, name, maxBytes, { log += it }, { nowMs }, ring = true)

    private fun message(i: Int) = AapMessage(Channel.ID_VID, 0x0B.toByte(), 1, 2, 12, ByteArray(12) { (i + it).toByte() })

    private fun freezer(maxMarks: Int = 3, maxBytes: Long = Long.MAX_VALUE, free: Long = Long.MAX_VALUE) =
        MarkFreezer(sessions, { log += it }, { nowMs }, utc, MarkFreezer.Limits(maxMarks, maxBytes, reserveBytes = 0), freeBytes = { free })

    private fun fill(r: SessionRecorder, from: Int, count: Int) = (from until from + count).forEach { r.write(message(it)) }

    private fun kept() = sessions.listFiles().orEmpty().map { it.name }.filter { MarkFreezer.isFrozenName(it) }.sorted()

    // ── the name ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the copy is named by the date and the time of the mark, with marca, so the ring never writes to it`() {
        assertEquals("reau_20251101_191551_marca", MarkFreezer.nameFor(nowMs, utc))
        assertTrue(MarkFreezer.isFrozenName("reau_20251101_191551_marca.aasession"))
        assertTrue(MarkFreezer.isFrozenName("reau_20251101_191551_marca.1.aasession"))
        assertEquals("reau_20251101_191551_marca", MarkFreezer.baseOf("reau_20251101_191551_marca.1.aasession"))
    }

    @Test
    fun `the ring's own names are not copies, and no ring name can be taken for one`() {
        val ringName = SessionRecorder.nameFor(nowMs)
        assertFalse(MarkFreezer.isFrozenName("$ringName.aasession"))
        assertFalse(MarkFreezer.isFrozenName("$ringName.1.aasession"))
        assertFalse(MarkFreezer.isFrozenName("reau_20251101_191551_marca.aasession.tmp"))
        assertNull(MarkFreezer.baseOf("reau_20251101_184137_902.aasession"))
        assertTrue("the ring's name ends in milliseconds, a copy's in marca", Regex("^reau_\\d{8}_\\d{6}_\\d{3}$").matches(ringName))
    }

    @Test
    fun `a second taken twice gets a number`() {
        val taken = setOf("reau_20251101_191551_marca", "reau_20251101_191551_marca2")
        assertEquals("reau_20251101_191551_marca3", MarkFreezer.nameFor(nowMs, utc, taken::contains))
    }

    // ── the copy ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `both halves of the ring are copied, each a recording of its own, and the ring goes on without touching them`() {
        val r = ring()
        fill(r, 0, 30) // 400 bytes ring: two halves of 200
        assertTrue("rolled over: ${r.rollovers}", r.rollovers >= 1)

        val snapshot = checkNotNull(r.snapshot()) // flushes: the files hold everything written
        val ringMessages = SessionRecorder.read(r.file).messages.size + SessionRecorder.read(File(sessions, "reau_20261101_184137_902.1.aasession")).messages.size
        assertTrue(freezer().freeze(snapshot, mark = 1))

        assertEquals(listOf("reau_20251101_191551_marca.1.aasession", "reau_20251101_191551_marca.aasession"), kept())
        val newer = SessionRecorder.read(File(sessions, "reau_20251101_191551_marca.aasession"))
        val older = SessionRecorder.read(File(sessions, "reau_20251101_191551_marca.1.aasession"))
        assertEquals(ringMessages, newer.messages.size + older.messages.size)
        assertFalse(newer.truncated)
        assertFalse(older.truncated)
        assertTrue(log.last { "mark 1" in it }.contains("reau_20251101_191551_marca"))

        // The ring overwrites itself afterwards: the copy stays as it was.
        val copyBefore = File(sessions, "reau_20251101_191551_marca.aasession").readBytes()
        fill(r, 100, 60)
        r.close()
        assertArrayEquals(copyBefore, File(sessions, "reau_20251101_191551_marca.aasession").readBytes())
    }

    @Test
    fun `a ring that has not rolled over gives one part, and what the file held at the mark is all there is, not what came after`() {
        val r = ring(maxBytes = 100_000)
        fill(r, 0, 5)
        val snapshot = checkNotNull(r.snapshot())
        fill(r, 5, 5) // after the mark
        assertTrue(freezer().freeze(snapshot, mark = 1))

        assertEquals(listOf("reau_20251101_191551_marca.aasession"), kept())
        val copy = SessionRecorder.read(File(sessions, "reau_20251101_191551_marca.aasession"))
        assertEquals(5, copy.messages.size)
        assertFalse(copy.truncated)
        r.close()
    }

    @Test
    fun `the copy sees the messages the stream had not yet flushed`() {
        val r = ring(maxBytes = 100_000)
        fill(r, 0, 2) // a few bytes: they sit in the stream's buffer, not in the file
        val snapshot = checkNotNull(r.snapshot())
        assertTrue(freezer().freeze(snapshot, mark = 1))
        assertEquals(2, SessionRecorder.read(File(sessions, "reau_20251101_191551_marca.aasession")).messages.size)
        r.close()
    }

    @Test
    fun `a rollover while the copy is made does not change what is copied`() {
        val r = ring()
        fill(r, 0, 30)
        val snapshot = checkNotNull(r.snapshot())
        val total = snapshot.bytes
        fill(r, 100, 80) // the ring rolls over several times before the copy begins
        assertTrue(freezer().freeze(snapshot, mark = 1))
        val copied = kept().sumOf { File(sessions, it).length() }
        assertEquals(total, copied)
        r.close()
    }

    @Test
    fun `no snapshot of a ring that wrote nothing`() {
        assertNull(ring().snapshot())
    }

    // ── one copy per stretch of the ring ────────────────────────────────────────────────────────────────────

    @Test
    fun `a second mark in the same stretch of the ring replaces the copy with a larger one, it does not make another`() {
        val r = ring(maxBytes = 100_000)
        fill(r, 0, 5)
        val f = freezer()
        assertTrue(f.freeze(checkNotNull(r.snapshot()), mark = 1))
        val first = File(sessions, "reau_20251101_191551_marca.aasession").length()
        nowMs += 40_000
        fill(r, 5, 5)
        assertTrue(f.freeze(checkNotNull(r.snapshot()), mark = 2))

        assertEquals("one copy", listOf("reau_20251101_191551_marca.aasession"), kept())
        assertTrue(File(sessions, "reau_20251101_191551_marca.aasession").length() > first)
        assertEquals(10, SessionRecorder.read(File(sessions, "reau_20251101_191551_marca.aasession")).messages.size)
        assertTrue(log.last { "mark 2" in it }.contains("replacing"))
        r.close()
    }

    @Test
    fun `a mark after the ring rolled over makes a new copy, the older beginning is not given up`() {
        val r = ring()
        fill(r, 0, 10)
        val f = freezer()
        assertTrue(f.freeze(checkNotNull(r.snapshot()), mark = 1))
        val before = r.rollovers
        nowMs += 120_000
        fill(r, 10, 40)
        assertTrue("rolled over", r.rollovers > before)
        assertTrue(f.freeze(checkNotNull(r.snapshot()), mark = 2))

        assertEquals(2, kept().mapNotNull { MarkFreezer.baseOf(it) }.distinct().size)
        r.close()
    }

    @Test
    fun `another session is another copy`() {
        val f = freezer()
        val a = ring(maxBytes = 100_000, name = "reau_20261101_100000_000")
        fill(a, 0, 3)
        assertTrue(f.freeze(checkNotNull(a.snapshot()), mark = 1))
        val b = ring(maxBytes = 100_000, name = "reau_20261101_110000_000")
        fill(b, 0, 3)
        nowMs += 60_000
        assertTrue(f.freeze(checkNotNull(b.snapshot()), mark = 1))
        assertEquals(2, kept().mapNotNull { MarkFreezer.baseOf(it) }.distinct().size)
    }

    // ── the ceiling ────────────────────────────────────────────────────────────────────────────────────────

    private fun copy(base: String, bytes: Long, at: Long) = MarkFreezer.Copy(base, bytes, at)

    @Test
    fun `within the ceiling nothing goes`() {
        val copies = listOf(copy("a", 10, 1), copy("b", 10, 2), copy("c", 10, 3))
        assertEquals(emptyList<String>(), MarkFreezer.overCeiling(copies, keep = "c", maxMarks = 3, maxBytes = 100))
    }

    @Test
    fun `over the count the oldest go, only as many as it takes`() {
        val copies = listOf(copy("a", 10, 1), copy("b", 10, 2), copy("c", 10, 3), copy("d", 10, 4))
        assertEquals(listOf("a"), MarkFreezer.overCeiling(copies, keep = "d", maxMarks = 3, maxBytes = 1_000))
        assertEquals(listOf("a", "b"), MarkFreezer.overCeiling(copies, keep = "d", maxMarks = 2, maxBytes = 1_000))
    }

    @Test
    fun `over the bytes the oldest go, and the one just made stays even if it alone is over`() {
        val copies = listOf(copy("a", 60, 1), copy("b", 60, 2), copy("c", 60, 3))
        assertEquals(listOf("a"), MarkFreezer.overCeiling(copies, keep = "c", maxMarks = 9, maxBytes = 130))
        assertEquals(listOf("a", "b"), MarkFreezer.overCeiling(copies, keep = "c", maxMarks = 9, maxBytes = 50))
    }

    @Test
    fun `the kept one is not the newest by the clock when a replacement is older`() {
        val copies = listOf(copy("a", 10, 5), copy("b", 10, 1))
        assertEquals(listOf("a"), MarkFreezer.overCeiling(copies, keep = "b", maxMarks = 1, maxBytes = 1_000))
    }

    @Test
    fun `in the folder the fourth copy removes the oldest whole pair, and only copies, never the ring's files or another recording`() {
        sessions.mkdirs()
        File(sessions, "reau_20261101_184137_902.aasession").writeText("ring newer")
        File(sessions, "reau_20261101_184137_902.1.aasession").writeText("ring older")
        File(sessions, "other.txt").writeText("x")
        val f = freezer(maxMarks = 3)
        val first = MarkFreezer.nameFor(nowMs, utc)
        for (i in 0 until 4) {
            val s = ring(maxBytes = 100_000, name = "reau_20261101_19000${i}_000")
            fill(s, 0, 3)
            assertTrue(f.freeze(checkNotNull(s.snapshot()), mark = i + 1))
            s.close()
            nowMs += 60_000
        }
        val bases = kept().mapNotNull { MarkFreezer.baseOf(it) }.distinct()
        assertEquals("three copies: $bases", 3, bases.size)
        assertFalse("the oldest is gone", first in bases)
        assertTrue(File(sessions, "reau_20261101_184137_902.aasession").exists())
        assertTrue(File(sessions, "reau_20261101_184137_902.1.aasession").exists())
        assertTrue(File(sessions, "other.txt").exists())
        assertTrue(log.any { "removed" in it && first in it })
    }

    // ── free space and failures ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `with too little free space nothing is copied, and the log says so`() {
        val r = ring(maxBytes = 100_000)
        fill(r, 0, 5)
        val f = MarkFreezer(sessions, { log += it }, { nowMs }, utc, MarkFreezer.Limits(reserveBytes = 1_000_000), freeBytes = { 500_000 })
        assertFalse(f.freeze(checkNotNull(r.snapshot()), mark = 1))
        assertEquals(emptyList<String>(), kept())
        assertTrue(log.last().contains("mark 1: the session recording was not copied"))
        r.close()
    }

    @Test
    fun `the free space needed is the copy plus the reserve`() {
        val r = ring(maxBytes = 100_000)
        fill(r, 0, 5)
        val size = checkNotNull(r.snapshot()).also { it.close() }.bytes
        val f = MarkFreezer(sessions, { log += it }, { nowMs }, utc, MarkFreezer.Limits(reserveBytes = 1_000), freeBytes = { size + 999 })
        assertFalse(f.freeze(checkNotNull(r.snapshot()), mark = 1))
        val g = MarkFreezer(sessions, { log += it }, { nowMs }, utc, MarkFreezer.Limits(reserveBytes = 1_000), freeBytes = { size + 1_000 })
        assertTrue(g.freeze(checkNotNull(r.snapshot()), mark = 1))
        r.close()
    }

    @Test
    fun `a stale temporary file of a killed copy is removed, a recent one is left`() {
        sessions.mkdirs()
        val stale = File(sessions, "reau_20251101_100000_marca.aasession.tmp").apply { writeText("x"); setLastModified(nowMs - 3_600_000) }
        val recent = File(sessions, "reau_20251101_100500_marca.aasession.tmp").apply { writeText("x"); setLastModified(nowMs - 1_000) }
        val r = ring(maxBytes = 100_000)
        fill(r, 0, 2)
        assertTrue(freezer().freeze(checkNotNull(r.snapshot()), mark = 1))
        assertFalse(stale.exists())
        assertTrue(recent.exists())
        assertNotNull(kept().firstOrNull())
        r.close()
    }

    @Test
    fun `the copies' ceiling is 3 copies and 250 MB in a release, and up to the whole 1024 MB of recordings in a debug build`() {
        val release = MarkFreezer.Limits.forBuild(debugBuild = false)
        assertEquals(MarkFreezer.Limits(), release)
        assertEquals(250L * 1024 * 1024, release.maxBytes)
        val debug = MarkFreezer.Limits.forBuild(debugBuild = true)
        assertEquals(1024L * 1024 * 1024, debug.maxBytes)
        assertEquals("the same count and the same free space to leave", release.maxMarks, debug.maxMarks)
        assertEquals(release.reserveBytes, debug.reserveBytes)
    }
}
