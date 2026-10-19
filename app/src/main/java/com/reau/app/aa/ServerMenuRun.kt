package com.reau.app.aa

/**
 * One walk through Android Auto's settings to switch its head unit server on ([Mode.START]) or off ([Mode.STOP]):
 * open the settings, wait for the ⋮ button, open the menu, read it, touch the one item that is wanted, go back.
 * Pure: it is shown what the screen holds ([Seen]) and answers with the next thing to do ([Step]); the accessibility
 * service carries it out.
 *
 * The rules that keep the rider's Android Auto safe:
 * - an item is touched only when its text equals the wanted one ([ServerTexts]), never by place, and never one of the
 *   developer items ("Sair do modo de desenvolvedor" is right under the server's);
 * - one touch of the wanted item per walk; "Versão" (the 10 taps of developer mode) is never touched;
 * - the phone locked, another app in front or "Cancelar" on the cover: it stops at once, with no other touch.
 *
 * In [Mode.START], a menu that shows "stop" (Android Auto holds the server as running, yet nothing answered) restarts
 * it once when [restartMarkedRunning]: "stop", [RESTART_PAUSE_MS], the menu again, "start".
 */
// One walk's life: a function per phase, and the few calls the service makes into it; split, they would only pass the
// same state around.
@Suppress("TooManyFunctions")
class ServerMenuRun(
    val mode: Mode,
    private val texts: ServerTexts,
    startMs: Long,
    private val restartMarkedRunning: Boolean = mode == Mode.START,
    private val timing: Timing = Timing(),
    /** The app whose menu is walked: Android Auto; a test's own screen on the emulator. */
    val target: String = AndroidAuto.PACKAGE,
) {
    enum class Mode { START, STOP }

    /** The longest each part may take; the walk moves on as soon as the screen allows. */
    data class Timing(
        val totalMs: Long = 10_000,
        val openMs: Long = 4_000,
        val overflowMs: Long = 3_000,
        val quietMs: Long = 400,
        val listMs: Long = 1_500,
        val listSteadyMs: Long = 300,
        val reopenPauseMs: Long = 500,
        val listCloseMs: Long = 600,
        val backEveryMs: Long = 500,
        val leaveMs: Long = 2_500,
    )

    /** What the screen holds, as the accessibility service sees it. */
    data class Seen(
        val nowMs: Long,
        /** The package of the window in front; null when none is known. */
        val front: String?,
        /** Locked, or the screen off. */
        val locked: Boolean,
        /** Every node with a text or a description in Android Auto's windows. */
        val nodes: List<Node>,
        /** When Android Auto last said its content changed (0: never). */
        val lastChangeMs: Long = 0,
    )

    /** One node: its [window], what it says, whether it or an ancestor can be clicked, and [ref] for the service. */
    data class Node(val window: Int, val text: String?, val description: String?, val clickable: Boolean, val ref: Int)

    sealed interface Step {
        /** Open Android Auto's settings ([AndroidAuto]'s DefaultSettingsActivity). */
        data object OpenSettings : Step

        /** Click [node] (its first clickable ancestor); [what] for the log. Say [clickRefused] if Android said no. */
        data class Click(val node: Node, val what: String) : Step

        data object Back : Step

        /** Bring REau to the front: Android Auto did not go with "back". */
        data object OpenREau : Step

        data object Wait : Step

        data class Done(val outcome: Outcome) : Step
    }

    enum class Outcome {
        /** "Start" was touched; whether the port answers is checked by the caller. */
        TAPPED_START,

        /** "Stop" was touched. */
        TAPPED_STOP,

        /** [Mode.START]: the menu shows "stop" and no restart was allowed (or it was already done once). */
        ALREADY_RUNNING,

        /** [Mode.STOP]: the menu shows "start": nothing to stop. */
        ALREADY_STOPPED,
        DEVELOPER_MODE_OFF,
        UNKNOWN_MENU,
        MENU_DID_NOT_OPEN,
        UNEXPECTED_SCREEN,
        SETTINGS_DID_NOT_OPEN,
        CLICK_REFUSED,
        TIMED_OUT,
        LOCKED,
        OTHER_APP,
        CANCELLED,
    }

    private enum class Phase { OPENING, SETTLING, MENU, LIST, LEAVING, FINISHED }

    private var phase = Phase.OPENING
    private var phaseSince = startMs
    private var deadline = startMs + timing.totalMs
    private var outcome: Outcome? = null
    private var opened = false
    private var cancelled = false

    // The window of the ⋮ button: the menu's list is any other window of Android Auto.
    private var overflowWindow: Int? = null
    private var overflowClicks = 0
    private var halfMenus = 0
    private var pauseUntil = 0L
    private var listTexts: List<String>? = null
    private var listSteadySince = 0L
    private var backs = 0
    private var lastBackMs = 0L

    /** Whether a restart ("stop", then "start") happened in this walk. */
    var restarted = false
        private set

    /**
     * Where the walk is, for the cover's three steps: 0 opening Android Auto, 1 its developer
     * menu, 2 starting (or stopping) the server in the menu's list, 3 every step done.
     */
    val coverStep: Int get() = when (phase) {
        Phase.OPENING -> 0
        Phase.SETTLING, Phase.MENU -> 1
        Phase.LIST -> 2
        Phase.LEAVING, Phase.FINISHED -> 3
    }

    /** The texts of the last menu read, for the log ("texts: …"). */
    var lastMenu: List<String> = emptyList()
        private set

    /** The step after [seen]. */
    @Suppress("ReturnCount") // what ends a walk in any phase comes first, each its own early answer
    fun next(seen: Seen): Step {
        if (phase == Phase.FINISHED) return Step.Done(checkNotNull(outcome))
        // Cancelled on the way back, the result stays (a touch already made counts); either way, no other touch.
        if (cancelled) return finish(if (phase == Phase.LEAVING) checkNotNull(outcome) else Outcome.CANCELLED)
        if (phase == Phase.LEAVING) return leaving(seen)
        interruption(seen)?.let { return finish(it) }
        if (seen.nowMs >= deadline) return leave(Outcome.TIMED_OUT, seen)
        return when (phase) {
            Phase.OPENING -> opening(seen)
            Phase.SETTLING -> settling(seen)
            Phase.MENU -> menu(seen)
            Phase.LIST -> list(seen)
            Phase.LEAVING, Phase.FINISHED -> Step.Wait
        }
    }

    /** The phone locked, or (once Android Auto has opened) another app in front: the walk stops with no touch. */
    private fun interruption(seen: Seen): Outcome? {
        val elsewhere = seen.front != null && seen.front != target && seen.front != REAU
        return when {
            seen.locked -> Outcome.LOCKED
            phase != Phase.OPENING && elsewhere -> Outcome.OTHER_APP
            else -> null
        }
    }

    /** "Cancelar" on the cover: the walk ends with no other touch. */
    fun cancel() {
        cancelled = true
    }

    /** Android could not open the settings. */
    fun openFailed() {
        if (phase != Phase.FINISHED) {
            outcome = Outcome.SETTINGS_DID_NOT_OPEN
            phase = Phase.FINISHED
        }
    }

    /** The last [Step.Click] was refused: no other touch, the walk leaves. */
    fun clickRefused(nowMs: Long) {
        if (phase == Phase.FINISHED) return
        outcome = Outcome.CLICK_REFUSED
        enter(Phase.LEAVING, nowMs)
    }

    private fun opening(seen: Seen): Step {
        if (!opened) {
            opened = true
            return Step.OpenSettings
        }
        if (seen.front == target) {
            enter(Phase.SETTLING, seen.nowMs)
            return settling(seen)
        }
        return if (seen.nowMs - phaseSince >= timing.openMs) leave(Outcome.SETTINGS_DID_NOT_OPEN, seen) else Step.Wait
    }

    private fun settling(seen: Seen): Step {
        val overflow = overflowOf(seen)
        if (overflow != null && seen.nowMs - seen.lastChangeMs >= timing.quietMs) {
            overflowWindow = overflow.window
            enter(Phase.MENU, seen.nowMs)
            return menu(seen)
        }
        return if (seen.nowMs - phaseSince >= timing.overflowMs) leave(Outcome.UNEXPECTED_SCREEN, seen) else Step.Wait
    }

    private fun menu(seen: Seen): Step {
        if (seen.nowMs < pauseUntil || listOpen(seen)) return Step.Wait
        val overflow = overflowOf(seen)
            ?: return if (seen.nowMs - phaseSince >= timing.overflowMs) leave(Outcome.UNEXPECTED_SCREEN, seen) else Step.Wait
        overflowWindow = overflow.window
        overflowClicks++
        listTexts = null
        enter(Phase.LIST, seen.nowMs)
        return Step.Click(overflow, "more options")
    }

    @Suppress("ReturnCount") // the waits first, each its own early answer; then the reading
    private fun list(seen: Seen): Step {
        val items = listNodes(seen)
        val shown = items.mapNotNull { it.text?.takeIf(String::isNotBlank) }
        if (shown.isEmpty()) {
            if (seen.nowMs - phaseSince < timing.listMs) return Step.Wait
            return if (overflowClicks < MAX_OVERFLOW_CLICKS) {
                enter(Phase.MENU, seen.nowMs)
                menu(seen)
            } else {
                leave(Outcome.MENU_DID_NOT_OPEN, seen)
            }
        }
        if (shown != listTexts) {
            listTexts = shown
            listSteadySince = seen.nowMs
        }
        if (seen.nowMs - listSteadySince < timing.listSteadyMs) return Step.Wait
        lastMenu = shown
        return when (texts.read(shown)) {
            ServerTexts.Reading.START_SHOWN ->
                if (mode == Mode.START) tap(items.first { texts.isStart(it.text) }, Outcome.TAPPED_START, seen) else leave(Outcome.ALREADY_STOPPED, seen)
            ServerTexts.Reading.STOP_SHOWN -> stopShown(items.first { texts.isStop(it.text) }, seen)
            ServerTexts.Reading.UNKNOWN_DEVELOPER_MENU -> leave(Outcome.UNKNOWN_MENU, seen)
            // The first time it may be the menu drawn before the settings loaded; the second, the mode is off.
            ServerTexts.Reading.DEVELOPER_MODE_OFF ->
                if (++halfMenus < 2) reopen(seen) else leave(Outcome.DEVELOPER_MODE_OFF, seen)
            ServerTexts.Reading.UNREADABLE ->
                if (overflowClicks < MAX_OVERFLOW_CLICKS) reopen(seen) else leave(Outcome.MENU_DID_NOT_OPEN, seen)
        }
    }

    private fun stopShown(stop: Node, seen: Seen): Step = when {
        mode == Mode.STOP -> tap(stop, Outcome.TAPPED_STOP, seen)
        restartMarkedRunning && !restarted -> {
            // Marked as running and not answering: stopped, and started again after a pause, once.
            restarted = true
            deadline += RESTART_EXTRA_MS
            overflowClicks = 0
            halfMenus = 0
            pauseUntil = seen.nowMs + RESTART_PAUSE_MS
            enter(Phase.MENU, seen.nowMs)
            Step.Click(stop, "stop (restart)")
        }
        else -> leave(Outcome.ALREADY_RUNNING, seen)
    }

    private fun tap(node: Node, result: Outcome, seen: Seen): Step {
        outcome = result
        enter(Phase.LEAVING, seen.nowMs)
        return Step.Click(node, if (result == Outcome.TAPPED_START) "start" else "stop")
    }

    /** The list closed with "back", and the ⋮ touched again after a pause. */
    private fun reopen(seen: Seen): Step {
        pauseUntil = seen.nowMs + timing.reopenPauseMs
        enter(Phase.MENU, seen.nowMs)
        return Step.Back
    }

    private fun leave(result: Outcome, seen: Seen): Step {
        outcome = result
        enter(Phase.LEAVING, seen.nowMs)
        return leaving(seen)
    }

    /**
     * Back to where the rider was: the list closes, then "back" while Android Auto is in front, up to three, then
     * REau itself. Locked or another app in front: it ends with no touch.
     */
    @Suppress("ReturnCount") // one early answer per rule of the way back, in the order they apply
    private fun leaving(seen: Seen): Step {
        val result = checkNotNull(outcome)
        if (seen.locked || seen.front != target) return finish(result)
        val waited = seen.nowMs - phaseSince
        if (waited >= timing.leaveMs) return finishWith(Step.OpenREau, result)
        if (listOpen(seen) && waited < timing.listCloseMs) return Step.Wait
        if (backs > 0 && seen.nowMs - lastBackMs < timing.backEveryMs) return Step.Wait
        if (backs >= MAX_BACKS) return finishWith(Step.OpenREau, result)
        backs++
        lastBackMs = seen.nowMs
        return Step.Back
    }

    private fun finish(result: Outcome): Step {
        outcome = result
        phase = Phase.FINISHED
        return Step.Done(result)
    }

    /** [step] now; the walk is over and the next call says [result]. */
    private fun finishWith(step: Step, result: Outcome): Step {
        outcome = result
        phase = Phase.FINISHED
        return step
    }

    private fun enter(next: Phase, nowMs: Long) {
        phase = next
        phaseSince = nowMs
    }

    private fun overflowOf(seen: Seen): Node? = seen.nodes.firstOrNull { it.clickable && texts.isMoreOptions(it.description) }

    /** The menu's list: Android Auto's nodes in a window other than the ⋮ button's. */
    private fun listNodes(seen: Seen): List<Node> {
        val home = overflowWindow ?: return emptyList()
        return seen.nodes.filter { it.window != home }
    }

    private fun listOpen(seen: Seen) = listNodes(seen).isNotEmpty()

    companion object {
        const val REAU = "com.reau.app"

        /** Between "stop" and "start" in a restart (as HeadQLink does). */
        const val RESTART_PAUSE_MS = 1_500L

        /** A restart reads the menu twice: the walk gets this much longer. */
        const val RESTART_EXTRA_MS = 4_000L
        private const val MAX_OVERFLOW_CLICKS = 2
        private const val MAX_BACKS = 3
    }
}
