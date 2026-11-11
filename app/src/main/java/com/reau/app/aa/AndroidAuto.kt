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
     * If that screen cannot be opened, the system's page of the Android Auto app is opened instead, and [say] tells the ride log so
     * ([planBLine]): the phone's Android Auto then has no settings screen of the name REau knows (an update renamed it?) or refuses it.
     */
    fun openSettings(context: Context, start: (Intent) -> Unit = context::startActivity, say: (String) -> Unit = {}): Opened =
        open(start, say)

    // The two intents are asked for only when needed, and can be given: the JVM tests have no Intent that builds itself (the framework stub returns null).
    internal fun open(
        start: (Intent) -> Unit,
        say: (String) -> Unit = {},
        own: () -> Intent = ::settingsIntent,
        details: () -> Intent = ::detailsIntent,
    ): Opened {
        val ownFailure = failure(own(), start)
        if (ownFailure == null) return Opened.ANDROID_AUTO_SETTINGS
        val detailsFailure = failure(details(), start)
        say(planBLine(ownFailure, detailsFailure))
        return if (detailsFailure == null) Opened.APP_DETAILS else Opened.NOTHING
    }

    /** Android Auto's settings screen, where its developer menu is. */
    fun settingsIntent(): Intent = Intent().setClassName(PACKAGE, SETTINGS_ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Whether REau takes the rider to Android Auto's settings by itself, so "Start head unit server" is one tap away: only while the server is off,
     * only once for each projection, only with REau on screen (never pulled over another app) and only with Android Auto installed
     * (else the button leads to the store, which is not what a rider expects to be shown by itself).
     */
    fun opensSettingsByItself(
        link: AaLinkState, alreadyOpened: Boolean, onScreen: Boolean, aaInstalled: Boolean,
    ): Boolean = link == AaLinkState.SERVER_OFF && !alreadyOpened && onScreen && aaInstalled

    /** Whether [settingsIntent] opens anything on this phone. */
    fun settingsOpen(context: Context): Boolean = context.packageManager.resolveActivity(settingsIntent(), 0) != null

    internal fun opens(intent: Intent, start: (Intent) -> Unit): Boolean = failure(intent, start) == null

    /** Null when [intent] was started; else the class name of why it was not. */
    internal fun failure(intent: Intent, start: (Intent) -> Unit): String? = try {
        start(intent)
        null
    } catch (e: ActivityNotFoundException) {
        e.javaClass.simpleName
    } catch (e: SecurityException) {
        e.javaClass.simpleName
    }
}

/**
 * The ride log's line for an opening of the settings that fell to plan B: why Android Auto's own screen did not open (by the exception's
 * class, `ActivityNotFoundException` or `SecurityException`) and what became of the system's page of the app (`opened` or why not).
 */
internal fun planBLine(ownFailure: String, detailsFailure: String?): String {
    val second = if (detailsFailure == null) {
        "the system's page of the app opened instead (plan B)"
    } else {
        "the system's page of the app did not open either ($detailsFailure)"
    }
    return "Android Auto's own settings screen did not open ($ownFailure): $second"
}

/** The system's page of the Android Auto app: plan B for [AndroidAuto.settingsIntent]. */
private fun detailsIntent(): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${AndroidAuto.PACKAGE}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
