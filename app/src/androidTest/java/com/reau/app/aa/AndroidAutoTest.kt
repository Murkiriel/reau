package com.reau.app.aa

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.TestDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** What the app knows about the Android Auto installed on the phone, and how it sends the rider to its settings. */
@RunWith(AndroidJUnit4::class)
class AndroidAutoTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun thePackageIsGooglesAndroidAuto() {
        assertEquals("com.google.android.projection.gearhead", AndroidAuto.PACKAGE)
    }

    @Test
    fun aPackageThatIsNotInstalledHasNoVersion() {
        assertNull(AndroidAuto.installedVersion(context, "com.reau.no.such.package"))
    }

    /** The emulator image carries a stub of Android Auto (1.2.x-stub): no projection in it, but a version to read; a phone
     *  has the real one (17.9.x-release). Either way the package must be visible to the app, which needs the <queries>
     *  entry of the manifest, and its version name read: this is the same lookup.
     */
    @Test
    fun theInstalledAndroidAutoIsSeenAndItsVersionRead() {
        val version = AndroidAuto.installedVersion(context)
        assertNotNull("the gearhead package must be visible to the app", version)
        assertTrue("got $version", Regex("""\d+\.\d+\.\d+.*""").matches(version!!))
        if (TestDevice.isEmulator) assertTrue("the emulator's is a stub, got $version", version.endsWith("-stub"))
    }

    @Test
    fun theLogLineSaysWhichVersionOrThatThereIsNone() {
        assertEquals("Android Auto 17.4.661804-release is installed", AndroidAuto.describe("17.4.661804-release"))
        assertEquals("Android Auto is not installed", AndroidAuto.describe(null))
    }

    @Test
    fun theVersionOfAnInstalledPackageIsItsVersionName() {
        val own = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        assertNotNull(own)
        assertEquals(own, AndroidAuto.installedVersion(context, context.packageName))
    }

    @Test
    fun openSettingsGoesToAndroidAutosOwnSettingsScreen() {
        val started = mutableListOf<Intent>()

        val opened = AndroidAuto.openSettings(context) { started += it }

        assertEquals(AndroidAuto.Opened.ANDROID_AUTO_SETTINGS, opened)
        val intent = started.single()
        assertEquals("com.google.android.projection.gearhead", intent.component?.packageName)
        assertEquals("com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity", intent.component?.className)
        assertTrue("it is started from outside an activity", intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun whenThatScreenCannotBeOpenedTheSystemsPageOfTheAppIsOpenedInstead() {
        val started = mutableListOf<Intent>()

        val opened = AndroidAuto.openSettings(context) {
            started += it
            if (started.size == 1) throw ActivityNotFoundException("no such activity")
        }

        assertEquals(AndroidAuto.Opened.APP_DETAILS, opened)
        assertEquals(2, started.size)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started[1].action)
        assertEquals("package:com.google.android.projection.gearhead", started[1].dataString)
        assertTrue(started[1].flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun aRefusalByTheSystemCountsAsNotOpenedToo() {
        var calls = 0
        val opened = AndroidAuto.openSettings(context) {
            calls++
            if (calls == 1) throw SecurityException("not exported")
        }
        assertEquals(AndroidAuto.Opened.APP_DETAILS, opened)
    }

    @Test
    fun whenNeitherOpensItSaysSoInsteadOfCrashing() {
        var calls = 0
        val opened = AndroidAuto.openSettings(context) {
            calls++
            throw ActivityNotFoundException("nothing")
        }
        assertEquals(AndroidAuto.Opened.NOTHING, opened)
        assertEquals(2, calls)
    }
}
