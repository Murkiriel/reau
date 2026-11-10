package com.reau.app

import android.content.Context
import android.content.pm.PackageManager

/**
 * REau used to come in two editions, one with the id com.reau.app and one with com.reau.app.lite (the one with the seal on its icon).
 * Since version 0.3.0 there is one app, com.reau.app: the old full edition is updated in place (same id), but the old "lite" one
 * stays installed beside it until the rider removes it, and both dial the one head unit server Android Auto opens on the phone, which
 * takes a single session at a time. Turned on together, the second finds the handshake unanswered and, without this, the screen only
 * said that Android Auto does not respond. Whether the old app is installed is all that can be known of it (there is no way to tell,
 * short of an IPC nobody asked for, whether it is running), so the warning says "may be using it" and only while Android Auto is not
 * answering, and a discreet line on the main screen says it can be uninstalled.
 *
 * The manifest lists its package in `<queries>`, so the package manager shows it (Android 11+).
 */
object OtherEdition {
    /** The id of the old "lite" app. */
    const val LITE_ID = "com.reau.app.lite"

    /** Whether the old "lite" app is installed for this user. Never throws: a package manager that refuses says no. */
    fun installed(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(LITE_ID, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    /** What the diagnostics log adds to the line that says Android Auto does not answer; empty when the old app is not there. */
    fun logNote(context: Context): String =
        if (installed(context)) "; $LITE_ID (the old lite app) is installed and may be using the same server" else ""
}
