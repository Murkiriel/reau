package com.reau.app.web

import java.io.BufferedInputStream
import java.io.IOException
import java.net.Socket

/** A browser's side of a connection to the web server, for tests: raw HTTP, the WebSocket handshake, server frames, masked frames out. */
internal class WsTestClient(port: Int) {
    val socket = Socket("127.0.0.1", port).apply { soTimeout = 5000 }
    private val input = BufferedInputStream(socket.getInputStream())

    fun send(text: String) = socket.getOutputStream().run { write(text.toByteArray(Charsets.ISO_8859_1)); flush() }

    fun readHead(): String {
        val text = StringBuilder()
        while (!text.endsWith("\r\n\r\n")) {
            val b = try { input.read() } catch (_: IOException) { -1 }
            if (b < 0) break
            text.append(b.toChar())
        }
        return text.toString()
    }

    fun readRest(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val b = try { input.read() } catch (_: IOException) { -1 }
            if (b < 0) break
            out.write(b)
        }
        return out.toByteArray()
    }

    /** One frame of the server (opcode, payload), or null when the stream ended. */
    fun frame(): Pair<Int, ByteArray>? = try { readFrame() } catch (_: IOException) { null }

    private fun readFrame(): Pair<Int, ByteArray>? {
        val b0 = input.read()
        if (b0 < 0) return null
        var length = (input.read() and 0x7F).toLong()
        if (length == 126L) length = ((input.read() shl 8) or input.read()).toLong()
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

    /** The next frame as text; fails the test if it is not a text frame. */
    fun text(): String? = frame()?.let { (opcode, payload) ->
        check(opcode == WebSocketCodec.TEXT) { "expected a text frame, got opcode $opcode" }
        String(payload)
    }

    fun sendFrame(opcode: Int, payload: ByteArray) {
        val mask = byteArrayOf(5, 6, 7, 8)
        val head = byteArrayOf((0x80 or opcode).toByte(), (0x80 or payload.size).toByte())
        val body = mask + ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        socket.getOutputStream().run { write(head + body); flush() }
    }

    fun sendText(text: String) = sendFrame(WebSocketCodec.TEXT, text.toByteArray())

    /** Opens `/ws` with [pin]; returns the answer's head. */
    fun upgrade(pin: String): String {
        send(
            "GET /ws?k=$pin HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n",
        )
        return readHead()
    }
}
