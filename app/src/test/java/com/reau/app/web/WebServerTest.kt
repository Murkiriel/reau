package com.reau.app.web

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The web server on real sockets, on 127.0.0.1: the page, the PIN, the WebSocket, the limits and the broadcast. */
class WebServerTest {
    private var limits = WebGuard.Limits()
    private val guard by lazy { WebGuard(limits, draw = { 4821 }) }
    private val pin get() = guard.pin
    private val events = TestEvents()
    private val servers = mutableListOf<WebServer>()
    private val clients = mutableListOf<Client>()

    @After
    fun tearDown() {
        clients.forEach { runCatching { it.socket.close() } }
        servers.forEach { it.stop() }
    }

    private class TestEvents : WebServer.Events {
        val texts = LinkedBlockingQueue<String>()
        val counts = CopyOnWriteArrayList<Int>()
        val logs = CopyOnWriteArrayList<String>()
        @Volatile var joinHook: (WebViewer) -> Unit = {}
        val pageBytes = "<html>the page</html>".toByteArray()

        override fun page() = pageBytes
        override fun onJoin(viewer: WebViewer) = joinHook(viewer)
        override fun onText(text: String) { texts.add(text) }
        override fun onViewers(count: Int) { counts.add(count) }
        override fun log(line: String) { logs.add(line) }
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun start(
        maxViewers: Int = 2,
        queueFrames: Int = 120,
        allowed: (java.net.InetAddress) -> Boolean = { true },
        ports: IntRange = freePort().let { it..it },
        headTimeoutMs: Int = 5_000,
        thePhone: (java.net.InetAddress) -> Boolean = { false },
    ): WebServer {
        val server = WebServer(WebServer.Settings(guard, ports, maxViewers, queueFrames, allowed, headTimeoutMs, thePhone), events)
        servers += server
        assertNotNull("the server found a port", server.start())
        return server
    }

    /** A raw client: bytes out, bytes in, with a 5 s guard so a bug fails the test instead of hanging it. */
    private inner class Client(port: Int) {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 5000 }
        val input = BufferedInputStream(socket.getInputStream())

        init { clients += this }

        fun send(text: String) = socket.getOutputStream().apply { write(text.toByteArray(Charsets.ISO_8859_1)); flush() }

        fun readHead(): String {
            val text = StringBuilder()
            while (!text.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) break
                text.append(b.toChar())
            }
            return text.toString()
        }

        fun readRest(): ByteArray = ByteArrayOutputStream().also { out ->
            while (true) {
                val b = try { input.read() } catch (e: java.io.IOException) { -1 }
                if (b < 0) break
                out.write(b)
            }
        }.toByteArray()

        /** One frame of the server (never masked). */
        fun frame(): Pair<Int, ByteArray>? = try { readFrame() } catch (e: java.io.IOException) { null }

        private fun readFrame(): Pair<Int, ByteArray>? {
            val b0 = input.read()
            if (b0 < 0) return null
            val b1 = input.read()
            var length = (b1 and 0x7F).toLong()
            if (length == 126L) length = (input.read() shl 8 or input.read()).toLong()
            if (length == 127L) length = (0 until 8).fold(0L) { acc, _ -> (acc shl 8) or input.read().toLong() }
            val payload = ByteArray(length.toInt())
            var done = 0
            while (done < payload.size) {
                val n = input.read(payload, done, payload.size - done)
                if (n < 0) return null
                done += n
            }
            return (b0 and 0x0F) to payload
        }

