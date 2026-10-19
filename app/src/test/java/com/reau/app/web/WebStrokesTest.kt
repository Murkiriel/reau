package com.reau.app.web

import com.reau.app.aa.PhoneTouch
import com.reau.app.aa.PhoneTouch.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Os toques dos navegadores a caminho do Android Auto: um traço de cada vez, so o que o JSON do navegador diz, e um
 * traço deixado no meio por um navegador que sumiu e cancelado.
 */
class WebStrokesTest {
    private val sent = mutableListOf<PhoneTouch.Sent>()
    private val strokes = WebStrokes { sent += it }

    private fun json(a: String, x: Int, y: Int) = "{\"a\":\"$a\",\"x\":$x,\"y\":$y}"

    @Test
    fun `um toque inteiro vai do dedo descendo ate o dedo saindo, nos mesmos pontos`() {
        strokes.take(json("d", 10, 20))
        strokes.take(json("m", 30, 40))
        strokes.take(json("u", 50, 60))
        assertEquals(
            listOf(PhoneTouch.Sent(Action.DOWN, 10, 20), PhoneTouch.Sent(Action.MOVE, 30, 40), PhoneTouch.Sent(Action.UP, 50, 60)),
            sent,
        )
    }

    @Test
    fun `texto que nao e toque nao manda nada`() {
        for (junk in listOf("", "oi", "{}", json("z", 1, 2), json("d", 800, 10), json("d", 10, 480), json("d", -1, 5))) strokes.take(junk)
        assertTrue(sent.toString(), sent.isEmpty())
    }

    @Test
    fun `um movimento ou um fim sem o dedo ter descido nao chega ao Android Auto`() {
        strokes.take(json("m", 5, 5))
        strokes.take(json("u", 5, 5))
        strokes.take(json("c", 5, 5))
        assertTrue(sent.toString(), sent.isEmpty())
    }

    @Test
    fun `um cancelamento do navegador fecha o traco no ponto dele`() {
        strokes.take(json("d", 10, 20))
        strokes.take(json("c", 11, 21))
        assertEquals(listOf(PhoneTouch.Sent(Action.DOWN, 10, 20), PhoneTouch.Sent(Action.CANCEL, 11, 21)), sent)
        sent.clear()
        strokes.take(json("m", 12, 22))
        assertTrue("depois do cancelamento o traco acabou", sent.isEmpty())
    }

    @Test
    fun `um navegador que some no meio do traco manda o cancelamento no ultimo ponto, uma vez so`() {
        strokes.take(json("d", 10, 20))
        strokes.take(json("m", 300, 400))
        sent.clear()
        strokes.cancel()
        assertEquals(listOf(PhoneTouch.Sent(Action.CANCEL, 300, 400)), sent)
        strokes.cancel()
        assertEquals("o segundo cancelamento nao manda nada", 1, sent.size)
    }

    @Test
    fun `cancelar sem dedo na tela nao manda nada`() {
        strokes.cancel()
        assertTrue(sent.isEmpty())
        strokes.take(json("d", 1, 1))
        strokes.take(json("u", 1, 1))
        sent.clear()
        strokes.cancel()
        assertTrue("o traco ja tinha terminado", sent.isEmpty())
    }

    @Test
    fun `um novo toque comeca limpo depois de um cancelamento`() {
        strokes.take(json("d", 1, 1))
        strokes.cancel()
        sent.clear()
        strokes.take(json("d", 7, 8))
        strokes.take(json("u", 7, 8))
        assertEquals(listOf(PhoneTouch.Sent(Action.DOWN, 7, 8), PhoneTouch.Sent(Action.UP, 7, 8)), sent)
    }

    @Test
    fun `dois navegadores mandando juntos nao quebram o traco`() {
        val received = java.util.Collections.synchronizedList(mutableListOf<PhoneTouch.Sent>())
        val shared = WebStrokes { received += it }
        shared.take(json("d", 0, 0))
        val threads = List(4) { t ->
            Thread { repeat(500) { shared.take(json("m", t, it % 400)) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }
        // O dedo continuou descido: 1 DOWN e 2000 MOVE, todos dentro do video.
        assertEquals(2001, received.size)
        assertEquals(Action.DOWN, received.first().action)
        assertTrue(received.drop(1).all { it.action == Action.MOVE })
    }
}
