package com.reau.app.service

import com.reau.app.R
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Which title and text the ongoing notification shows for each headline of the projection. */
class ProjectionNotificationTest {

    @Test
    fun `each headline of a running projection has its own title and text`() {
        val expected = mapOf(
            Headline.PREPARING to (R.string.notif_title_preparing to R.string.notif_text_preparing),
            Headline.CONNECTING to (R.string.notif_title_connecting to R.string.notif_text_connecting),
            Headline.ANDROID_AUTO_SERVER_OFF to (R.string.notif_title_server_off to R.string.notif_text_server_off),
            Headline.DASH_LOST to (R.string.notif_title_dash_lost to R.string.notif_text_dash_lost),
            Headline.DASH_SEARCHING to (R.string.notif_title_dash_searching to R.string.notif_text_dash_searching),
            Headline.STREAMING to (R.string.notif_title_streaming to R.string.notif_text_streaming),
        )
        for ((headline, content) in expected) assertEquals(headline.name, content, ProjectionNotification.contentFor(headline))
        assertEquals("six different titles", 6, expected.keys.map { ProjectionNotification.contentFor(it).first }.toSet().size)
    }

    @Test
    fun `a mute server's notification says the menu by hand only where the rider has to restart it`() {
        val title = R.string.notif_title_unresponsive
        assertEquals(title to R.string.notif_text_unresponsive, ProjectionNotification.contentFor(Headline.ANDROID_AUTO_UNRESPONSIVE))
        assertEquals(title to R.string.notif_text_unresponsive, ProjectionNotification.contentFor(Headline.ANDROID_AUTO_UNRESPONSIVE, muteByRider = false))
        assertEquals(title to R.string.notif_text_unresponsive_by_hand, ProjectionNotification.contentFor(Headline.ANDROID_AUTO_UNRESPONSIVE, muteByRider = true))
        for (headline in Headline.entries.filter { it != Headline.ANDROID_AUTO_UNRESPONSIVE }) {
            assertEquals(headline.name, ProjectionNotification.contentFor(headline), ProjectionNotification.contentFor(headline, muteByRider = true))
        }
    }

    @Test
    fun `the tap Connect text is only the join-ask headline's, the plain search keeps its own`() {
        val search = ProjectionNotification.contentFor(Headline.DASH_SEARCHING)
        val ask = ProjectionNotification.contentFor(Headline.DASH_JOIN_ASK)
        assertEquals("same title: both are looking for the dash", search.first, ask.first)
        assertEquals(R.string.notif_text_dash_searching, search.second)
        assertEquals(R.string.notif_text_dash_join_ask, ask.second)
        assertEquals(1, Headline.entries.count { ProjectionNotification.contentFor(it).second == R.string.notif_text_dash_join_ask })
    }

    @Test
    fun `the card on the dash has a notification of its own`() {
        val card = ProjectionNotification.contentFor(Headline.CARD)
        assertEquals(R.string.notif_title_card to R.string.notif_text_card, card)
        assertEquals(
            "not the title of anything else",
            listOf(card.first),
            (Headline.entries).map { ProjectionNotification.contentFor(it).first }.filter { it == card.first },
        )
    }

    @Test
    fun `a dash whose full name is not known has a notification of its own`() {
        val name = ProjectionNotification.contentFor(Headline.DASH_NAME_NEEDED)
        assertEquals(R.string.notif_title_dash_name_needed to R.string.notif_text_dash_name_needed, name)
        assertEquals(1, Headline.entries.count { ProjectionNotification.contentFor(it).first == name.first })
    }

    @Test
    fun `a dash held by another app has a notification of its own`() {
        val taken = ProjectionNotification.contentFor(Headline.DASH_TAKEN)
        assertEquals(R.string.notif_title_dash_taken to R.string.notif_text_dash_taken, taken)
        assertEquals(1, Headline.entries.count { ProjectionNotification.contentFor(it).first == taken.first })
    }

    @Test
    fun `stopped, with or without a problem, is the notification of a service on its way out`() {
        assertEquals(R.string.status_stopped to R.string.notif_text_stopping, ProjectionNotification.contentFor(Headline.STOPPED))
        assertEquals(R.string.status_stopped to R.string.notif_text_stopping, ProjectionNotification.contentFor(Headline.PROBLEM))
    }

    @Test
    fun `turning off has a notification of its own, shown while the stop is carried out`() {
        val stopping = ProjectionNotification.contentFor(Headline.STOPPING)
        assertEquals(R.string.notif_title_stopping to R.string.notif_text_stopping_now, stopping)
        assertEquals(1, Headline.entries.count { ProjectionNotification.contentFor(it).first == stopping.first })
    }

    /** The colour of each state, with the Aurum palette. */
    @Test
    fun `each state takes its colour`() {
        val gold = 0xFFE1AD01.toInt()
        val warning = 0xFFC26A22.toInt()
        val error = 0xFFD64A4A.toInt()
        for (h in listOf(Headline.PREPARING, Headline.CONNECTING, Headline.STREAMING, Headline.CARD, Headline.STOPPED, Headline.PROBLEM)) {
            assertEquals(h.name, gold, ProjectionNotification.colorFor(h))
        }
        assertEquals(warning, ProjectionNotification.colorFor(Headline.DASH_LOST))
        assertEquals("closing the previous session is a wait, not an error", warning, ProjectionNotification.colorFor(Headline.ANDROID_AUTO_CLOSING_PREVIOUS))
        for (h in listOf(Headline.ANDROID_AUTO_SERVER_OFF, Headline.ANDROID_AUTO_UNRESPONSIVE, Headline.DASH_TAKEN, Headline.DASH_NAME_NEEDED)) {
            assertEquals(h.name, error, ProjectionNotification.colorFor(h))
        }
    }

    @Test
    fun `the chronometer starts at the projection's start on the wall clock`() {
        // Started 252 s ago on the boot clock: on the wall clock, 252 s before now.
        assertEquals(1_000_000L - 252_000L, ProjectionNotification.startedWall(startedElapsed = 48_000, nowElapsed = 300_000, nowWall = 1_000_000))
    }

    @Test
    fun `streaming, the notification says what the screen says, Android Auto on the dash and not an image`() {
        // A ride: the notification said "Imagem no painel" while Android Auto went to the dash (the image is the wallpaper's).
        val strings = File("src/main/res/values-pt/strings.xml").readText()
        fun text(name: String) = Regex("""<string name="$name">(.*?)</string>""").find(strings)!!.groupValues[1]
        assertEquals(text("sub_streaming"), text("notif_text_streaming"))
    }
}
