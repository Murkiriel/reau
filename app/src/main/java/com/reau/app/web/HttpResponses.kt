package com.reau.app.web

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/** The few answers the web server gives. An error is its status and nothing else: it never says what exists. */
internal object HttpResponses {
    private val REASONS = mapOf(
        200 to "OK", 204 to "No Content", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed",
        429 to "Too Many Requests", 431 to "Request Header Fields Too Large", 503 to "Service Unavailable",
    )

    /** An error with no body. */
    fun empty(out: OutputStream, status: Int) = write(out, status, emptyList(), ByteArray(0))

    /** The page: HTML, never cached, and a PIN in its address never leaves in a Referer. */
    fun page(out: OutputStream, body: ByteArray) = write(
        out, 200,
        listOf("Content-Type: text/html; charset=utf-8", "Referrer-Policy: no-referrer", "X-Content-Type-Options: nosniff"),
        body,
    )

    /** The answer that turns the connection into a WebSocket. */
    fun upgrade(out: OutputStream, accept: String) {
        val head = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    /**
     * Closes [socket] after the answer without a reset: an answer sent while the client's bytes were still unread would be
     * lost to a reset, so the output is closed first and what the client still sends is read (briefly) and thrown away.
     */
    fun closeAfterAnswer(socket: Socket, input: InputStream) {
        runCatching {
            socket.shutdownOutput()
            socket.soTimeout = LINGER_MS
            val drain = ByteArray(DRAIN_BYTES)
            var left = LINGER_BYTES
            while (left > 0 && input.read(drain) >= 0) left -= drain.size
        }
        runCatching { socket.close() }
    }

    private fun write(out: OutputStream, status: Int, extra: List<String>, body: ByteArray) {
        val head = StringBuilder("HTTP/1.1 $status ${REASONS[status] ?: "Error"}\r\n")
        extra.forEach { head.append(it).append("\r\n") }
        head.append("Content-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private const val LINGER_MS = 200
    private const val LINGER_BYTES = 64 * 1024
    private const val DRAIN_BYTES = 4096
}
