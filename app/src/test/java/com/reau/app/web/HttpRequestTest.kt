package com.reau.app.web

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The server's request reader: one GET, bounded, with the query and the headers in lower case. */
class HttpRequestTest {
    private fun parse(text: String) = HttpRequestParser.parse(ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1)))

    private fun ok(text: String): HttpRequest = (parse(text) as HttpRequestParser.Result.Ok).request

    private fun bad(text: String): HttpRequestParser.Result.Bad = parse(text) as HttpRequestParser.Result.Bad

    @Test
    fun `a plain GET gives its path, query and headers`() {
        val r = ok("GET /ws?k=abc123&x=a%20b HTTP/1.1\r\nHost: 192.168.0.5:8780\r\nUpgrade: websocket\r\nSec-WebSocket-Key: dGhl\r\n\r\n")
        assertEquals("/ws", r.path)
        assertEquals("abc123", r.query["k"])
        assertEquals("a b", r.query["x"])
        assertEquals("websocket", r.headers["upgrade"])
        assertEquals("dGhl", r.headers["sec-websocket-key"])
        assertEquals("192.168.0.5:8780", r.headers["host"])
    }

    @Test
    fun `a path with no query has an empty one`() {
        val r = ok("GET / HTTP/1.1\r\nHost: a\r\n\r\n")
        assertEquals("/", r.path)
        assertTrue(r.query.isEmpty())
    }

    @Test
    fun `a header may be asked for in any case`() {
        assertEquals("keep-alive", ok("GET / HTTP/1.1\r\nCONNECTION: keep-alive\r\n\r\n").headers["connection"])
    }

    @Test
    fun `anything but GET is refused`() {
        assertEquals(405, bad("POST / HTTP/1.1\r\nHost: a\r\n\r\n").status)
        assertEquals(405, bad("DELETE /x HTTP/1.1\r\n\r\n").status)
    }

    @Test
    fun `a head that never ends is refused for its size`() {
        val big = "GET / HTTP/1.1\r\n" + "X-Fill: " + "a".repeat(9000) + "\r\n\r\n"
        assertEquals(431, bad(big).status)
    }

    @Test
    fun `too many headers are refused`() {
        val many = "GET / HTTP/1.1\r\n" + (1..65).joinToString("") { "H$it: v\r\n" } + "\r\n"
        assertEquals(431, bad(many).status)
        val allowed = "GET / HTTP/1.1\r\n" + (1..64).joinToString("") { "H$it: v\r\n" } + "\r\n"
        assertEquals(64, ok(allowed).headers.size)
    }

    @Test
    fun `a stream that ends before the head is a bad request`() {
        assertEquals(400, bad("GET / HTTP/1.1\r\nHost: a\r\n").status)
        assertEquals(400, bad("").status)
    }

    @Test
    fun `a request line that is not three parts is a bad request`() {
        assertEquals(400, bad("GET /\r\n\r\n").status)
        assertEquals(400, bad("GET / HTTP/1.1 extra\r\n\r\n").status)
    }

    @Test
    fun `a header with no colon is a bad request`() {
        assertEquals(400, bad("GET / HTTP/1.1\r\nnocolon\r\n\r\n").status)
    }

    @Test
    fun `a path that does not start with a slash is a bad request`() {
        assertEquals(400, bad("GET http://x/ HTTP/1.1\r\n\r\n").status)
    }

    @Test
    fun `a missing query value is empty and a repeated key keeps the first`() {
        val r = ok("GET /?k&k=1&k=2 HTTP/1.1\r\n\r\n")
        assertEquals("", r.query["k"])
        assertNull(r.query["zzz"])
    }
}
