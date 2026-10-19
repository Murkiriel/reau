package com.reau.app.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Privacy: the logs and the debug recordings live in the app's
 * internal storage (a computer reading the phone over USB sees nothing of them), and what an older version left in the
 * external folder is moved there once.
 */
class AppFoldersTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `the old folder's files move to the new one, and the old folder goes`() {
        val old = tmp.newFolder("external", "diag")
        File(old, "ride-a.log").writeText("one")
        File(old, "crash-reported").writeText("1")
        val new = File(tmp.root, "internal/diag")
        assertEquals(2, AppFolders.move(old, new))
        assertEquals("one", File(new, "ride-a.log").readText())
        assertTrue(File(new, "crash-reported").exists())
        assertFalse("the old folder is gone", old.exists())
    }

    @Test
    fun `moving again, or from nothing, moves nothing`() {
        val new = File(tmp.root, "internal/diag")
        assertEquals(0, AppFolders.move(File(tmp.root, "no-such"), new))
        assertFalse(new.exists())
    }

    @Test
    fun `a name already in the new folder is kept, the old one taking another name`() {
        val old = tmp.newFolder("external", "video")
        File(old, "v.h264").writeText("old")
        val new = tmp.newFolder("internal", "video")
        File(new, "v.h264").writeText("new")
        assertEquals(1, AppFolders.move(old, new))
        assertEquals("new", File(new, "v.h264").readText())
        assertEquals("old", File(new, "moved-v.h264").readText())
    }
}
