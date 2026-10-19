package com.reau.app.web

import com.reau.app.aa.PhoneTouch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The browser's touch: a fixed JSON in video pixels, or nothing. */
class WebTouchTest {
    private fun json(a: String, x: String, y: String) = "{\"a\":\"$a\",\"x\":$x,\"y\":$y}"

    @Test
    fun `each action letter is one action`() {
        assertEquals(PhoneTouch.Sent(PhoneTouch.Action.DOWN, 10, 20), WebTouch.parse(json("d", "10", "20")))
        assertEquals(PhoneTouch.Sent(PhoneTouch.Action.MOVE, 11, 21), WebTouch.parse(json("m", "11", "21")))
        assertEquals(PhoneTouch.Sent(PhoneTouch.Action.UP, 12, 22), WebTouch.parse(json("u", "12", "22")))
        assertEquals(PhoneTouch.Sent(PhoneTouch.Action.CANCEL, 13, 23), WebTouch.parse(json("c", "13", "23")))
    }

    @Test
    fun `the corners of the video are inside, one past them is not`() {
        assertEquals(PhoneTouch.Sent(PhoneTouch.Action.DOWN, 0, 0), WebTouch.parse(json("d", "0", "0")))
        assertEquals(PhoneTouch.Sent(PhoneTouch.Action.DOWN, 799, 479), WebTouch.parse(json("d", "799", "479")))
        assertNull(WebTouch.parse(json("d", "800", "10")))
        assertNull(WebTouch.parse(json("d", "10", "480")))
        assertNull(WebTouch.parse(json("d", "-1", "10")))
        assertNull(WebTouch.parse(json("d", "10", "-1")))
    }

    @Test
    fun `the size of the video is a parameter`() {
        assertEquals(PhoneTouch.Sent(PhoneTouch.Action.UP, 99, 49), WebTouch.parse(json("u", "99", "49"), width = 100, height = 50))
        assertNull(WebTouch.parse(json("u", "100", "49"), width = 100, height = 50))
    }

    @Test
    fun `spaces around the parts are fine`() {
        assertEquals(PhoneTouch.Sent(PhoneTouch.Action.MOVE, 5, 6), WebTouch.parse(" { \"a\" : \"m\" , \"x\" : 5 , \"y\" : 6 } "))
    }

    @Test
    fun `anything else is null`() {
        assertNull(WebTouch.parse(""))
        assertNull(WebTouch.parse("hello"))
        assertNull(WebTouch.parse(json("z", "1", "2")))
        assertNull(WebTouch.parse("{\"a\":\"d\",\"x\":1}"))
        assertNull(WebTouch.parse(json("d", "1.5", "2")))
        assertNull(WebTouch.parse(json("d", "\"1\"", "2")))
        assertNull(WebTouch.parse("{\"a\":\"d\",\"x\":1,\"y\":2,\"z\":3}"))
        assertNull(WebTouch.parse("{\"x\":1,\"y\":2,\"a\":\"d\"}"))
        assertNull(WebTouch.parse(json("d", "99999999999", "2")))
        assertNull(WebTouch.parse("[" + json("d", "1", "2") + "]"))
    }
}
