package com.reau.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "answer by the joystick" switch says what the joystick really does. The call goes
 * on a tap of the side (JoystickMap.callFirst acts on the press itself), so the text never speaks of holding.
 */
class AnswerCallsTextTest {
    private val strings = File("src/main/res/values-pt/strings.xml").readText()
    private val text: String = Regex("""<string name="answer_calls">(.*?)</string>""").find(strings)!!.groupValues[1]
    private val page: String = Regex("""<string name="joystick_calls">(.*?)</string>""").find(strings)!!.groupValues[1]

    @Test
    fun `the switch speaks of a tap, never of holding`() {
        assertFalse(text, text.contains("segur", ignoreCase = true))
        // the sides of Android Auto's call card, green on the left
        assertTrue(text, text.contains("toque à esquerda atende"))
        assertTrue(text, text.contains("toque à direita ou no centro recusa"))
        // On a call the dash keeps its sides to itself and sends only the middle (0x16), which ends it.
        assertTrue(text, text.contains("toque no centro desliga"))
    }

    @Test
    fun `the joystick page says the same sides`() {
        // shortened: "tocando, esquerda atende e direita recusa; em ligação, direita desliga"
        assertTrue(page, page.contains("esquerda atende e direita ou centro recusam"))
        assertTrue(page, page.contains("em ligação, o centro desliga"))
    }

    @Test
    fun `the switch still says where it works and what it asks for`() {
        assertTrue(text, text.contains("nos dois modos do painel"))
        assertTrue(text, text.contains("permissões de telefone"))
    }
}
