package com.reau.app.web

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * As respostas do servidor web, byte a byte: o que o navegador (ou um invasor) recebe. Um erro é só o status, sem corpo
 * e sem dizer o que existe; a página nunca vai para o cache e nunca vaza o PIN por Referer; e o fechamento depois da
 * resposta não pode virar um reset que apague a resposta.
 */
class HttpResponsesTest {
    private fun run(block: (OutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also { block(it) }.toByteArray()

    /** Cabeça e corpo de uma resposta, separados na primeira linha em branco. */
    private fun split(bytes: ByteArray): Pair<String, ByteArray> {
        val text = String(bytes, Charsets.ISO_8859_1)
        val cut = text.indexOf("\r\n\r\n")
        assertTrue("a resposta não tem fim de cabeça: $text", cut >= 0)
        return text.substring(0, cut) to bytes.copyOfRange(cut + 4, bytes.size)
    }

    private fun headers(head: String): Map<String, String> =
        head.split("\r\n").drop(1).associate { it.substringBefore(": ") to it.substringAfter(": ") }

    @Test
    fun `um erro e so o status, sem corpo, sem cache e fechando a conexao`() {
        val bytes = run { HttpResponses.empty(it, 404) }
        assertEquals(
            "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n",
            String(bytes, Charsets.ISO_8859_1),
        )
    }

    @Test
    fun `cada status que o servidor usa tem a sua frase`() {
        val expected = mapOf(
            200 to "OK", 204 to "No Content", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found",
            405 to "Method Not Allowed", 429 to "Too Many Requests", 431 to "Request Header Fields Too Large",
            503 to "Service Unavailable",
        )
        for ((status, reason) in expected) {
            val (head, body) = split(run { HttpResponses.empty(it, status) })
            assertEquals(status.toString(), "HTTP/1.1 $status $reason", head.lineSequence().first())
            assertEquals(0, body.size)
        }
    }

    @Test
    fun `um status desconhecido sai com a frase generica, nunca sem frase`() {
        val (head, _) = split(run { HttpResponses.empty(it, 418) })
        assertEquals("HTTP/1.1 418 Error", head.lineSequence().first())
    }

    @Test
    fun `um erro nunca carrega tipo de conteudo nem texto que diga o que existe`() {
        for (status in listOf(400, 403, 404, 405, 429, 431, 503)) {
            val (head, body) = split(run { HttpResponses.empty(it, status) })
            assertFalse(head, head.contains("Content-Type", ignoreCase = true))
            assertEquals("Content-Length: 0", head.lines().first { it.startsWith("Content-Length") })
            assertEquals(0, body.size)
        }
    }

    @Test
    fun `a pagina sai como HTML sem cache, sem Referer e sem adivinhar o tipo`() {
        val body = "<html>REau</html>".toByteArray()
        val (head, got) = split(run { HttpResponses.page(it, body) })
        assertEquals("HTTP/1.1 200 OK", head.lineSequence().first())
        val h = headers(head)
        assertEquals("text/html; charset=utf-8", h["Content-Type"])
        assertEquals("no-referrer", h["Referrer-Policy"])
        assertEquals("nosniff", h["X-Content-Type-Options"])
        assertEquals("no-store", h["Cache-Control"])
        assertEquals("close", h["Connection"])
        assertArrayEquals(body, got)
    }

    @Test
    fun `o tamanho declarado e o dos bytes, nao o dos caracteres`() {
        val body = "ação — 日本".toByteArray(Charsets.UTF_8)
        assertTrue("o teste precisa de texto multibyte", body.size > "ação — 日本".length)
        val (head, got) = split(run { HttpResponses.page(it, body) })
        assertEquals(body.size.toString(), headers(head)["Content-Length"])
        assertArrayEquals(body, got)
        assertEquals("ação — 日本", String(got, Charsets.UTF_8))
    }

    @Test
    fun `uma pagina vazia declara tamanho zero e uma grande entra inteira`() {
        val (headEmpty, bodyEmpty) = split(run { HttpResponses.page(it, ByteArray(0)) })
        assertEquals("0", headers(headEmpty)["Content-Length"])
        assertEquals(0, bodyEmpty.size)

        val big = ByteArray(300_000) { (it % 251).toByte() }
        val (headBig, bodyBig) = split(run { HttpResponses.page(it, big) })
        assertEquals("300000", headers(headBig)["Content-Length"])
        assertArrayEquals(big, bodyBig)
    }

    @Test
    fun `cada linha da cabeca termina em CRLF e a cabeca nao tem linha solta`() {
        val bytes = run { HttpResponses.page(it, "x".toByteArray()) }
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse("sobrou um LF sem CR", text.replace("\r\n", "").contains('\n'))
        assertTrue(text.endsWith("\r\n\r\nx"))
    }

    @Test
    fun `a resposta e empurrada para a rede antes de a conexao fechar`() {
        var flushes = 0
        val out = object : ByteArrayOutputStream() {
            override fun flush() {
                flushes++
                super.flush()
            }
        }
        HttpResponses.empty(out, 403)
        assertTrue("o erro ficou no buffer", flushes >= 1)
        flushes = 0
        HttpResponses.page(out, ByteArray(1))
        assertTrue("a pagina ficou no buffer", flushes >= 1)
        flushes = 0
        HttpResponses.upgrade(out, "abc")
        assertTrue("o upgrade ficou no buffer", flushes >= 1)
    }

    @Test
    fun `o upgrade devolve o 101 com a chave de aceite e nada mais`() {
        val bytes = run { HttpResponses.upgrade(it, "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=") }
        assertEquals(
            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n",
            String(bytes, Charsets.ISO_8859_1),
        )
    }

    @Test
    fun `o upgrade com a chave de exemplo da RFC 6455 bate com o codec`() {
        // A chave de exemplo da RFC 6455, secao 1.3.
        val accept = WebSocketCodec.acceptKey("dGhlIHNhbXBsZSBub25jZQ==")
        val (head, body) = split(run { HttpResponses.upgrade(it, accept) })
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", headers(head)["Sec-WebSocket-Accept"])
        assertEquals(0, body.size)
    }

    /** Um soquete que anota a ordem do que se faz com ele, sem rede nenhuma. */
    private class RecordingSocket(private val shutdownFails: Boolean = false) : Socket() {
        val calls = mutableListOf<String>()
        var timeout = -1

        override fun shutdownOutput() {
            calls += "shutdownOutput"
            if (shutdownFails) throw IOException("already reset")
        }

        override fun setSoTimeout(timeout: Int) {
            calls += "soTimeout=$timeout"
            this.timeout = timeout
        }

        override fun close() {
            calls += "close"
            super.close()
        }
    }

    /** Um fluxo que entrega bytes sem fim e conta o que foi lido. */
    private class EndlessInput : InputStream() {
        var read = 0L
        override fun read(): Int {
            read++
            return 1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            read += len
            return len
        }
    }

    @Test
    fun `fecha primeiro a saida, depois escuta um pouco, e so entao fecha o soquete`() {
        val socket = RecordingSocket()
        HttpResponses.closeAfterAnswer(socket, ByteArrayInputStream(ByteArray(0)))
        assertEquals(listOf("shutdownOutput", "soTimeout=200", "close"), socket.calls)
        assertTrue(socket.isClosed)
    }

    @Test
    fun `o que o cliente ainda manda e lido e jogado fora ate um teto, sem prender a conexao`() {
        val socket = RecordingSocket()
        val endless = EndlessInput()
        HttpResponses.closeAfterAnswer(socket, endless)
        assertTrue("leu ${endless.read}, menos do que o teto de 64 KB", endless.read >= 64 * 1024)
        assertTrue("leu ${endless.read}, passou muito do teto de 64 KB", endless.read <= 64 * 1024 + 4096)
        assertEquals("close", socket.calls.last())
    }

    @Test
    fun `um cliente que fecha o seu lado encerra a leitura antes do teto`() {
        val socket = RecordingSocket()
        val input = ByteArrayInputStream(ByteArray(10))
        HttpResponses.closeAfterAnswer(socket, input)
        assertEquals("a leitura deveria ter ido ate o fim do fluxo", 0, input.available())
        assertEquals("close", socket.calls.last())
    }

    @Test
    fun `se a leitura falha o soquete fecha do mesmo jeito`() {
        val socket = RecordingSocket()
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("Connection reset")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("Connection reset")
        }
        HttpResponses.closeAfterAnswer(socket, broken)
        assertTrue(socket.isClosed)
    }

    @Test
    fun `se a saida nao fecha limpo o soquete fecha do mesmo jeito`() {
        val socket = RecordingSocket(shutdownFails = true)
        HttpResponses.closeAfterAnswer(socket, ByteArrayInputStream(ByteArray(0)))
        assertTrue(socket.isClosed)
        assertEquals("close", socket.calls.last())
    }
}
