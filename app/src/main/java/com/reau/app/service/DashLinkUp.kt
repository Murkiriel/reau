package com.reau.app.service

import android.net.Network
import com.reau.app.dash.DashJoin
import com.reau.app.dash.DashName
import com.reau.app.dash.DashPairing
import com.reau.app.dash.WifiConnStatus
import com.reau.app.dash.WifiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How the dash side gets to where the dash session can connect: on the phone, onto the bike's Wi-Fi. */
interface DashLinkUp {
    /** Gets there, then [onReady] with the dash's exact network name and the network; or [onFailed] with why. */
    fun start(onReady: (ssid: String, network: Network?) -> Unit, onFailed: (String) -> Unit)

    fun stop()

    /** The network the dash is reached through, while there is one (for the `device` line). */
    val bikeNetwork: Network? get() = null

    /** The last try found the dash's network in the scan but was refused: it is there, stuck or busy. */
    val dashVisible: Boolean get() = false

    /** [listener] is told each time the bike's Wi-Fi comes up (again), on any thread; it replaces the one before. */
    fun onBikeNetwork(listener: () -> Unit) = Unit

    /**
     * [listener] is told, on any thread, whether the network a start is about to ask for is one the rider has not
     * approved yet AND the dash was seen in the last Wi-Fi scan (so Android will really ask them in its dialog), and again
     * with false once it is joined; it replaces the one before. With the dash out of range no dialog comes, so no hint.
     */
    fun onFirstJoin(listener: (Boolean) -> Unit) = Unit
}

/**
 * Onto the bike's Wi-Fi with [DashWifiManager][com.reau.app.dash.DashWifiManager] (given here as its parts, so
 * a test needs no Android): by the exact name once the dash is known, by the beginning of its name until then.
 * The dash checks its own name inside the encrypted handshake, so the session is given the exact one, and a name
 * learned on the way is remembered for next time. A Wi-Fi already up from before is used at once.
 *
 * Each [start] reports once. What happens to the Wi-Fi after that is the session's to notice (its socket fails).
 *
 * The Wi-Fi manager gets a lost bike network back by itself (4 s, then a 10 s request,
 * again and again), and the projection's machine asks for the dash again on its own pace after the session's
 * watchdog. Were every one of those asks a fresh `connect()`, it would cancel the request the manager had
 * out and make it start over as a first-time attempt (30 s wait, giving up on the next refusal). So a [start] that
 * finds such a reconnection under way ([Joining.underWay], for the same dash) only waits for its outcome, at most
 * [Joining.waitMs]; past that it reports a failure, so the machine keeps its own pace, and the next start asks anew if the
 * manager has gone quiet meanwhile (see `DashWifiManager.reconnectUnderWay`).
 */
