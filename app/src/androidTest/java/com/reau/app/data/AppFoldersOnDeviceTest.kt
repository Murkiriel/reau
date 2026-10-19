package com.reau.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On a real Android: the logs live in the app's internal storage, and what an older
 * version left in the external folder (Android/data/com.reau.app/files) is moved there.
 */
@RunWith(AndroidJUnit4::class)
class AppFoldersOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theLogsAreInternalAndTheOldExternalOnesAreMovedIn() {
        assertEquals(File(context.filesDir, "diag"), AppFolders.diag(context))
        assertEquals(AppFolders.diag(context), RideDiagnostics.logDir())
        val external = checkNotNull(context.getExternalFilesDir(null))
        val old = File(external, "diag").apply { mkdirs() }
        File(old, "ride-20260101-000000-old.log").writeText("an old ride")
        File(external, "video").apply { mkdirs() }.let { File(it, "old-video.h264").writeBytes(ByteArray(8)) }

        assertTrue(AppFolders.migrate(context) >= 2)
        assertTrue(File(AppFolders.diag(context), "ride-20260101-000000-old.log").exists())
        assertTrue(File(AppFolders.recordings(context, "video"), "old-video.h264").exists())
        assertFalse("nothing left outside", old.exists())
        File(AppFolders.diag(context), "ride-20260101-000000-old.log").delete()
        File(AppFolders.recordings(context, "video"), "old-video.h264").delete()
    }
}
