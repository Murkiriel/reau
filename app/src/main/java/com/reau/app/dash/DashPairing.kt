package com.reau.app.dash

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.reau.app.data.KeystoreBackedPrefs

/**
 * Which dash this phone talks to: its Wi-Fi name and password, kept on the device.
 *
 * Every dash is an access point named `RE_…` (e.g. `RE_AB12_345678`) with the panel's default passphrase
 * `12345678`, so out of the box the app can look for a dash by [ssidPrefix] and the rider picks theirs in the
 * system dialog once. The exact name is then remembered in [ssid]: the dash checks it inside the encrypted
 * handshake ([DashAuth]), and Android 13+ hides the name of the connected network, so it has to be known up front.
 *
 * Give it [open]'s store: the name and the password identify one rider's bike and are encrypted at rest.
 */
class DashPairing(private val prefs: SharedPreferences) : JoinMemory {

    /** Broadest match across dash variants; rider-overridable. Never blank: that would match any network. */
    var ssidPrefix: String
        get() = prefs.getString(KEY_PREFIX, null).orDefault(DEFAULT_PREFIX)
        set(v) = prefs.edit { putString(KEY_PREFIX, v.trim()) }

    /** The exact name once learned or typed in. Empty = not known yet, discover by prefix. */
    var ssid: String
        get() = prefs.getString(KEY_SSID, null).orEmpty()
        set(v) = prefs.edit { putString(KEY_SSID, v.trim()) }

    /** Kept exactly as typed: a passphrase may begin or end with a space. */
    var password: String
        get() = prefs.getString(KEY_PASSWORD, null).let { if (it.isNullOrEmpty()) DEFAULT_PASSWORD else it }
        set(v) = prefs.edit { putString(KEY_PASSWORD, v) }

    /**
     * The name of the dash network this phone last joined from REau, or empty if it never has. Android asks the rider
     * once, in its own dialog, whether the app may join that network; having joined it is the sign that the question was
     * answered (see [DashJoin]). Kept apart from [ssid]: a name learned from the Wi-Fi list, or typed, was never approved.
     */
    override var joinedSsid: String
        get() = prefs.getString(KEY_JOINED, null).orEmpty()
        set(v) = prefs.edit { putString(KEY_JOINED, v.trim()) }

    /** True until a specific dash has been identified. */
    val needsDiscovery: Boolean get() = ssid.isBlank()

    /**
     * Drops the remembered dash and the memory of having joined it; the prefix and the password stay, so the next one
     * is found the same way ("Esquecer o painel").
     */
    fun forget() = prefs.edit {
        remove(KEY_SSID)
        remove(KEY_JOINED)
    }

    private fun String?.orDefault(default: String): String = if (isNullOrBlank()) default else this

    companion object {
        const val DEFAULT_PREFIX = "RE_"
        const val DEFAULT_PASSWORD = "12345678"
        private const val FILE = "dash_pairing"
        private const val KEY_PREFIX = "ssid_prefix"
        private const val KEY_SSID = "ssid"
        private const val KEY_PASSWORD = "password"
        private const val KEY_JOINED = "joined_ssid"

        /** The pairing of this install, in a preferences file whose values are encrypted under an Android Keystore key. */
        fun open(context: Context): DashPairing = DashPairing(KeystoreBackedPrefs.open(context.applicationContext, FILE))
    }
}