        /** A masked frame as a browser sends it. */
        fun sendFrame(opcode: Int, payload: ByteArray, masked: Boolean = true) {
            val mask = byteArrayOf(1, 2, 3, 4)
            val head = byteArrayOf((0x80 or opcode).toByte(), ((if (masked) 0x80 else 0) or payload.size).toByte())
            val body = if (masked) mask + ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() } else payload
            socket.getOutputStream().apply { write(head + body); flush() }
        }
    }

    private class Response(val status: Int, val head: String, val body: ByteArray)

    private fun get(server: WebServer, target: String, method: String = "GET"): Response {
        val c = Client(server.port)
        c.send("$method $target HTTP/1.1\r\nHost: x\r\n\r\n")
        val head = c.readHead()
        return Response(head.substringAfter(' ').substringBefore(' ').toIntOrNull() ?: 0, head, c.readRest())
    }

    private fun open(server: WebServer, key: String = "dGhlIHNhbXBsZSBub25jZQ==", query: String = "?k=$pin"): Pair<Client, String> {
        val c = Client(server.port)
        c.send(
            "GET /ws$query HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n",
        )
        return c to c.readHead()
    }

    private fun eventually(what: String, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            assertTrue("not in 5 s: $what", System.nanoTime() < until)
            Thread.sleep(20)
        }
    }

    @Test
    fun `the page is public, never cached, and says nothing of the pin`() {
        val server = start()
        for (target in listOf("/", "/?k=", "/?k=$pin", "/?k=wrong")) {
            val r = get(server, target)
            assertEquals(target, 200, r.status)
            assertArrayEquals(target, events.pageBytes, r.body)
            assertTrue(r.head, r.head.contains("Content-Type: text/html", ignoreCase = true))
            assertTrue(r.head, r.head.contains("Cache-Control: no-store", ignoreCase = true))
            assertTrue(r.head, r.head.contains("Referrer-Policy: no-referrer", ignoreCase = true))
            assertTrue(r.head, r.head.contains("Content-Length: ${events.pageBytes.size}"))
        }
    }

    /**
     * The server writes no text for the person: its errors are a status with no body and the page is one file for both languages
     * (the page picks by the browser's own language list), so the Accept-Language header changes nothing in an answer.
     */
    @Test
    fun `the server's answers do not depend on the browser's language`() {
        limits = WebGuard.Limits(maxPerAddress = 99, rotateAfter = 999)
        val server = start()
        fun ask(target: String, language: String?): Response {
            val c = Client(server.port)
            c.send("GET $target HTTP/1.1\r\nHost: x\r\n" + (language?.let { "Accept-Language: $it\r\n" } ?: "") + "\r\n")
            val head = c.readHead()
            return Response(head.substringAfter(' ').substringBefore(' ').toIntOrNull() ?: 0, head, c.readRest())
        }
        for (target in listOf("/", "/auth?k=0000")) {
            val none = ask(target, null)
            for (language in listOf("pt-BR,pt;q=0.9,en;q=0.8", "en-US,en;q=0.9", "es")) {
                val r = ask(target, language)
                assertEquals("$target $language", none.status, r.status)
                assertEquals("$target $language", none.head, r.head)
                assertArrayEquals("$target $language", none.body, r.body)
                if (target.startsWith("/auth")) assertEquals("a wrong pin is a status and nothing else, in any language", 0, r.body.size)
            }
        }
    }

    @Test
    fun `auth says yes to the pin with no body, no to anything else, and a wrong pin is 403 with nothing in it`() {
        limits = WebGuard.Limits(maxPerAddress = 99, rotateAfter = 999)
        val server = start()
        val yes = get(server, "/auth?k=$pin")
        assertEquals(204, yes.status)
        assertEquals(0, yes.body.size)
        for (target in listOf("/auth", "/auth?k=", "/auth?k=0000", "/auth?k=482", "/auth?k=48210", "/auth?x=$pin")) {
            val r = get(server, target)
            assertEquals(target, 403, r.status)
            assertEquals(target, 0, r.body.size)
            assertTrue(target, r.head.contains("Content-Length: 0"))
        }
    }

    @Test
    fun `five wrong pins from one address are 429, and then even the right one is`() {
        val server = start()
        repeat(4) { assertEquals(403, get(server, "/auth?k=0000").status) }
        assertEquals(429, get(server, "/auth?k=0000").status)
        assertEquals(429, get(server, "/auth?k=$pin").status)
        assertTrue("the WebSocket is held too", open(server).second.startsWith("HTTP/1.1 429"))
    }

    @Test
    fun `wrong pins and a held address are said in the log, never with the pin or an address`() {
        val server = start()
        repeat(5) { get(server, "/auth?k=0000") }
        val log = events.logs.joinToString("\n")
        assertEquals(log, 4, Regex("web: wrong pin").findAll(log).count())
        assertTrue(log, log.contains("web: address held"))
        assertFalse(log, log.contains("4821") || log.contains("0000") || log.contains("127.0.0.1"))
    }

    @Test
    fun `too many wrong pins change the pin, and the old one is no key`() {
        limits = WebGuard.Limits(maxPerAddress = 9, rotateAfter = 3, lockMs = 700)
        val draws = ArrayDeque(listOf(4821, 77))
        val server = WebServer(WebServer.Settings(WebGuard(limits, draw = { draws.removeFirst() }), freePort().let { it..it }), events).also { servers += it }
        assertNotNull(server.start())
        repeat(3) { get(server, "/auth?k=0000") }
        assertEquals("held for the lock time, whatever the pin", 429, get(server, "/auth?k=0077").status)
        Thread.sleep(800)
        assertEquals(403, get(server, "/auth?k=4821").status)
        assertEquals(204, get(server, "/auth?k=0077").status)
    }

    @Test
    fun `an unknown path is 404 and a method but GET is 405, both with no body`() {
        val server = start()
        val missing = get(server, "/nothing")
        assertEquals(404, missing.status)
        assertEquals(0, missing.body.size)
        val post = get(server, "/", method = "POST")
        assertEquals(405, post.status)
        assertEquals(0, post.body.size)
    }

    @Test
    fun `a client the network rule refuses gets no byte at all`() {
        val server = start(allowed = { false })
        val c = Client(server.port)
        c.send("GET /?k=$pin HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals(0, c.readRest().size)
    }

    @Test
    fun `a head that is too big is 431`() {
        val server = start()
        val c = Client(server.port)
        c.send("GET / HTTP/1.1\r\nX: " + "a".repeat(9000) + "\r\n\r\n")
        assertTrue(c.readHead().startsWith("HTTP/1.1 431"))
    }

    @Test
    fun `the handshake answers 101 with the accept key`() {
        val server = start()
        val (_, head) = open(server)
        assertTrue(head, head.startsWith("HTTP/1.1 101"))
        assertTrue(head, head.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo="))
        assertTrue(head, head.contains("Upgrade: websocket", ignoreCase = true))
    }

    @Test
    fun `a WebSocket with no pin or a wrong one is 403, with the headers missing is 400`() {
        val server = start()
        assertTrue(open(server, query = "").second.startsWith("HTTP/1.1 403"))
        assertTrue(open(server, query = "?k=nope").second.startsWith("HTTP/1.1 403"))
        val c = Client(server.port)
        c.send("GET /ws?k=$pin HTTP/1.1\r\nHost: x\r\n\r\n")
        assertTrue(c.readHead().startsWith("HTTP/1.1 400"))
        val noKey = Client(server.port)
        noKey.send("GET /ws?k=$pin HTTP/1.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n\r\n")
        assertTrue(noKey.readHead().startsWith("HTTP/1.1 400"))
    }

    @Test
    fun `a published frame reaches every viewer whole, and so does a text`() {
        val server = start()
        val (a, _) = open(server)
        val (b, _) = open(server)
        eventually("two viewers") { server.viewerCount == 2 }
        val payload = ByteArray(70_000) { (it % 251).toByte() }
        server.publish(payload)
        server.sendText("hello")
        for (viewer in listOf(a, b)) {
            val binary = viewer.frame()!!
            assertEquals(WebSocketCodec.BINARY, binary.first)
            assertArrayEquals(payload, binary.second)
            val text = viewer.frame()!!
            assertEquals(WebSocketCodec.TEXT, text.first)
            assertEquals("hello", String(text.second))
        }
    }

    @Test
    fun `the third viewer gets 503 and a place frees when one leaves`() {
        val server = start(maxViewers = 2)
        val (a, _) = open(server)
        open(server)
        eventually("two viewers") { server.viewerCount == 2 }
        val third = open(server).second
        assertTrue(third, third.startsWith("HTTP/1.1 503"))
        a.socket.close()
        eventually("one viewer left") { server.viewerCount == 1 }
        assertTrue(open(server).second.startsWith("HTTP/1.1 101"))
    }

    @Test
    fun `the viewer count is told on each change and the log never has the pin`() {
        val server = start()
        val (a, _) = open(server)
        eventually("one") { events.counts.lastOrNull() == 1 }
        a.socket.close()
        eventually("zero") { events.counts.lastOrNull() == 0 }
        assertEquals(listOf(1, 0), events.counts.toList())
        val log = events.logs.joinToString("\n")
        assertTrue(log, log.contains("server on port ${server.port}"))
        assertTrue(log, log.contains("viewer joined (1)"))
        assertTrue(log, log.contains("viewer left (0)"))
        assertFalse("the pin is never logged", log.contains(pin))
        assertFalse("nor an address", log.contains("127.0.0.1"))
    }

    @Test
    fun `what the viewer sends as text reaches the caller, a ping is answered, binary is ignored`() {
        val server = start()
        val (a, _) = open(server)
        eventually("one viewer") { server.viewerCount == 1 }
        a.sendFrame(WebSocketCodec.TEXT, "{\"a\":\"d\"}".toByteArray())
        assertEquals("{\"a\":\"d\"}", events.texts.poll(5, TimeUnit.SECONDS))
        a.sendFrame(WebSocketCodec.BINARY, byteArrayOf(1, 2, 3))
        a.sendFrame(WebSocketCodec.PING, byteArrayOf(7))
        val pong = a.frame()!!
        assertEquals(WebSocketCodec.PONG, pong.first)
        assertArrayEquals(byteArrayOf(7), pong.second)
        assertNull(events.texts.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a viewer that breaks the protocol is dropped`() {
        val server = start()
        val (a, _) = open(server)
        eventually("one viewer") { server.viewerCount == 1 }
        a.sendFrame(WebSocketCodec.TEXT, "x".toByteArray(), masked = false)
        eventually("dropped") { server.viewerCount == 0 }
    }

    @Test
    fun `a close from the viewer drops it`() {
        val server = start()
        val (a, _) = open(server)
        eventually("one viewer") { server.viewerCount == 1 }
        a.sendFrame(WebSocketCodec.CLOSE, byteArrayOf(0x03, 0xE8.toByte()))
        eventually("dropped") { server.viewerCount == 0 }
    }

    @Test
    fun `what the caller queues on joining comes before any published frame`() {
        events.joinHook = { it.sendBinary(byteArrayOf(9, 9)); it.sendText("welcome") }
        val server = start()
        val (a, _) = open(server)
        eventually("one viewer") { server.viewerCount == 1 }
        server.publish(byteArrayOf(1))
        assertArrayEquals(byteArrayOf(9, 9), a.frame()!!.second)
        assertEquals("welcome", String(a.frame()!!.second))
        assertArrayEquals(byteArrayOf(1), a.frame()!!.second)
    }

    @Test
    fun `a viewer that does not read is dropped and the others are not held back`() {
        val server = start(maxViewers = 2, queueFrames = 4)
        val (slow, _) = open(server)
        val (fast, _) = open(server)
        eventually("two viewers") { server.viewerCount == 2 }
        val received = java.util.concurrent.atomic.AtomicInteger()
        val ended = java.util.concurrent.atomic.AtomicBoolean()
        val reader = Thread {
            while (true) {
                val (opcode, payload) = fast.frame() ?: break
                if (opcode == WebSocketCodec.TEXT && String(payload) == "end") ended.set(true) else received.incrementAndGet()
            }
        }.apply { isDaemon = true; start() }
        val big = ByteArray(200_000)
        var published = 0
        while (server.viewerCount == 2 && published < 600) {
            server.publish(big)
            published++
            Thread.sleep(10)
        }
        assertEquals("only the slow one went", 1, server.viewerCount)
        server.sendText("end")
        eventually("the fast one got every frame up to the end") { ended.get() }
        assertTrue("and most of the frames", received.get() >= published - 1)
        slow.socket.close()
        reader.interrupt()
    }

    @Test
    fun `sound that a slow viewer cannot take is dropped, the viewer is not, while video that it cannot take still drops it`() {
        val server = start(maxViewers = 1, queueFrames = 4)
        val (slow, _) = open(server)
        eventually("one viewer") { server.viewerCount == 1 }
        val big = ByteArray(200_000)
        repeat(300) {
            server.publish(big, sound = true)
            Thread.sleep(2)
        }
        assertEquals("the sound alone does not drop a slow viewer", 1, server.viewerCount)
        var published = 0
        while (server.viewerCount == 1 && published < 600) {
            server.publish(big)
            published++
            Thread.sleep(10)
        }
        assertEquals("a picture it cannot take does", 0, server.viewerCount)
        slow.socket.close()
    }

    @Test
    fun `sound reaches a viewer that keeps up, in order with the pictures`() {
        val server = start()
        val (a, _) = open(server)
        eventually("one viewer") { server.viewerCount == 1 }
        server.publish(byteArrayOf(0, 1))
        server.publish(byteArrayOf(1, 2), sound = true)
        server.publish(byteArrayOf(0, 3))
        assertArrayEquals(byteArrayOf(0, 1), a.frame()!!.second)
        assertArrayEquals(byteArrayOf(1, 2), a.frame()!!.second)
        assertArrayEquals(byteArrayOf(0, 3), a.frame()!!.second)
    }

    @Test
    fun `a head that drips in slowly is cut off at the head's time, not held for hours`() {
        val server = start(headTimeoutMs = 600)
        val c = Client(server.port)
        val out = c.socket.getOutputStream()
        val started = System.nanoTime()
        val text = "GET / HTTP/1.1\r\nHost: x\r\nX-Slow: ".toByteArray()
        var cut = false
        try {
            for (b in text + ByteArray(60) { 'a'.code.toByte() }) {
                out.write(b.toInt())
                out.flush()
                Thread.sleep(150)
            }
        } catch (e: java.io.IOException) {
            cut = true
        }
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        assertTrue("the server closed the connection (after ${"%.1f".format(seconds)} s)", cut || c.input.read() < 0)
        assertTrue("and soon, not after the whole head: $seconds s", seconds < 6.0)
    }

    @Test
    fun `one address cannot take every place for connections`() {
        val server = start()
        val idle = (1..10).map { Client(server.port) }
        Thread.sleep(200)
        val over = Client(server.port)
        assertEquals("the eleventh connection from the same address is closed without a byte", -1, over.input.read())
        idle.forEach { it.socket.close() }
        eventually("the places come back") { get(server, "/").status == 200 }
    }

    @Test
    fun `a page of another site cannot ask for the pin or open the stream, whatever the pin it tries`() {
        val server = start()
        fun auth(origin: String?, key: String = pin): Int {
            val c = Client(server.port)
            c.send("GET /auth?k=$key HTTP/1.1\r\nHost: phone:8780\r\n" + (origin?.let { "Origin: $it\r\n" } ?: "") + "\r\n")
            return c.readHead().substringAfter(' ').substringBefore(' ').toInt().also { c.socket.close() }
        }
        assertEquals("the page's own address", 204, auth("http://phone:8780"))
        assertEquals("no Origin: not a browser's page", 204, auth(null))
        assertEquals("another site", 403, auth("http://evil.example"))
        assertEquals("the same host on another port is another site", 403, auth("http://phone:9999"))
        repeat(10) { assertEquals(403, auth("http://evil.example", "0000")) }
        assertEquals("the guesses of another site are not counted against the right page", 204, auth("http://phone:8780"))
        val c = Client(server.port)
        c.send(
            "GET /ws?k=$pin HTTP/1.1\r\nHost: phone:8780\r\nOrigin: http://evil.example\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n",
        )
        assertTrue(c.readHead().startsWith("HTTP/1.1 403"))
    }

    @Test
    fun `the caller is told of the viewers in the order they came and went`() {
        val server = start()
        val (a, _) = open(server)
        val (b, _) = open(server)
        eventually("two viewers") { server.viewerCount == 2 }
        a.socket.close()
        eventually("one left") { events.counts.toList() == listOf(1, 2, 1) }
        b.socket.close()
        eventually("none left") { events.counts.toList() == listOf(1, 2, 1, 0) }
    }

    @Test
    fun `the sound goes to every browser, as the picture does, with no choosing and no notice`() {
        val server = start(maxViewers = 3)
        val (a, _) = open(server)
        val (b, _) = open(server)
        val (c, _) = open(server)
        eventually("three") { server.viewerCount == 3 }
        server.publish(byteArrayOf(1, 7, 7), sound = true)
        server.publish(byteArrayOf(0, 9))
        for (viewer in listOf(a, b, c)) {
            assertArrayEquals(byteArrayOf(1, 7, 7), viewer.frame()!!.second)
            assertArrayEquals(byteArrayOf(0, 9), viewer.frame()!!.second)
        }
    }

    @Test
    fun `a browser on the phone itself gets the picture and no sound, and says why, while the others get both`() {
        val seen = java.util.concurrent.atomic.AtomicInteger()
        val server = start(maxViewers = 3, thePhone = { seen.getAndIncrement() == 0 }) // the first to come in is the phone's own
        val (onThePhone, _) = open(server)
        val (other, _) = open(server)
        eventually("two") { server.viewerCount == 2 }
        server.publish(byteArrayOf(1, 7, 7), sound = true)
        server.publish(byteArrayOf(0, 9))
        assertEquals("told why it has no sound", WebProtocol.SOUND_OFF, String(onThePhone.frame()!!.second))
        assertArrayEquals("the picture, and not the sound that Android Auto would take back", byteArrayOf(0, 9), onThePhone.frame()!!.second)
        assertArrayEquals(byteArrayOf(1, 7, 7), other.frame()!!.second)
        assertArrayEquals(byteArrayOf(0, 9), other.frame()!!.second)
    }

    @Test
    fun `kick drops every viewer, who see the stream end`() {
        val server = start()
        val (a, _) = open(server)
        val (b, _) = open(server)
        eventually("two viewers") { server.viewerCount == 2 }
        server.kick()
        eventually("none") { server.viewerCount == 0 }
        assertNull(a.frame())
        assertNull(b.frame())
        assertTrue("and the server still answers", get(server, "/").status == 200)
    }

    @Test
    fun `stop closes the port and the viewers`() {
        val server = start()
        val port = server.port
        val (a, _) = open(server)
        eventually("one viewer") { server.viewerCount == 1 }
        server.stop()
        assertNull(a.frame())
        assertEquals(0, server.viewerCount)
        val refused = runCatching { Socket("127.0.0.1", port).use { } }.isFailure
        assertTrue("nobody listens any more", refused)
    }

    @Test
    fun `a busy port passes to the next one and no free port is null`() {
        val holder = ServerSocket(0)
        holder.use {
            val first = it.localPort
            val server = WebServer(WebServer.Settings(guard, first..first + 3), events).also { s -> servers += s }
            val port = server.start()
            assertNotNull(port)
            assertTrue("not the busy one", port != first)
            assertEquals(port, server.port)
        }
        val busy = ServerSocket(0)
        busy.use {
            val none = WebServer(WebServer.Settings(guard, it.localPort..it.localPort), events).also { s -> servers += s }
            assertNull(none.start())
        }
    }

    @Test
    fun `a head that stalls is cut after the timeout, not held for ever`() {
        val server = WebServer(WebServer.Settings(guard, freePort().let { it..it }, headTimeoutMs = 300), events).also { servers += it }
        server.start()
        val c = Client(server.port)
        c.send("GET / HTTP/1.1\r\nHost: x\r\n")
        assertEquals(0, c.readRest().size)
    }
}
