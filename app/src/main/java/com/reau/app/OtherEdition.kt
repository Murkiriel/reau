package com.reau.app

import android.content.Context
import android.content.pm.PackageManager

/**
 * The two editions of REau (see [Edition]) can be installed side by side, and both dial the one head unit server Android
 * Auto opens on the phone, which takes a single session at a time. Turned on together, the second finds the handshake
 * unanswered and, without this, the screen only said that Android Auto does not respond. Whether the other edition is
 * installed is all that can be known of it (there is no way to tell, short of an IPC nobody asked for, whether it is
 * running), so the screen says "may be using it" and only while Android Auto is not answering.
 *
 * Each edition's manifest lists the other's package in its `<queries>`, so the package manager shows it (Android 11+).
 */
object OtherEdition {
    /** The id of the complete edition. */
    const val FULL_ID = "com.reau.app"

    /** The id of the Lite edition (the complete one's with ".lite"). */
    const val LITE_ID = "com.reau.app.lite"

    /** The id of the edition that is not [lite] (this one's by default). */
    fun packageOf(lite: Boolean = Edition.lite): String = if (lite) FULL_ID else LITE_ID

    /** The name the rider knows the edition that is not [lite] by. */
    fun nameOf(lite: Boolean = Edition.lite): String = if (lite) "REau" else "REau Lite"

    /** Whether the other edition is installed for this user. Never throws: a package manager that refuses says no. */
    fun installed(context: Context, lite: Boolean = Edition.lite): Boolean = try {
        context.packageManager.getPackageInfo(packageOf(lite), 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    /** What the diagnostics log adds to the line that says Android Auto does not answer; empty when the other edition is not there. */
    fun logNote(context: Context): String =
        if (installed(context)) "; ${packageOf()} (the other edition) is installed and may be using the same server" else ""
}
