package com.reau.app.web

import java.io.InputStream
import java.net.URLDecoder

/** One GET as the web server reads it: the path (as sent), the decoded query and the headers by lower case name. */
class HttpRequest(val path: String, val query: Map<String, String>, val headers: Map<String, String>)

/**
 * Reads the head of one request, bounded: [MAX_HEAD] bytes and [MAX_HEADERS] headers. Only GET is served.
 * Reads one byte at a time, so the caller hands in a buffered stream.
 */
object HttpRequestParser {
    const val MAX_HEAD = 8192
    const val MAX_HEADERS = 64
    private const val END_OF_HEAD = "\r\n\r\n"
    private const val NS_PER_MS = 1_000_000L

    sealed interface Result {
        class Ok(val request: HttpRequest) : Result

        /** The answer to give: [status] with a short [reason] (never sent as a body). */
        class Bad(val status: Int, val reason: String) : Result
    }

    /** The request at the start of [input]; with [totalMs] the whole head must come within that time (a head that drips in is cut off). */
    fun parse(input: InputStream, totalMs: Long = Long.MAX_VALUE): Result {
        val head = readHead(input, totalMs) ?: return Result.Bad(400, "incomplete request")
        if (head.length > MAX_HEAD) return Result.Bad(431, "request head too large")
        val lines = head.split("\r\n").filter { it.isNotEmpty() }
        val parts = lines.firstOrNull()?.split(" ") ?: return Result.Bad(400, "no request line")
        return when {
            parts.size != 3 -> Result.Bad(400, "bad request line")
            parts[0] != "GET" -> Result.Bad(405, "only GET")
            !parts[1].startsWith("/") -> Result.Bad(400, "bad path")
            lines.size - 1 > MAX_HEADERS -> Result.Bad(431, "too many headers")
            else -> build(parts[1], lines.drop(1))
        }
    }

    /** The text up to the blank line, or null if the stream ends first; one byte past [MAX_HEAD] stops the reading. */
    private fun readHead(input: InputStream, totalMs: Long): String? {
        val text = StringBuilder()
        var ended = false
        val deadline = if (totalMs == Long.MAX_VALUE) Long.MAX_VALUE else System.nanoTime() + totalMs * NS_PER_MS
        while (!ended && text.length <= MAX_HEAD && !text.endsWith(END_OF_HEAD)) {
            if (System.nanoTime() > deadline) throw java.io.IOException("the head came too slowly")
            val b = input.read()
            if (b < 0) ended = true else text.append(b.toChar())
        }
        return text.takeUnless { ended }?.toString()
    }

    private fun build(target: String, headerLines: List<String>): Result {
        val query = runCatching { queryOf(target.substringAfter('?', "")) }.getOrNull()
        return when {
            headerLines.any { it.indexOf(':') <= 0 } -> Result.Bad(400, "bad header")
            query == null -> Result.Bad(400, "bad query")
            else -> Result.Ok(
                HttpRequest(
                    target.substringBefore('?'), query,
                    headerLines.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() },
                ),
            )
        }
    }

    /** `a=b&c` as a map; a missing value is empty and the first of a repeated key stays. */
    private fun queryOf(raw: String): Map<String, String> {
        val query = LinkedHashMap<String, String>()
        for (pair in raw.split('&').filter { it.isNotEmpty() }) {
            val key = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            query.putIfAbsent(key, URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8"))
        }
        return query
    }
}
