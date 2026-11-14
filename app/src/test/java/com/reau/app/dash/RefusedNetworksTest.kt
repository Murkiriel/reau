package com.reau.app.dash

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The networks Android refused a socket on, which the Wi-Fi manager keeps out of "already connected" ([RefusedNetworks]; it was a list
 * inside the manager until 14/11/2026). Strings stand for the `Network`s here.
 */
class RefusedNetworksTest {
    private val max = 8

    @Test
    fun `a network remembered is refused, and one that was not is not`() {
        val refused = RefusedNetworks<String>(max)
        assertFalse(refused.has("454"))
        refused.remember("454")
        assertTrue(refused.has("454"))
        assertFalse(refused.has("455"))
    }

    @Test
    fun `it keeps as many as it was told, and the next one takes the place of the oldest`() {
        val refused = RefusedNetworks<String>(max)
        (1..max).forEach { refused.remember("n$it") }
        assertTrue("all of them fit", (1..max).all { refused.has("n$it") })
        refused.remember("n9")
        assertFalse("the oldest went", refused.has("n1"))
        assertTrue("the rest stayed, the new one with them", (2..9).all { refused.has("n$it") })
    }

    @Test
    fun `a network refused twice takes one place only`() {
        val refused = RefusedNetworks<String>(max)
        refused.remember("n1")
        refused.remember("n2")
        refused.remember("n2")
        (3..max).forEach { refused.remember("n$it") }
        assertTrue("with n2 counted once all of them fit: the oldest was not pushed out by a second copy", (1..max).all { refused.has("n$it") })
    }

    @Test
    fun `the rider's switch-off forgets them all`() {
        val refused = RefusedNetworks<String>(max)
        refused.remember("n1")
        refused.remember("n2")
        refused.clear()
        assertFalse(refused.has("n1"))
        assertFalse(refused.has("n2"))
    }
}
