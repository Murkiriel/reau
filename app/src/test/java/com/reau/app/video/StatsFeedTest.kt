package com.reau.app.video

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The text shows Android Auto's numbers on the phone ("app") until the dash is
 * connected, then the stream to the dash's ("painel"), and says which under the numbers.
 */
class StatsFeedTest {
    private val shown = mutableListOf<StatsText>()
    private val words = StatsWords(panel = "painel", defaultMode = "padrão", frameUnit = "KB/quadro", keyframe = "QC")
    private val feed = StatsFeed(words) { shown += it }
    private val app = Reading(12.0, 480.0)
    private val panel = Reading(4.0, 200.0)

    @Test
    fun `without the dash it shows the app's numbers, labelled app`() {
        feed.app(app)
        assertEquals(StatsText("12.0 fps  480 kbps", "app", "", "5.0 KB/quadro"), shown.last())
        feed.panel(panel) // a stream that has not started says nothing yet
        assertEquals(StatsText("12.0 fps  480 kbps", "app", "", "5.0 KB/quadro"), shown.last())
    }

    @Test
    fun `with nothing measured it shows dashes under app`() {
        feed.app(null)
        assertEquals(StatsText("-- fps  -- kbps", "app", "", "-- KB/quadro"), shown.last())
    }

    @Test
    fun `with the dash it shows the stream's numbers, labelled painel, and the app's no longer count`() {
        feed.app(app)
        feed.panelStarted()
        assertEquals("dashes until the first second of the stream", StatsText("-- fps  -- kbps", "painel", "", "-- KB/quadro"), shown.last())
        feed.panel(panel)
        assertEquals(StatsText("4.0 fps  200 kbps", "painel", "", "6.3 KB/quadro"), shown.last())
        feed.app(Reading(30.0, 900.0))
        assertEquals(StatsText("4.0 fps  200 kbps", "painel", "", "6.3 KB/quadro"), shown.last())
    }

    @Test
    fun `when the dash goes the app's numbers come back, and the stream's old ones do not`() {
        feed.app(app)
        feed.panelStarted()
        feed.panel(panel)
        feed.panelStopped()
        assertEquals(StatsText("12.0 fps  480 kbps", "app", "", "5.0 KB/quadro"), shown.last())
        feed.panelStarted()
        assertEquals(StatsText("-- fps  -- kbps", "painel", "", "-- KB/quadro"), shown.last())
    }

    @Test
    fun `the app line has the average frame size and the speed when there is one, and no stream mode`() {
        feed.app(Reading(12.0, 480.0)) // 480 kbit/s over 12 fps: 5 KB a frame
        assertEquals(StatsText("12.0 fps  480 kbps", "app", "", "5.0 KB/quadro"), shown.last())
        feed.speed(41.6)
        assertEquals(StatsText("12.0 fps  480 kbps", "app", "", "5.0 KB/quadro  42 km/h"), shown.last())
        feed.speed(null)
        assertEquals("5.0 KB/quadro", shown.last().detail)
    }

    @Test
    fun `the painel line has the bitrate mode and the keyframe after the label, then the frame size and speed`() {
        feed.panelStarted()
        feed.stream(com.reau.app.dash.BitrateMode.VBR, 1, fixed = true)
        feed.panel(Reading(4.0, 200.0)) // 200 kbit/s over 4 fps: 6.25 KB a frame
        assertEquals(StatsText("4.0 fps  200 kbps", "painel", "VBR  QC 1s", "6.3 KB/quadro"), shown.last())
        feed.speed(80.4)
        assertEquals("6.3 KB/quadro  80 km/h", shown.last().detail)
        feed.panelStopped()
        assertEquals("the stream's mode goes with the stream", "", shown.last().extra)
    }

    @Test
    fun `it publishes only a change`() {
        feed.app(app)
        feed.app(app)
        feed.app(app.copy())
        assertEquals(1, shown.size)
    }
}
