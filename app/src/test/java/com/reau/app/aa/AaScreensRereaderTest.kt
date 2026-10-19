package com.reau.app.aa

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the process tells the screen re-reader (the accessibility service), and how a re-read change is said. */
class AaScreensRereaderTest {
    private val calls = mutableListOf<String>()
    private val fake = object : AaScreens.Rereader {
        override fun inputSent() { calls += "input" }
        override fun projection(on: Boolean) { calls += "projection $on" }
    }

    @After
    fun tearDown() {
        AaScreens.rereader = null
        AaScreens.projection(false)
    }

    @Test
    fun `inputs and the projection reach the re-reader, and nothing breaks without one`() {
        AaScreens.inputSent()
        AaScreens.projection(true)
        assertTrue("remembered for a re-reader that connects later", AaScreens.projecting)
        AaScreens.rereader = fake
        AaScreens.inputSent()
        AaScreens.projection(false)
        assertEquals(listOf("input", "projection false"), calls)
        assertFalse(AaScreens.projecting)
    }

    @Test
    fun `a change first seen by a re-read is said as such, one seen by a window event is not`() {
        val said = mutableListOf<String>()
        val watch = AaScreenWatch(holdMs = 300) { said += it }
        fun d(name: String) = listOf(AaScreenRule.Display(5, "GhostActivityDisplay:ComponentInfo{x/y.$name}", true))
        watch.update(d("GhAppLauncherService"), 0)
        watch.update(d("GhAppLauncherService"), 300)
        watch.update(d("TemplateNavigationService"), 1_000, reread = true)
        watch.update(d("TemplateNavigationService"), 1_300)
        assertEquals(2, said.size)
        assertFalse(said[0], said[0].endsWith("by re-read"))
        assertTrue(said[1], said[1].startsWith("aa-screen: apps") && said[1].endsWith("by re-read"))
    }
}
