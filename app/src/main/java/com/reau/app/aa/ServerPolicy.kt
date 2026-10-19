package com.reau.app.aa

/**
 * What the automatic switch of Android Auto's head unit server says on the screen and in the log. [NONE]: nothing
 * tried yet.
 */
enum class ServerResult {
    NONE,

    /** A walk through Android Auto's menu is going on: switching the server on. */
    WORKING,

    /** A walk through Android Auto's menu is going on: switching the server off. */
    STOPPING,

    /** The port answered after "start" was touched. */
    STARTED,
    SERVICE_OFF,
    LOCKED,
    NOT_INSTALLED,
    SETTINGS_DID_NOT_OPEN,
    UNEXPECTED_SCREEN,
    MENU_DID_NOT_OPEN,
    DEVELOPER_MODE_OFF,
    UNKNOWN_MENU,

    /** The menu shows "stop" (the server marked running) and the port does not answer. */
    ALREADY_RUNNING,

    /** "Start" touched, and the port still closed after the wait. */
    NOT_UP,
    INTERRUPTED,

    /** Switched off after the projection: the notification's "Stop", or the menu's. */
    STOPPED,

    /** The projection ended and the server could not be switched off (no "Stop" button kept, phone locked). */
    LEFT_ON,
    ;

    companion object {
        /** What a walk's [outcome] means for the rider; a touched "start" waits for the port ([WORKING]). */
        /** What a walk in [mode] says while it goes on. */
        fun working(mode: ServerMenuRun.Mode): ServerResult = if (mode == ServerMenuRun.Mode.STOP) STOPPING else WORKING

        internal fun of(outcome: ServerMenuRun.Outcome): ServerResult = when (outcome) {
            ServerMenuRun.Outcome.TAPPED_START -> WORKING
            ServerMenuRun.Outcome.TAPPED_STOP, ServerMenuRun.Outcome.ALREADY_STOPPED -> STOPPED
            ServerMenuRun.Outcome.ALREADY_RUNNING -> ALREADY_RUNNING
            ServerMenuRun.Outcome.DEVELOPER_MODE_OFF -> DEVELOPER_MODE_OFF
            ServerMenuRun.Outcome.UNKNOWN_MENU -> UNKNOWN_MENU
            ServerMenuRun.Outcome.MENU_DID_NOT_OPEN, ServerMenuRun.Outcome.CLICK_REFUSED -> MENU_DID_NOT_OPEN
            ServerMenuRun.Outcome.UNEXPECTED_SCREEN -> UNEXPECTED_SCREEN
            ServerMenuRun.Outcome.SETTINGS_DID_NOT_OPEN -> SETTINGS_DID_NOT_OPEN
            ServerMenuRun.Outcome.LOCKED, ServerMenuRun.Outcome.OTHER_APP, ServerMenuRun.Outcome.CANCELLED,
            ServerMenuRun.Outcome.TIMED_OUT -> INTERRUPTED
        }
    }
}

/**
 * Whether a walk may begin, in the order the rider would fix them: the switch, the
 * accessibility service, the phone unlocked with the screen on, Android Auto there. Null: go.
 */
internal object ServerGate {
    data class Phone(val serviceConnected: Boolean, val locked: Boolean, val androidAutoSettings: Boolean)

    fun refusal(phone: Phone): ServerResult? = when {
        !phone.serviceConnected -> ServerResult.SERVICE_OFF
        phone.locked -> ServerResult.LOCKED
        !phone.androidAutoSettings -> ServerResult.NOT_INSTALLED
        else -> null
    }
}

/**
 * When, during one projection, the server is switched on, restarted or off: told each state of
 * the link to Android Auto, it answers what to do. One try to start per switching on (the first dial that finds the
 * port closed), one restart (the link says Android Auto accepts and does not answer), and, when the projection ends,
 * a stop only if the server was seen up. Whether a try may really be made is [ServerGate]'s. Each projection has its
 * number ([started]); what an older one says while it ends is not the newer one's.
 */
internal class ServerPolicy {
    enum class Act { NOTHING, START, RESTART, ANSWERED }

    private var triedStart = false
    private var triedRestart = false
    private var closed = false

    // The link's last word is UNRESPONSIVE: the server accepts and stays mute, and nothing has answered since.
    private var mute = false
    private var seenUp = false
    private var live = false
    private var generation = 0

    /** The projection switched on (Android Auto's side started): a new try of each. Returns this projection's number. */
    fun started(): Int {
        generation++
        live = true
        triedStart = false
        triedRestart = false
        closed = false
        mute = false
        seenUp = false
        return generation
    }

    /** Whether the newest projection has seen the server up: its end will switch it off (unless it ends first). */
    fun serverSeenUp(): Boolean = seenUp

    /** Whether [of] is the newest projection. */
    fun isCurrent(of: Int): Boolean = of == generation

