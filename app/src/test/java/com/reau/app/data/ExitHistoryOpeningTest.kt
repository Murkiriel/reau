package com.reau.app.data

import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The `exit` line (how the process before this one ended) is told at every opening of the app and not only when a projection starts (11/11/2026): the
 * opening and the projection that follows in the same process share the book of what was told, so no exit is told twice, and an exit that only the
 * projection sees is still told. The line's format is `ExitReasons`' (ExitReasonsTest holds it); here is who tells it, and when.
 */
class ExitHistoryOpeningTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun exit(at: Long, pid: Int, reason: Int = ApplicationExitInfo.REASON_SIGNALED) =
        PastExit(at, "com.reau.app", pid, reason, 9, RunningAppProcessInfo.IMPORTANCE_CACHED, 0, 0, "test")

    private fun tell(book: ExitBook, exits: List<PastExit>): List<String> {
        val lines = ArrayList<String>()
        ExitHistory.tellNews(book, exits, { null }) { lines += it }
        return lines
    }

    @Test
    fun `an opening with no projection tells the exits not told yet`() {
        val book = ExitBook(tmp.newFolder("diag"))
        val lines = tell(book, listOf(exit(1_000, 11), exit(2_000, 12)))
        assertEquals(lines.toString(), 2, lines.size)
        assertTrue(lines.toString(), lines.all { "pid" in it || "11" in it || "12" in it })
    }

    @Test
    fun `the projection that follows in the same process tells none of them again`() {
        val book = ExitBook(tmp.newFolder("diag"))
        val exits = listOf(exit(1_000, 11), exit(2_000, 12))
        assertEquals(2, tell(book, exits).size) // the opening
        assertEquals("the projection's start", emptyList<String>(), tell(book, exits))
        assertEquals("and a book read afresh from the disk, as the next process would", emptyList<String>(), tell(ExitBook(File(tmp.root, "diag")), exits))
    }

    @Test
    fun `an exit that only the projection sees is still told, and alone`() {
        val book = ExitBook(tmp.newFolder("diag"))
        val first = listOf(exit(1_000, 11))
        tell(book, first)
        val later = first + exit(3_000, 13) // the previous process ended after the opening looked
        val lines = tell(book, later)
        assertEquals(lines.toString(), 1, lines.size)
        assertTrue(lines.toString(), "13" in lines.single())
    }

    @Test
    fun `an opening that finds nothing new writes nothing and marks nothing`() {
        val dir = tmp.newFolder("diag")
        val book = ExitBook(dir)
        assertEquals(emptyList<String>(), tell(book, emptyList()))
        assertFalse(File(dir, "exit-reported").exists())
    }

    @Test
    fun `the traces are kept for the exits told, and the line names the file`() {
        val dir = tmp.newFolder("diag")
        val book = ExitBook(dir)
        val anr = exit(5_000, 21, ApplicationExitInfo.REASON_ANR)
        val lines = ArrayList<String>()
        ExitHistory.tellNews(book, listOf(anr), { book.saveTrace(it, "trace".byteInputStream()) }) { lines += it }
        assertTrue(lines.toString(), lines.single().contains(".txt"))
        assertEquals(1, dir.listFiles { f -> f.name.endsWith(".txt") }!!.size)
    }
}