internal class WifiDashLinkUp(
    private val scope: CoroutineScope,
    private val wifiState: StateFlow<WifiState>,
    private val network: () -> Network?,
    private val connect: (ssid: String, password: String, prefixMatch: Boolean) -> Unit,
    private val disconnect: () -> Unit,
    private val pairing: Pairing,
) : DashLinkUp {
    /**
     * How a reconnection of the Wi-Fi manager's own is joined, set before the first [start]; the default
     * never joins (a test's). [Joining.underWay] says whether the manager is getting a lost bike network back by
     * itself right now; [Joining.waitMs] is how long a start waits on a reconnection it joined before reporting a
     * failure; [Joining.log] gets a line when one is joined.
     */
    @Volatile var joining: Joining = Joining()

    class Joining(
        val underWay: () -> Boolean = { false },
        val waitMs: Long = JOIN_WAIT_MS,
        val log: (String) -> Unit = {},
    )

    /** What is remembered of the dash (DashPairing on the phone), and where its full name can be looked up. */
    interface Pairing {
        val ssid: String
        val ssidPrefix: String
        val password: String
        fun remember(ssid: String)

        /** The dash network the rider already let REau join (DashPairing); null: nothing is known, so no first approval is assumed. */
        val joinedSsid: String? get() = null

        /** The full name of a network starting with [prefix] in the last Wi-Fi scan, or null. */
        fun discover(prefix: String): String? = null

        /**
         * Whether the last Wi-Fi scan has the network asked for ([wanted]; only its beginning if [isPrefix]) seen recently
         * ([DashJoin.SIGHTING_FRESH_MS]): only then is Android's approval dialog worth opening, and the request of a first
         * approval is not made without it. False when the dash is not there or the scan cannot be read (no hint on a guess).
         */
        fun seenInScan(wanted: String, isPrefix: Boolean): Boolean = false
    }

    private var watch: Job? = null

    // The log line about waiting for a sighting is said once per wait, not at every retry (the first ones are seconds apart).
    @Volatile private var waitingForSighting = false

    @Volatile private var bikeNetworkListener: (() -> Unit)? = null

    override fun onBikeNetwork(listener: () -> Unit) {
        bikeNetworkListener = listener
    }

    @Volatile private var firstJoinListener: ((Boolean) -> Unit)? = null

    override fun onFirstJoin(listener: (Boolean) -> Unit) {
        firstJoinListener = listener
    }

    /** The Wi-Fi manager got the bike's network (its state already says so): whoever listens is told. */
    fun bikeNetworkAvailable() {
        bikeNetworkListener?.invoke()
    }

    companion object {
        /** Why a start fails when the dash's full name is nowhere to be had; to the log, so the example is made up. */
        const val FULL_NAME_NEEDED =
            "nome inteiro do Wi-Fi do painel desconhecido: digite o nome inteiro (ex.: RE_AB12_345678) em Ajustes"

        /**
         * [text] with every one of [passwords] (the paired one, the panel's default one) replaced by "…": a password never goes to the
         * log, not even the default one a screen text may name. Empty passwords hide nothing.
         */
        internal fun withoutPasswords(text: String, vararg passwords: String): String =
            passwords.filter { it.isNotEmpty() }.fold(text) { said, password -> said.replace(password, "…") }

        /** What the projection is told when [start] fails with [why]. */
        fun linkAfterFailure(why: String): DashLink = if (why == FULL_NAME_NEEDED) DashLink.NAME_NEEDED else DashLink.DROPPED

        /** Why a start fails when the network to approve is not asked for yet: the dash is not in the recent Wi-Fi scan. */
        const val DASH_NOT_SEEN_YET = "painel ainda não visto na varredura do Wi-Fi: esperando ele aparecer"

        /** Why a start fails when the reconnection it joined has not come back in time. */
        const val RECONNECT_NOT_BACK_YET = "o Wi-Fi do painel ainda está tentando voltar"

        /** How long a start waits on a reconnection it joined: one request of the manager's and its pause. */
        const val JOIN_WAIT_MS = 20_000L

        /**
         * Whether a start joins the reconnection the Wi-Fi manager has under way instead of asking anew: only
         * with the manager asking (REQUESTING) for the same dash this start wants ([wanted], the exact name or the
         * prefix) and its reconnection recent ([underWay]). A manager asking by the prefix is getting back the very
         * network the exact name was learned on.
         */
        internal fun joinsReconnection(state: WifiState, wanted: String, underWay: Boolean): Boolean =
            underWay && state.status == WifiConnStatus.REQUESTING && state.ssid.isNotBlank() &&
                (state.ssid == wanted || wanted.startsWith(state.ssid))
    }

    override fun start(onReady: (ssid: String, network: Network?) -> Unit, onFailed: (String) -> Unit) {
        watch?.cancel()
        val now = wifiState.value
        if (now.status == WifiConnStatus.CONNECTED) {
            report(now, onReady, onFailed)
            return
        }
        // The dash checks its full name inside the handshake, so an unknown dash is first looked for in
        // the last Wi-Fi scan and then asked for by that exact name; only with no scan
        // answer is it asked for by the prefix.
        val known = pairing.ssid.takeIf { isDashName(it) } ?: pairing.discover(pairing.ssidPrefix)?.takeIf { isFullName(it) }?.also { pairing.remember(it) }
        val wanted = known ?: pairing.ssidPrefix
        // A reconnection of the manager's own, for this dash, is waited for, not replaced.
        if (joinsReconnection(now, wanted, joining.underWay())) {
            joining.log("the bike's Wi-Fi is being got back already: waiting for it instead of asking anew")
            watch = scope.launch {
                val outcome = withTimeoutOrNull(joining.waitMs) {
                    wifiState.first { it.status == WifiConnStatus.CONNECTED || it.status == WifiConnStatus.ERROR }
                }
                if (outcome == null) onFailed(RECONNECT_NOT_BACK_YET) else report(outcome, onReady, onFailed)
            }
            return
        }
        // A network the rider never approved AND that the scan sees: Android stops to ask them, and the screen says so while
        // the request waits. With the dash out of range (bike off) no dialog ever comes, so the screen must not promise one;
        // each new request looks at the scan again, so the hint shows up once the dash is seen.
        val firstApproval = DashJoin.needsApproval(pairing.joinedSsid, wanted, known == null)
        val seen = firstApproval && pairing.seenInScan(wanted, known == null)
        firstJoinListener?.invoke(seen)
        // Worse than no hint: with the dash away, the request of a first approval still opens Android's "Connect to device"
        // dialog over the rider's phone ("no device found"), again at every request. So it is not made until the scan
        // sees the dash; meanwhile this start fails at once and the projection's own retries keep looking (silent, the
        // screen says it waits for the dash). A network already approved opens no dialog and is asked for as always.
        if (firstApproval && !seen) {
            if (!waitingForSighting) joining.log("first approval pending and the dash is not in the recent scan: not asking Android yet, only looking")
            waitingForSighting = true
            onFailed(DASH_NOT_SEEN_YET)
        } else {
            waitingForSighting = false
            ask(known, onReady, onFailed)
        }
    }

    /** Asks the Wi-Fi manager for the bike's network, by the exact [known] name or, with none, by the prefix. */
    private fun ask(known: String?, onReady: (String, Network?) -> Unit, onFailed: (String) -> Unit) {
        // Listening before asking: whatever the Wi-Fi says from here on is about this attempt.
        watch = scope.launch {
            val outcome = wifiState.drop(1).first { it.status == WifiConnStatus.CONNECTED || it.status == WifiConnStatus.ERROR }
            report(outcome, onReady, onFailed)
        }
        if (known != null) connect(known, pairing.password, false) else connect(pairing.ssidPrefix, pairing.password, true)
    }

    private fun isDashName(ssid: String): Boolean = DashName.isDashName(ssid)

    private fun isFullName(ssid: String): Boolean = DashName.isFullName(ssid, pairing.ssidPrefix)

    override val bikeNetwork: Network? get() = network()

    override val dashVisible: Boolean get() = wifiState.value.let { it.status == WifiConnStatus.ERROR && it.dashVisible }

    override fun stop() {
        watch?.cancel()
        watch = null
        waitingForSighting = false
        disconnect()
    }

    private fun report(state: WifiState, onReady: (String, Network?) -> Unit, onFailed: (String) -> Unit) {
        if (state.status == WifiConnStatus.ERROR) {
            // The reason goes to the ride log: the words of a stuck dash say the default password.
            onFailed(withoutPasswords(state.error ?: "Wi-Fi do painel falhou", pairing.password, DashPairing.DEFAULT_PASSWORD))
            return
        }
        // Connected by the prefix, the Wi-Fi may report the prefix itself as the name; that is neither
        // remembered nor given to the session (the dash would reject every handshake).
        val reported = state.ssid.takeIf { isFullName(it) }
        val ssid = reported ?: pairing.ssid.takeIf { isDashName(it) }
        if (ssid == null) {
            onFailed(if (state.ssid.isBlank()) "nome do Wi-Fi do painel desconhecido" else FULL_NAME_NEEDED)
            return
        }
        if (reported != null && reported != pairing.ssid) pairing.remember(reported)
        firstJoinListener?.invoke(false)
        onReady(ssid, network())
    }
}
