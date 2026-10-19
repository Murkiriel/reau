package com.reau.app.aa

import com.reau.app.aa.ServerMenuRun.Outcome
import com.reau.app.aa.ServerMenuRun.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One walk through Android Auto's menu, against a pretend screen.
 */
class ServerMenuRunTest {
    private val aa = AndroidAuto.PACKAGE
    private val texts = ServerTexts.table()

    private val full = listOf("Ajuda e feedback", "Configurações do desenvolvedor", "Iniciar servidor da unidade principal", "Sair do modo de desenvolvedor")
    private val running = listOf("Ajuda e feedback", "Configurações do desenvolvedor", "Parar servidor da unid. princ.", "Sair do modo de desenvolvedor")
    private val half = listOf("Ajuda e feedback", "Enviar feedback")

    /** Android Auto's settings with the ⋮ button (window 1) and, when [menu] is not null, the menu's list (window 2). */
    private fun screen(now: Long, menu: List<String>? = null, front: String? = aa, locked: Boolean = false, overflow: Boolean = true, changed: Long = 0) =
        ServerMenuRun.Seen(
            nowMs = now,
            front = front,
            locked = locked,
            nodes = buildList {
                add(ServerMenuRun.Node(1, "Android Auto", null, clickable = false, ref = 0))
                if (overflow) add(ServerMenuRun.Node(1, null, "Mais opções", clickable = true, ref = 1))
                menu?.forEachIndexed { i, text -> add(ServerMenuRun.Node(2, text, null, clickable = true, ref = 10 + i)) }
            },
            lastChangeMs = changed,
        )

    private fun elsewhere(now: Long, front: String = ServerMenuRun.REAU) = ServerMenuRun.Seen(now, front, false, emptyList())

    /** Opens the settings and the menu: the run is left waiting for the list at [at]. */
    private fun toTheMenu(run: ServerMenuRun, at: Long = 0): Long {
        assertEquals(Step.OpenSettings, run.next(elsewhere(at)))
        val click = run.next(screen(at + 500))
        assertTrue("$click", click is Step.Click && click.what == "more options")
        return at + 500
    }

    /** Shows [menu] until the run answers something other than waiting; that answer and the time. */
    private fun showMenu(run: ServerMenuRun, menu: List<String>, from: Long): Pair<Step, Long> {
        var now = from
        while (true) {
            now += 100
            val step = run.next(screen(now, menu))
            if (step != Step.Wait) return step to now
        }
    }

