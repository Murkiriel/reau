package com.reau.app.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Privacy: logs (and debug recordings) older than the rider's choice of days go by themselves. Never an open file,
 * never the reported marks, never a file of another kind; zero days means never.
 */
class LogAgeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun file(dir: File, name: String, ageDays: Long) = File(dir, name).apply {
        writeText("x")
        setLastModified(now - ageDays * day)
    }

    @Test
    fun `older than the days chosen go, younger stay`() {
        val d = tmp.newFolder("diag")
        val old = file(d, "ride-20260901-080000.log", 40)
        val oldLogcat = file(d, "logcat-20260901-080000.txt", 31)
        val young = file(d, "ride-20261001-080000.log", 29)
        val said = LogAge.trim(listOf(d), keepDays = 30, nowMs = now, open = emptySet())
        assertFalse(old.exists())
        assertFalse(oldLogcat.exists())
        assertTrue(young.exists())
        assertEquals("2 files older than 30 days removed", said)
    }

    @Test
    fun `never an open file, a mark, or a file of another kind`() {
        val d = tmp.newFolder("diag")
        val open = file(d, "ride-20260901-080000.log", 40)
        val mark = file(d, "crash-reported", 400)
        val other = file(d, "notes.txt", 400)
        assertNull(LogAge.trim(listOf(d), keepDays = 7, nowMs = now, open = setOf(open.name)))
        assertTrue(open.exists() && mark.exists() && other.exists())
    }

    @Test
    fun `the recordings by age too`() {
        val video = tmp.newFolder("video")
        val old = file(video, "video-20260901-080000.h264", 10)
        val oldCsv = file(video, "video-20260901-080000.csv", 10)
        val young = file(video, "video-20261008-080000.h264", 1)
        LogAge.trim(listOf(video), keepDays = 7, nowMs = now, open = emptySet())
        assertFalse(old.exists() || oldCsv.exists())
        assertTrue(young.exists())
    }

    @Test
    fun `zero days is never`() {
        val d = tmp.newFolder("diag")
        val old = file(d, "ride-20200101-080000.log", 2000)
        assertNull(LogAge.trim(listOf(d), keepDays = 0, nowMs = now, open = emptySet()))
        assertTrue(old.exists())
    }
}
