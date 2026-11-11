package com.reau.app.data

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The lock the ride log is written through is shared with the video threads and Android Auto's (they log from their loops), so no disk work may
 * be done under it (11/11/2026): the rider's "Apagar registros" and the days kept used to delete
 * files with the lock held, and every thread that logged waited for it. Each test holds one piece of disk work still, logs from another thread, and
 * requires that the logging goes on at once; and that the order and the content of the file are what they would have been (RideDiagnosticsSequenceTest
 * holds the text).
 */
class RideDiagnosticsLockTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var diag: File

    @Before
    fun setUp() {
        diag = tmp.newFolder("diag")
        RideDiagnostics.testOnlyUseDir(diag, today = { "20261111" })
        OwnLogcat.testOnlyUseDir(diag)
    }

    @After
    fun tearDown() {
        OwnLogcat.testOnlyReset()
        RideDiagnostics.testOnlyReset()
    }

    private fun rides() = diag.listFiles { f -> f.name.startsWith("ride-") }!!.toList()

    /** Logs [count] lines from the calling thread and says how long it took, in ms. */
    private fun timedLogging(tag: String, count: Int): Long {
        val start = System.nanoTime()
        repeat(count) { RideDiagnostics.log(tag, "line $it") }
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun `while the rider's deletion cleans the folder, other threads log without waiting for it, and their lines come after the new file's header in order`() {
        RideDiagnostics.startProcess("app")
        RideDiagnostics.logAndWait("ui", "before")
        val cleaning = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val clearing = Thread {
            RideDiagnostics.clearAndReopen { d ->
                cleaning.countDown()
                finish.await(30, TimeUnit.SECONDS)
                d.listFiles()?.forEach { it.delete() }
                "cleaned"
            }
        }
        clearing.start()
        assertTrue("the cleaning began", cleaning.await(5, TimeUnit.SECONDS))

        val t0 = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
        // The cleaning is held; two threads log meanwhile (the video loop and Android Auto's, say). With the lock held for the cleaning they would be stuck here.
        val tookMs = LongArray(2)
        val loggers = listOf("video", "aa").mapIndexed { i, tag -> Thread { tookMs[i] = timedLogging(tag, 300) }.apply { start() } }
        loggers.forEach { it.join(10_000) }
        assertTrue("both returned while the cleaning was still going on: ${tookMs.toList()}", loggers.none { it.isAlive } && tookMs.all { it < 3_000 })
        assertTrue("the cleaning has not finished", clearing.isAlive)

        finish.countDown()
        clearing.join(10_000)
        RideDiagnostics.logAndWait("ui", "after")

        val lines = rides().single().readLines()
        assertTrue(lines.toString(), "the rider deleted the logs: cleaned" in lines.first())
        for (tag in listOf("video", "aa")) {
            val mine = lines.filter { "[$tag] line " in it }.map { it.substringAfter("[$tag] line ").toInt() }
            assertEquals("every line of $tag is there, once, in order", (0 until 300).toList(), mine)
        }
        assertTrue("nothing was buffered as pre-connect", lines.none { "pre-connect" in it })
        // Each held line keeps the time it was logged (after t0, before the cleaning ended), not the time of the swap.
        val held = lines.filter { "[video] line " in it || "[aa] line " in it }
        assertTrue("a held line carries an earlier time than the header's: ${held.first()} / ${lines.first()}", held.all { it.substringBefore("  ") >= t0 && it.substringBefore("  ") <= lines.first().substringBefore("  ") })
        assertTrue("the later line comes last", "[ui] after" in lines.last())
    }

    @Test
    fun `while the days kept delete the old files, other threads log without waiting`() {
        RideDiagnostics.startProcess("app")
        RideDiagnostics.logAndWait("ui", "before")
        val trimming = CountDownLatch(1)
        val finish = CountDownLatch(1)
        RideDiagnostics.testOnlyDiskWork = { what ->
            if (what == "trim") {
                trimming.countDown()
                finish.await(30, TimeUnit.SECONDS)
            }
        }
        val old = File(diag, "ride-20260101-080000.log").apply { writeText("old"); setLastModified(System.currentTimeMillis() - 40L * 24 * 60 * 60 * 1000) }
        val applying = Thread { RideDiagnostics.applyKeepDays(30) }
        applying.start()
        assertTrue("the trimming began", trimming.await(5, TimeUnit.SECONDS))

        val tookMs = LongArray(1)
        val logger = Thread { tookMs[0] = timedLogging("video", 300) }.apply { start() }
        logger.join(5_000)
        assertTrue("300 lines took ${tookMs[0]} ms while the old files were being deleted", !logger.isAlive && tookMs[0] < 3_000 && applying.isAlive)

        finish.countDown()
        logger.join(10_000)
        applying.join(10_000)
        RideDiagnostics.logAndWait("ui", "after")
        assertTrue("the old file went", !old.exists())
        val lines = rides().single().readLines()
        assertEquals((0 until 300).toList(), lines.filter { "[video] line " in it }.map { it.substringAfter("[video] line ").toInt() })
        assertTrue(lines.toString(), lines.any { "diag age: 1 file older than 30 days removed" in it })
    }

    @Test
    fun `a roll to a new file does not hold the lock either, and no line is lost or doubled across files`() {
        RideDiagnostics.maxFileBytes = 400
        RideDiagnostics.startProcess("app")
        val creating = CountDownLatch(1)
        val finish = CountDownLatch(1)
        var armed = true
        RideDiagnostics.testOnlyDiskWork = { what ->
            if (what == "create" && armed) {
                armed = false
                creating.countDown()
                finish.await(30, TimeUnit.SECONDS)
            }
        }
        // One thread fills the file and rolls it; the file's creation is held.
        val roller = Thread { repeat(40) { RideDiagnostics.log("roller", "line $it with some text to fill the file up") } }
        roller.start()
        assertTrue("a roll began", creating.await(10, TimeUnit.SECONDS))

        val tookMs = LongArray(1)
        val logger = Thread { tookMs[0] = timedLogging("video", 100) }.apply { start() }
        logger.join(5_000)
        assertTrue("100 lines took ${tookMs[0]} ms while the new file was being created", !logger.isAlive && tookMs[0] < 3_000)

        finish.countDown()
        logger.join(10_000)
        roller.join(10_000)
        RideDiagnostics.logAndWait("ui", "last")

        val files = rides()
        assertTrue("it rolled: ${files.map { it.name }}", files.size >= 2)
        val all = files.flatMap { it.readLines() }
        assertEquals("the roller's lines, each once", (0 until 40).toList().sorted(), all.filter { "[roller] line " in it }.map { it.substringAfter("[roller] line ").substringBefore(" ").toInt() }.sorted())
        assertEquals("the video's lines, each once", (0 until 100).toList(), all.filter { "[video] line " in it }.map { it.substringAfter("[video] line ").toInt() }.sorted())
        assertTrue("a file says where it continues from", all.any { "==== continued from ride-" in it })
    }
}
