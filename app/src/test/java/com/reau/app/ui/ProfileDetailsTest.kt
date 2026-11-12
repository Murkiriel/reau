package com.reau.app.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixed video profiles show their name and one short line, and keep their numbers (frame ceiling, bitrate, mode, keyframe) behind a
 * "Detalhes" row that starts closed (the owner's decision, 12/11/2026). No value changed: only what is in sight.
 */
class ProfileDetailsTest {
    @Test
    fun `the details start closed and show no number while they are`() {
        assertFalse(ProfileDetails.START_OPEN)
        assertEquals(emptyList<String>(), ProfileDetails.rowsShown(ProfileDetails.START_OPEN))
        assertEquals(emptyList<String>(), ProfileDetails.rowsShown(false))
    }

    @Test
    fun `opened, they show the four numbers, in the order they always had`() {
        assertEquals(listOf("fps", "bitrate", "bitrate_mode", "keyframe"), ProfileDetails.rowsShown(true))
    }

    private val video = File("src/main/java/com/reau/app/ui/SettingsVideo.kt").readText()
    private val screen = File("src/main/java/com/reau/app/ui/SettingsScreen.kt").readText()

    @Test
    fun `the rows of numbers are the ones behind the details, and only there`() {
        val rows = video.substringAfter("fun StreamProfileRows").substringBefore("fun NavProfileRows")
        for (tag in ProfileDetails.ROWS) assertTrue("$tag is one of the rows of StreamProfileRows", rows.contains("\"$tag\""))
        // The stream's rows are asked for by the details row alone; the group itself never draws them.
        assertEquals(1, Regex("""StreamProfileRows\(s\)""").findAll(video).count())
        assertFalse(screen.contains("StreamProfileRows"))
        assertTrue(video.substringAfter("fun ProfileDetailsRow").contains("if (ProfileDetails.rowsShown(open).isNotEmpty())"))
        assertTrue("the state starts from the constant", video.contains("rememberSaveable { mutableStateOf(ProfileDetails.START_OPEN) }"))
    }

    @Test
    fun `the group shows each profile by name, then the navigation profiles, then the details row`() {
        val group = screen.substringAfter("private fun VideoProfilesGroup").substringBefore("tag = \"group_video_profiles\"")
        val order = listOf("\"profile_a\"", "\"profile_b\"", "NavProfileRows()", "ProfileDetailsRow(s)").map { group.indexOf(it) }
        assertTrue("all four are there: $order", order.all { it >= 0 })
        assertEquals("in that order", order.sorted(), order)
    }

    private fun text(folder: String, name: String): String {
        val strings = File("src/main/res/$folder/strings.xml").readText()
        return Regex("""<string name="$name">(.*?)</string>""").find(strings)?.groupValues?.get(1) ?: error("no $name in $folder")
    }

    @Test
    fun `the names and short lines of the profiles carry no number, in the three languages`() {
        for (folder in listOf("values-pt", "values", "values-es")) {
            for (name in listOf("profile_a_title", "profile_a_sub", "profile_b_title", "profile_b_sub", "profile_fixed", "profile_details_title", "profile_details_sub")) {
                val body = text(folder, name)
                assertTrue("$folder/$name is empty", body.isNotBlank())
                if (name != "profile_a_title" && name != "profile_b_title") assertFalse("$folder/$name has a number: $body", body.any { it.isDigit() })
            }
            assertTrue(text(folder, "profile_a_title").endsWith("A") && text(folder, "profile_b_title").endsWith("B"))
        }
        assertEquals("Detalhes", text("values-pt", "profile_details_title"))
        assertEquals("Details", text("values", "profile_details_title"))
        assertEquals("Detalles", text("values-es", "profile_details_title"))
    }

    @Test
    fun `the values themselves did not change`() {
        val d = com.reau.app.dash.DashConfig
        assertEquals(listOf(4, 400_000, 1), listOf(d.PROFILE_FULL_FPS, d.PROFILE_FULL_BPS, d.PROFILE_KEYFRAME_SEC))
        assertEquals(listOf(2, 200_000), listOf(d.PROFILE_REDUCED_FPS, d.PROFILE_REDUCED_BPS))
    }
}
