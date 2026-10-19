// The way into Android Auto's settings follows open-headunit (AGPL-3.0), commit 93c065dd:
// app/src/main/java/com/andrerinas/openheadunit/connection/self/SelfLauncherManager.kt, openAaSettings (the settings
// activity's name and the fallback to the system's page of the app). The rest is written for REau.
package com.reau.app.aa

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.net.toUri

/** What the app knows about the Android Auto installed on the phone, and how it sends the rider to its settings. */
object AndroidAuto {
    const val PACKAGE = "com.google.android.projection.gearhead"
    private const val SETTINGS_ACTIVITY = "$PACKAGE.companion.settings.DefaultSettingsActivity"

    enum class Opened { ANDROID_AUTO_SETTINGS, APP_DETAILS, STORE_APP, STORE_WEB, NOTHING }

    /** The version name of the installed Android Auto, or null when there is none. */
    fun installedVersion(context: Context, packageName: String = PACKAGE): String? = try {
        context.packageManager.getPackageInfo(packageName, 0).versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * Whether Android Auto [version] (as [installedVersion] gives it) is one that dials the head unit, 17.3 and older, which
     * [AaCallsIn] asks to; 17.4 and newer is dialled by REau at its developer server. A version that is not there or cannot be
     * read is taken as a newer one.
     */
    fun dialsIn(version: String?): Boolean {
        val numbers = version?.takeWhile { it.isDigit() || it == '.' }?.split('.')?.mapNotNull { it.toIntOrNull() } ?: return false
        val major = numbers.getOrNull(0) ?: return false
        val minor = numbers.getOrNull(1) ?: 0
        return major < 17 || (major == 17 && minor < 4)
    }

    /** The line for the diagnostics log; [version] as [installedVersion] gives it. */
    fun describe(version: String?): String =
        if (version == null) "Android Auto is not installed" else "Android Auto $version is installed"

    /**
     * Opens Android Auto's settings, where the rider reaches the developer options and "Start head unit server".
     * If that screen cannot be opened, the system's page of the Android Auto app is opened instead.
     */
    fun openSettings(context: Context, start: (Intent) -> Unit = context::startActivity): Opened {
        val own = settingsIntent()
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$PACKAGE".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return when {
            opens(own, start) -> Opened.ANDROID_AUTO_SETTINGS
            opens(details, start) -> Opened.APP_DETAILS
            else -> Opened.NOTHING
        }
    }

    /** Android Auto's settings screen, where its developer menu is (the walk opens it by accessibility). */
    fun settingsIntent(): Intent = Intent().setClassName(PACKAGE, SETTINGS_ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Whether [settingsIntent] opens anything on this phone. */
    fun settingsOpen(context: Context): Boolean = context.packageManager.resolveActivity(settingsIntent(), 0) != null

    internal fun opens(intent: Intent, start: (Intent) -> Unit): Boolean = try {
        start(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
