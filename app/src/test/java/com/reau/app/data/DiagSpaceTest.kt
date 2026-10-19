package com.reau.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The diag folder holds at most a ceiling of bytes (200 MB on the phone); the oldest files of every kind go first; an
 * open file is never removed, nor the marks of what was already reported.
 */
class DiagSpaceTest {
    @get:Rule val tmp = TemporaryFolder()

    private var clock = 1_000_000L

    /** A file of [kb] KB, each one newer than the last. */
    private fun file(name: String, kb: Int): File = File(tmp.root, name).apply {
        writeBytes(ByteArray(kb * 1024))
        clock += 60_000
        setLastModified(clock)
    }

    private fun names() = tmp.root.list()!!.sorted()

    @Test
    fun `under the ceiling nothing goes`() {
        file("ride-1.log", 10)
        file("logcat-1.txt", 10)
        assertNull(DiagSpace.trim(tmp.root, capBytes = 30 * 1024L, open = emptySet()))
        assertEquals(listOf("logcat-1.txt", "ride-1.log"), names())
    }

    @Test
    fun `over it, the oldest of any kind go first until it fits`() {
        file("ride-1.log", 40)
        file("crash-1.txt", 10)
        file("logcat-1.txt", 40)
        file("anr-1.txt", 10)
        file("ride-2.log", 40)
        val note = DiagSpace.trim(tmp.root, capBytes = 95 * 1024L, open = emptySet())
        assertEquals("the two oldest went", listOf("anr-1.txt", "logcat-1.txt", "ride-2.log"), names())
        assertEquals("2 files removed (50 KB), 90 KB left of 95 KB", note)
    }

    @Test
    fun `an open file is never removed, even the oldest`() {
        file("ride-1.log", 40)
        file("logcat-1.txt", 40)
        file("ride-2.log", 40)
        DiagSpace.trim(tmp.root, capBytes = 50 * 1024L, open = setOf("ride-1.log", "logcat-1.txt"))
        assertEquals(listOf("logcat-1.txt", "ride-1.log"), names())
    }

    @Test
    fun `the marks of what was reported and files of other kinds stay`() {
        file("crash-reported", 1)
        file("exit-reported", 1)
        file("notes.txt", 50)
        file("native-crash-1.txt", 50)
        file("ride-1.log", 50)
        DiagSpace.trim(tmp.root, capBytes = 110 * 1024L, open = emptySet())
        assertEquals(listOf("crash-reported", "exit-reported", "notes.txt", "ride-1.log"), names())
    }

    @Test
    fun `the phone's ceiling is 200 MB`() {
        assertEquals(200L * 1024 * 1024, DiagSpace.CAP_BYTES)
        assertTrue(DiagSpace.isLog("logcat-20261004-101010.txt") && DiagSpace.isLog("ride-20261004-101010-2.log"))
    }
}
