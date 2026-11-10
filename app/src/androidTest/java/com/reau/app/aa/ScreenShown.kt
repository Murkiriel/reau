package com.reau.app.aa

import com.reau.app.video.PictureLook
import org.junit.Assert.assertEquals

/**
 * Puts the process's screen watch ([AaScreens.watch]) on a screen the way the picture would: looks of that kind, stamped from a clock of
 * their own (the watch takes the time as an argument), past the first moments of a session. The emulator's Android Auto is a stub with
 * no picture to read, so the tests that need "the map is in front" or "the menu is" say it with this.
 */
object ScreenShown {
    private fun kindOf(screen: AaScreen) = when (screen) {
        AaScreen.NAV -> PictureLook.Kind.MAP
        AaScreen.MENU -> PictureLook.Kind.MENU
        AaScreen.APPS -> PictureLook.Kind.APPS
        AaScreen.UNKNOWN -> error("nothing to put")
    }

    /**
     * [put], and then keeps the screen there: a look of its kind every 100 ms on the real clock, so the looks of the test's own fake video
     * (the process's compositor reads them every 250 ms and they say something else) never make the three in a row that would change the screen.
     * Close it (in `@After`) to stop.
     */
    fun keep(screen: AaScreen): AutoCloseable {
        put(screen)
        val kind = kindOf(screen)
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val thread = Thread {
            while (running.get()) {
                AaScreens.watch.look(kind, android.os.SystemClock.elapsedRealtime())
                Thread.sleep(100)
            }
        }.apply { isDaemon = true; start() }
        return AutoCloseable {
            running.set(false)
            thread.join(1_000)
        }
    }

    fun put(screen: AaScreen) {
        val kind = kindOf(screen)
        val watch = AaScreens.watch
        watch.reset()
        watch.look(kind, 1_000)
        for (at in listOf(4_000L, 4_500L, 5_000L)) watch.look(kind, at)
        assertEquals("the screen", screen, watch.screen.value)
    }
}