    /** A state of the link of projection [of]; one of an older projection (still ending) changes nothing. */
    fun state(state: AaLinkState, of: Int = generation): Act = if (of != generation) Act.NOTHING else when (state) {
        AaLinkState.SERVER_OFF -> {
            closed = true
            mute = false
            if (triedStart) Act.NOTHING else Act.START.also { triedStart = true }
        }
        AaLinkState.UNRESPONSIVE -> {
            seenUp = true
            mute = true
            if (triedRestart) Act.NOTHING else Act.RESTART.also { triedRestart = true }
        }
        // Still closing the session REau's own stop ended (~70 s): it clears by itself, as the screen says. Stopping and
        // starting the server inside that window is what made Android Auto fall over with "Already connected" (seen in
        // use), so it is a passive wait: the restart is not spent. If the window ends with the server still mute,
        // the link says UNRESPONSIVE and the one restart happens then; if the port closes, SERVER_OFF starts it.
        AaLinkState.CLOSING_PREVIOUS -> Act.NOTHING.also { seenUp = true; mute = false }
        // A dial that went through after the port was closed: the server came up.
        AaLinkState.CONNECTING -> (if (closed) Act.ANSWERED.also { closed = false; seenUp = true } else Act.NOTHING).also { mute = false }
        AaLinkState.ACTIVE, AaLinkState.DROPPED -> Act.NOTHING.also { seenUp = true; closed = false; mute = false }
        AaLinkState.IDLE, AaLinkState.NOT_CALLING -> Act.NOTHING.also { mute = false }
    }

    /**
     * Projection [of] ended: whether the server is to be switched off (only one seen up, never one that never
     * answered). Never when a newer projection already began: turned off and on again in under
     * 2 s, the old one's end comes after the new one's start, and the server is the new one's.
     */
    fun stopped(of: Int = generation): Boolean {
        if (of != generation) return false
        live = false
        return seenUp.also { seenUp = false }
    }

    /**
     * Whether the accessibility service that has just connected should bring the server's switching on back at once: the
     * projection goes on, the last try was refused for want of that very service ([last]), and the link still says what
     * that try was for: the port is closed (the start), or the server accepts and stays mute (the restart, whose one
     * try per projection the refusal had spent). Then the rider need not touch "Tentar de novo". Never over a link that
     * has answered since (ACTIVE, DROPPED...): a restart there would drop a session that is working.
     */
    fun retryOnServiceConnected(last: ServerResult): Boolean = live && (closed || mute) && last == ServerResult.SERVICE_OFF
}

/**
 * Who has to restart Android Auto's head unit server when it accepts and stays mute ([AaLinkState.UNRESPONSIVE]), and so
 * which text the rider is given: the app itself (the full edition with the automatic switch on and the accessibility
 * service connected, [byRider] false) or the rider, in the ⋮ menu of Android Auto (the Lite edition, which has no
 * service, or the full one while the switch is off or the service not connected).
 */
internal object MuteServerFix {
    fun byRider(hasAccessibility: Boolean, auto: Boolean, serviceConnected: Boolean): Boolean =
        !(hasAccessibility && auto && serviceConnected)
}

/**
 * Whether the port answered after "start" was touched. The receiver keeps dialling on its own pace while
 * the walk goes back, so the answer may come before the walk has ended (observed on a device): kept,
 * it counts when the walk ends, instead of the wait running out over a server that is up.
 */
internal class PortWait {
    private var walking = false
    private var answeredInWalk = false
    private var since = 0L

    /** A walk to start the server began. */
    fun walkBegun() {
        walking = true
        answeredInWalk = false
    }

    /** The walk ended without touching "start" (what it heard is dropped by the next [walkBegun]). */
    fun walkEnded() {
        walking = false
    }

    /**
     * The walk ended having touched "start", at [nowMs]: true if the port already answered during it; otherwise the
     * wait begins, and [answered] or [expired] ends it.
     */
    fun tapped(nowMs: Long): Boolean {
        val already = answeredInWalk
        walkEnded()
        since = if (already) 0 else nowMs
        return already
    }

    /** The port answered at [nowMs]: how long after the touched "start" it took, or null when nothing waited for it. */
    fun answered(nowMs: Long): Long? {
        if (since != 0L) return (nowMs - since).also { since = 0 }
        if (walking) answeredInWalk = true
        return null
    }

    /** The wait that began at [begunMs] ran out: true if it was still waiting (the server did not come up). */
    fun expired(begunMs: Long): Boolean = (since != 0L && since == begunMs).also { if (it) since = 0 }
}

/**
 * Whether a notification Android Auto posted is its head unit server's, whose one
 * action is the "Stop" that switches the server off without opening a screen. Strict, because Android Auto posts its
 * wireless and permission notices on the same channel.
 */
internal object ServerNotification {
    const val CHANNEL = "gearhead_connection_status"

    data class Seen(
        val channel: String?,
        val actions: Int,
        val creator: String?,
        /** Whether the action starts a service (API 31+); null below, where it is not known. */
        val isService: Boolean?,
        val actionTitle: String?,
        val text: String?,
    )

    fun isServerStop(seen: Seen, texts: ServerTexts): Boolean =
        seen.channel == CHANNEL &&
            seen.actions == 1 &&
            seen.creator == AndroidAuto.PACKAGE &&
            seen.isService != false &&
            (texts.isNotificationStop(seen.actionTitle) || texts.isNotificationText(seen.text))
}