    @Test
    fun `the walk opens the settings, the menu, touches start and goes back`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        val t = toTheMenu(run)
        val (tap, at) = showMenu(run, full, t)
        assertEquals("Iniciar servidor da unidade principal", (tap as Step.Click).node.text)
        assertEquals("start", tap.what)
        // Back while Android Auto is in front, then REau is: done.
        assertEquals(Step.Back, run.next(screen(at + 700)))
        assertEquals(Step.Done(Outcome.TAPPED_START), run.next(elsewhere(at + 900)))
        assertEquals(Step.Done(Outcome.TAPPED_START), run.next(elsewhere(at + 1_000)))
        assertEquals(full, run.lastMenu)
    }

    @Test
    fun `the cover follows the walk, step by step`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        assertEquals("before anything", 0, run.coverStep)
        assertEquals(Step.OpenSettings, run.next(elsewhere(0)))
        assertEquals("opening Android Auto", 0, run.coverStep)
        assertEquals(Step.Wait, run.next(screen(300, overflow = false)))
        assertEquals("in Android Auto, before its menu", 1, run.coverStep)
        val t = 600L.also { assertTrue(run.next(screen(it)) is Step.Click) }
        assertEquals("the menu's list", 2, run.coverStep)
        val (tap, at) = showMenu(run, full, t)
        assertTrue(tap is Step.Click)
        assertEquals("start touched: done", 3, run.coverStep)
        run.next(screen(at + 700))
        run.next(elsewhere(at + 900))
        assertEquals(3, run.coverStep)
    }

    @Test
    fun `the item touched is the one whose text is start, wherever it sits`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        val shuffled = listOf("Sair do modo de desenvolvedor", "Iniciar servidor da unidade principal", "Configurações do desenvolvedor")
        val (tap, _) = showMenu(run, shuffled, toTheMenu(run))
        assertEquals(11, (tap as Step.Click).node.ref)
    }

    @Test
    fun `the list must hold still before it is read`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        val t = toTheMenu(run)
        assertEquals(Step.Wait, run.next(screen(t + 100, half)))
        assertEquals(Step.Wait, run.next(screen(t + 300, full)))
        // 300 ms after the last change.
        assertEquals(Step.Wait, run.next(screen(t + 550, full)))
        assertTrue(run.next(screen(t + 600, full)) is Step.Click)
    }

    @Test
    fun `the overflow waits for the settings to stop changing`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        run.next(elsewhere(0))
        assertEquals(Step.Wait, run.next(screen(500, changed = 300)))
        assertEquals(Step.Wait, run.next(screen(650, changed = 300)))
        assertTrue(run.next(screen(700, changed = 300)) is Step.Click)
    }

    @Test
    fun `a changed menu touches nothing`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        val (step, at) = showMenu(run, listOf("Ajuda e feedback", "Configurações do desenvolvedor", "Sair do modo de desenvolvedor"), toTheMenu(run))
        // The list closes with back, never with a touch on an item.
        assertEquals(Step.Back, step)
        assertEquals(Step.Done(Outcome.UNKNOWN_MENU), run.next(elsewhere(at + 100)))
    }

    @Test
    fun `the way back waits for the list to close before back`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        val (_, at) = showMenu(run, full, toTheMenu(run))
        // The touched item's list is still drawn: a back now would be swallowed by its closing.
        assertEquals(Step.Wait, run.next(screen(at + 100, full)))
        assertEquals(Step.Wait, run.next(screen(at + 500, full)))
        assertEquals(Step.Back, run.next(screen(at + 600, full)))
    }

    @Test
    fun `the half menu is read again once, and the full one is then touched`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        val (back, at) = showMenu(run, half, toTheMenu(run))
        assertEquals(Step.Back, back)
        // The list closes; after the pause the ⋮ is touched again.
        assertEquals(Step.Wait, run.next(screen(at + 200)))
        val again = run.next(screen(at + 600))
        assertTrue(again is Step.Click && again.what == "more options")
        val (tap, _) = showMenu(run, full, at + 600)
        assertEquals("start", (tap as Step.Click).what)
    }

    @Test
    fun `the help items twice mean developer mode off`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        val (back, at) = showMenu(run, half, toTheMenu(run))
        assertEquals(Step.Back, back)
        assertTrue(run.next(screen(at + 600)) is Step.Click)
        // The list is left 600 ms to close, then back.
        val (leave, at2) = showMenu(run, half, at + 600)
        assertEquals(Step.Back, leave)
        assertEquals(Step.Done(Outcome.DEVELOPER_MODE_OFF), run.next(elsewhere(at2 + 100)))
    }

    @Test
    fun `a server marked running is restarted once, stop then a pause then start`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        val (stop, at) = showMenu(run, running, toTheMenu(run))
        assertEquals("stop (restart)", (stop as Step.Click).what)
        assertEquals("Parar servidor da unid. princ.", stop.node.text)
        assertEquals(Step.Wait, run.next(screen(at + 1_000)))
        val again = run.next(screen(at + ServerMenuRun.RESTART_PAUSE_MS))
        assertTrue(again is Step.Click && again.what == "more options")
        val (start, _) = showMenu(run, full, at + ServerMenuRun.RESTART_PAUSE_MS)
        assertEquals("start", (start as Step.Click).what)
        assertTrue(run.restarted)
    }

    @Test
    fun `a second stop in the same walk is not touched`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        val (_, at) = showMenu(run, running, toTheMenu(run))
        run.next(screen(at + ServerMenuRun.RESTART_PAUSE_MS))
        val (step, at2) = showMenu(run, running, at + ServerMenuRun.RESTART_PAUSE_MS)
        assertFalse(step is Step.Click)
        assertEquals(Step.Done(Outcome.ALREADY_RUNNING), run.next(elsewhere(at2 + 100)))
    }

    @Test
    fun `without the restart, stop shown is already running`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0, restartMarkedRunning = false)
        val (step, at) = showMenu(run, running, toTheMenu(run))
        assertFalse(step is Step.Click)
        assertEquals(Step.Done(Outcome.ALREADY_RUNNING), run.next(elsewhere(at + 100)))
    }

    @Test
    fun `switching off touches stop`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.STOP, texts, 0)
        val (tap, at) = showMenu(run, running, toTheMenu(run))
        assertEquals("stop", (tap as Step.Click).what)
        assertEquals(Step.Done(Outcome.TAPPED_STOP), run.next(elsewhere(at + 100)))
    }

    @Test
    fun `switching off a stopped server touches nothing`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.STOP, texts, 0)
        val (step, at) = showMenu(run, full, toTheMenu(run))
        assertFalse(step is Step.Click)
        assertEquals(Step.Done(Outcome.ALREADY_STOPPED), run.next(elsewhere(at + 100)))
    }

    @Test
    fun `the phone locked stops the walk at once, with no other touch`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        val t = toTheMenu(run)
        assertEquals(Step.Done(Outcome.LOCKED), run.next(screen(t + 100, full, locked = true)))
        assertEquals(Step.Done(Outcome.LOCKED), run.next(screen(t + 200, full)))
    }

    @Test
    fun `another app in front stops the walk at once`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        val t = toTheMenu(run)
        assertEquals(Step.Done(Outcome.OTHER_APP), run.next(screen(t + 100, front = "com.whatsapp")))
    }

    @Test
    fun `another app in front before Android Auto opened is waited out`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        run.next(elsewhere(0))
        assertEquals(Step.Wait, run.next(elsewhere(300, front = "com.whatsapp")))
    }

    @Test
    fun `settings that never come are given up after 4 s, with no back`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        run.next(elsewhere(0))
        assertEquals(Step.Wait, run.next(elsewhere(3_900)))
        assertEquals(Step.Done(Outcome.SETTINGS_DID_NOT_OPEN), run.next(elsewhere(4_000)))
    }

    @Test
    fun `settings that could not be opened end the walk`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        run.next(elsewhere(0))
        run.openFailed()
        assertEquals(Step.Done(Outcome.SETTINGS_DID_NOT_OPEN), run.next(elsewhere(100)))
    }

    @Test
    fun `no overflow in 3 s is an unexpected screen, left with back`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        run.next(elsewhere(0))
        assertEquals(Step.Wait, run.next(screen(500, overflow = false)))
        assertEquals(Step.Back, run.next(screen(3_500, overflow = false)))
        assertEquals(Step.Done(Outcome.UNEXPECTED_SCREEN), run.next(elsewhere(3_600)))
    }

    @Test
    fun `a list that never shows gets one more touch, then the walk leaves`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        val t = toTheMenu(run)
        assertEquals(Step.Wait, run.next(screen(t + 1_400)))
        val again = run.next(screen(t + 1_500))
        assertTrue(again is Step.Click && again.what == "more options")
        assertEquals(Step.Back, run.next(screen(t + 3_000)))
        assertEquals(Step.Done(Outcome.MENU_DID_NOT_OPEN), run.next(elsewhere(t + 3_100)))
    }

    @Test
    fun `an unreadable list is read once more`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        val (back, at) = showMenu(run, listOf("Bluetooth"), toTheMenu(run))
        assertEquals(Step.Back, back)
        run.next(screen(at + 600))
        val (leave, at2) = showMenu(run, listOf("Bluetooth"), at + 600)
        assertFalse(leave is Step.Click)
        assertEquals(Step.Done(Outcome.MENU_DID_NOT_OPEN), run.next(elsewhere(at2 + 100)))
    }

    @Test
    fun `cancel ends the walk with no other step`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        val t = toTheMenu(run)
        run.cancel()
        assertEquals(Step.Done(Outcome.CANCELLED), run.next(screen(t + 100, full)))
    }

    @Test
    fun `cancel on the way back keeps the touch made`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        val (_, at) = showMenu(run, full, toTheMenu(run))
        run.cancel()
        assertEquals(Step.Done(Outcome.TAPPED_START), run.next(screen(at + 700)))
    }

    @Test
    fun `a refused click is not tried again`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        val (_, at) = showMenu(run, full, toTheMenu(run))
        run.clickRefused(at)
        val steps = (1..10).map { run.next(screen(at + it * 300L, if (it < 2) full else null)) }
        assertTrue(steps.none { it is Step.Click })
        assertEquals(Step.Done(Outcome.CLICK_REFUSED), run.next(elsewhere(at + 4_000)))
    }

    @Test
    fun `the walk gives up after 10 s`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        val t = toTheMenu(run)
        // A list that keeps changing is never read.
        var now = t
        while (now < 9_900) {
            now += 100
            assertFalse(run.next(screen(now, listOf("Bluetooth $now"))) is Step.Click)
        }
        run.next(screen(10_000, listOf("x")))
        assertEquals(Step.Done(Outcome.TIMED_OUT), run.next(elsewhere(10_200)))
    }

    @Test
    fun `Android Auto that does not go with back gives way to REau after three`() {
        val run = ServerMenuRun(ServerMenuRun.Mode.START, texts, 0)
        val (_, at) = showMenu(run, full, toTheMenu(run))
        assertEquals(Step.Back, run.next(screen(at + 600)))
        assertEquals(Step.Wait, run.next(screen(at + 900)))
        assertEquals(Step.Back, run.next(screen(at + 1_100)))
        assertEquals(Step.Back, run.next(screen(at + 1_600)))
        assertEquals(Step.OpenREau, run.next(screen(at + 2_100)))
        assertEquals(Step.Done(Outcome.TAPPED_START), run.next(screen(at + 2_200)))
    }
}
