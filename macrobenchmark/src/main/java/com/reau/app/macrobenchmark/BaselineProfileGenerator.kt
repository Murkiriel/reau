package com.reau.app.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Writes down which methods run while the app opens, for a baseline profile. It needs a device (Android 13+, or an older one that can be rooted), and
 * its output is NOT used by the build by itself: the app's own profile, app/src/main/baseline-prof.txt, is written by hand and stays. The test prints where it put
 * the file (a `baseline-prof.txt` under the device's /sdcard/Android/media/); pull it, compare with the one in the app and add by hand what is worth adding
 * (CONTRIBUTING, "Baseline profile").
 *
 * It opens the app and waits for it to be idle: the terms screen on a phone that never accepted them, the main screen on one that did. Accept the terms on
 * the device first to record the main screen.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun appOpens() = rule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
    }
}
