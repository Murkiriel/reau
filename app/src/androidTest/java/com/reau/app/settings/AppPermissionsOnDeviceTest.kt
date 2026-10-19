package com.reau.app.settings

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The permissions page lists what Android says the INSTALLED app asks for (the merged manifest, libraries included):
 * every one of them has REau's own explanation but the internal receiver permission AndroidX adds, which gets the
 * library text. A permission added later without an explanation fails here.
 */
@RunWith(AndroidJUnit4::class)
class AppPermissionsOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyPermissionTheInstalledAppAsksForIsExplained() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toList()
        assertTrue("Android lists them: $requested", requested.size >= 15)
        val library = requested.filter { AppPermissions.explain(it).kind == AppPermissions.Kind.LIBRARY }
        assertEquals("only AndroidX's receiver permission is a library's", listOf("${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"), library)
        val rows = AppPermissions.rows(requested, { true }, accessibilityOn = true, batteryExempt = true)
        assertEquals("each once, plus the two special accesses", requested.distinct().size + 2, rows.size)
    }
}
