package com.reau.app.web

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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

/**
 * Os espectadores do servidor web: o limite de lugares (que vale ate para quem ainda aperta a mao), quem recebe o
 * broadcast, a contagem avisada aos eventos na ordem certa e a leitura das mensagens do navegador. Sem rede: o soquete
 * de cada espectador e um falso que guarda o que o escritor dele mandou.
 */
class WebViewersTest {
    /** Um soquete sem rede cuja saida guarda os bytes, para ver o que o escritor do espectador mandou. */
    private class CapturingSocket : Socket() {
        private val sink = ByteArrayOutputStream()
        private val monitor = Object()

        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) = synchronized(monitor) {
                sink.write(b)
                monitor.notifyAll()
            }

            override fun write(b: ByteArray, off: Int, len: Int) = synchronized(monitor) {
                sink.write(b, off, len)
                monitor.notifyAll()
            }
        }

        /** Espera ate haver [count] bytes (ou 5 s) e devolve o que chegou. */
        fun await(count: Int): ByteArray {
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            synchronized(monitor) {
                while (sink.size() < count) {
                    val left = TimeUnit.NANOSECONDS.toMillis(limit - System.nanoTime())
                    if (left <= 0) break
                    monitor.wait(left)
                }
                return sink.toByteArray()
            }
        }

        fun sent(): ByteArray = synchronized(monitor) { sink.toByteArray() }
    }

    private class Events : WebServer.Events {
        val counts = LinkedBlockingQueue<Int>()
        val lines = CopyOnWriteArrayList<String>()
        val texts = LinkedBlockingQueue<String>()

        override fun page() = ByteArray(0)
        override fun onViewers(count: Int) {
            counts.put(count)
        }
        override fun onText(text: String) {
            texts.put(text)
        }
        override fun log(line: String) {
            lines += line
        }

        /** O proximo aviso de contagem (ou falha em 5 s). */
        fun nextCount(): Int = counts.poll(5, TimeUnit.SECONDS) ?: error("nenhuma contagem foi avisada")
    }

    private val events = Events()
    private val opened = mutableListOf<CapturingSocket>()
    private val viewers = mutableListOf<WebViewer>()

    @After
    fun cleanUp() {
        viewers.forEach { it.close() }
    }

    private fun newViewers(max: Int = 2, queue: Int = 8) = WebViewers(max, queue, events)

    private fun WebViewers.take(soundOff: Boolean = false, write: Boolean = true): Pair<WebViewer, CapturingSocket> {
        val socket = CapturingSocket().also { opened += it }
        val viewer = reserve(socket, soundOff) ?: error("sem lugar")
        viewers += viewer
        if (write) viewer.startWriter()
        return viewer to socket
    }

    private fun text(s: String) = WebSocketCodec.encode(WebSocketCodec.TEXT, s.toByteArray())

    private fun binary(vararg b: Int) = WebSocketCodec.encode(WebSocketCodec.BINARY, ByteArray(b.size) { b[it].toByte() })

    /** Uma mensagem do navegador: mascarada, como a RFC 6455 exige do cliente. */
    private fun masked(opcode: Int, payload: ByteArray): ByteArray {
        val mask = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val head = when {
            payload.size <= 125 -> byteArrayOf((0x80 or opcode).toByte(), (0x80 or payload.size).toByte())
            else -> byteArrayOf((0x80 or opcode).toByte(), (0x80 or 126).toByte(), (payload.size shr 8).toByte(), payload.size.toByte())
        }
        return head + mask + ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
    }

    // ---- lugares ----

    @Test
    fun `os lugares acabam no limite e o proximo e recusado`() {
        val v = newViewers(max = 2)
        v.take(write = false)
        v.take(write = false)
        assertNull(v.reserve(CapturingSocket()))
    }

    @Test
    fun `quem ainda aperta a mao ja ocupa lugar, mesmo sem contar como espectador`() {
        val v = newViewers(max = 1)
        v.take(write = false)
        assertEquals(0, v.liveCount)
        assertNull("o lugar de quem nao entrou ainda tem de valer", v.reserve(CapturingSocket()))
    }

    @Test
    fun `um espectador que sai devolve o lugar`() {
        val v = newViewers(max = 1)
        val (first, _) = v.take(write = false)
        first.close()
        assertNotNull(v.reserve(CapturingSocket()))
    }

    @Test
    fun `um limite zero nao deixa ninguem entrar`() {
        assertNull(newViewers(max = 0).reserve(CapturingSocket()))
    }

    // ---- contagem e eventos ----

    @Test
    fun `reservar nao avisa nem conta, so entrar`() {
        val v = newViewers()
        val (viewer, _) = v.take(write = false)
        assertEquals(0, v.liveCount)
        assertFalse(viewer.live)
        assertNull("reservar nao deve avisar contagem", events.counts.poll(100, TimeUnit.MILLISECONDS))
        assertTrue(events.lines.isEmpty())
    }

    @Test
    fun `entrar conta, avisa a contagem e escreve no log sem endereco nem PIN`() {
        val v = newViewers()
        val (viewer, _) = v.take(write = false)
        v.join(viewer)
        assertTrue(viewer.live)
        assertEquals(1, v.liveCount)
        assertEquals(1, events.nextCount())
        assertEquals(listOf("web: viewer joined (1)"), events.lines.toList())
    }

    @Test
    fun `as contagens chegam aos eventos na ordem em que foram feitas`() {
        val v = newViewers(max = 3)
        val (a, _) = v.take(write = false)
        val (b, _) = v.take(write = false)
        val (c, _) = v.take(write = false)
        v.join(a)
        v.join(b)
        v.join(c)
        b.close()
        a.close()
        c.close()
        val told = List(6) { events.nextCount() }
        assertEquals(listOf(1, 2, 3, 2, 1, 0), told)
    }

    @Test
    fun `quem sai sem ter entrado nao e contado nem avisado`() {
        val v = newViewers()
        val (viewer, _) = v.take(write = false)
        viewer.close()
        assertNull(events.counts.poll(100, TimeUnit.MILLISECONDS))
        assertTrue("nao ha o que dizer de quem nunca entrou: ${events.lines}", events.lines.isEmpty())
    }

    @Test
    fun `quem sai depois de entrar e avisado uma vez so, mesmo se for fechado de novo`() {
        val v = newViewers()
        val (viewer, _) = v.take(write = false)
        v.join(viewer)
        assertEquals(1, events.nextCount())
        viewer.close()
        viewer.close()
        assertEquals(0, events.nextCount())
        assertNull("o segundo fechamento nao deve avisar de novo", events.counts.poll(100, TimeUnit.MILLISECONDS))
        assertEquals(listOf("web: viewer joined (1)", "web: viewer left (0)"), events.lines.toList())
    }

    @Test
    fun `o que ouve o aviso nunca roda na thread que encerrou o espectador`() {
        val seen = LinkedBlockingQueue<Thread>()
        val v = WebViewers(2, 8, object : WebServer.Events {
            override fun page() = ByteArray(0)
            override fun onViewers(count: Int) {
                seen.put(Thread.currentThread())
            }
        })
        val (viewer, _) = v.take(write = false)
        v.join(viewer)
        viewer.close()
        repeat(2) { assertTrue(seen.poll(5, TimeUnit.SECONDS) !== Thread.currentThread()) }
    }

    // ---- som desligado ----

    @Test
    fun `quem esta no proprio celular recebe o aviso de que nao tera som, logo ao entrar`() {
        val v = newViewers()
        val (viewer, socket) = v.take(soundOff = true)
        v.join(viewer)
        val expected = text(WebProtocol.SOUND_OFF)
        assertArrayEquals(expected, socket.await(expected.size).copyOf(expected.size))
    }

    @Test
    fun `quem nao esta no celular nao recebe aviso de som`() {
        val v = newViewers()
        val (viewer, socket) = v.take()
        v.join(viewer)
        v.broadcast(binary(1, 2))
        val expected = binary(1, 2)
        assertArrayEquals("so o broadcast deveria ter chegado", expected, socket.await(expected.size))
    }

    // ---- broadcast ----

    @Test
    fun `o broadcast so alcanca quem ja entrou`() {
        val v = newViewers()
        val (viewer, socket) = v.take()
        v.broadcast(binary(1))
        v.join(viewer)
        v.broadcast(binary(2))
        val expected = binary(2)
        assertArrayEquals("o quadro de antes de entrar nao deve chegar", expected, socket.await(expected.size))
    }

    @Test
    fun `o quadro chega inteiro e igual a todos que entraram`() {
        val v = newViewers()
        val (a, sa) = v.take()
        val (b, sb) = v.take()
        v.join(a)
        v.join(b)
        val frame = binary(9, 8, 7, 6)
        v.broadcast(frame)
        assertArrayEquals(frame, sa.await(frame.size))
        assertArrayEquals(frame, sb.await(frame.size))
    }

    @Test
    fun `o som nao vai a quem esta no celular, o video vai a todos`() {
        val v = newViewers()
        val (phone, onPhone) = v.take(soundOff = true)
        val (remote, onRemote) = v.take()
        v.join(phone)
        v.join(remote)
        val soundOffMessage = text(WebProtocol.SOUND_OFF)
        onPhone.await(soundOffMessage.size)
        val sound = binary(0xAA, 0xBB)
        val video = binary(0x01, 0x02, 0x03)
        v.broadcast(sound, droppable = true)
        v.broadcast(video)
        assertArrayEquals(soundOffMessage + video, onPhone.await(soundOffMessage.size + video.size))
        assertArrayEquals(sound + video, onRemote.await(sound.size + video.size))
    }

    @Test
    fun `um espectador lento e derrubado sem atrapalhar o rapido`() {
        val v = newViewers(max = 2, queue = 2)
        val (slow, _) = v.take(write = false)
        val (fast, fastSocket) = v.take(write = false)
        v.join(slow)
        v.join(fast)
        events.nextCount()
        events.nextCount()
        // As duas filas enchem; so o rapido volta a escoar.
        v.broadcast(binary(0))
        v.broadcast(binary(1))
        fast.startWriter()
        val first = binary(0) + binary(1)
        assertArrayEquals(first, fastSocket.await(first.size))
        v.broadcast(binary(2))
        assertEquals("so o lento sai", 1, v.liveCount)
        assertTrue(fast.live)
        assertFalse(slow.sendText("ainda aqui?"))
        val all = first + binary(2)
        assertArrayEquals(all, fastSocket.await(all.size))
        assertEquals("o aviso de saida do lento", 1, events.nextCount())
    }

    @Test
    fun `o som e deixado de lado antes de derrubar o espectador`() {
        val v = newViewers(max = 1, queue = 4)
        val (viewer, _) = v.take(write = false)
        v.join(viewer)
        // A fila comporta 4; o som para na metade (2) e e solto, sem derrubar ninguem.
        repeat(10) { v.broadcast(binary(it), droppable = true) }
        assertEquals("o som em excesso nao pode tirar o espectador", 1, v.liveCount)
        // O video ainda cabe na outra metade e so o excesso dele derruba.
        v.broadcast(binary(100))
        v.broadcast(binary(101))
        assertEquals(1, v.liveCount)
        v.broadcast(binary(102))
        assertEquals(0, v.liveCount)
    }

    @Test
    fun `fechar todos fecha tambem quem ainda nao entrou e libera os lugares`() {
        val v = newViewers(max = 2)
        val (joined, _) = v.take(write = false)
        val (shaking, shakingSocket) = v.take(write = false)
        v.join(joined)
        v.closeAll()
        assertEquals(0, v.liveCount)
        assertTrue(shakingSocket.isClosed)
        assertTrue(opened.all { it.isClosed })
        assertFalse(shaking.sendText("tarde demais"))
        assertNotNull("os lugares deveriam estar livres", v.reserve(CapturingSocket()))
    }

    @Test
    fun `fechar todos com ninguem la dentro nao faz nada`() {
        val v = newViewers()
        v.closeAll()
        assertNull(events.counts.poll(100, TimeUnit.MILLISECONDS))
    }

    // ---- escuta ----

    private fun listen(v: WebViewers, viewer: WebViewer, bytes: ByteArray) = v.listen(viewer, ByteArrayInputStream(bytes))

    @Test
    fun `o texto do navegador chega aos eventos como UTF-8, na ordem`() {
        val v = newViewers()
        val (viewer, _) = v.take(write = false)
        listen(v, viewer, masked(WebSocketCodec.TEXT, "{\"x\":1}".toByteArray()) + masked(WebSocketCodec.TEXT, "ação".toByteArray()))
        assertEquals("{\"x\":1}", events.texts.poll())
        assertEquals("ação", events.texts.poll())
        assertNull(events.texts.poll())
    }

    @Test
    fun `um ping e respondido com um pong de mesmo conteudo para o proprio navegador`() {
        val v = newViewers()
        val (viewer, socket) = v.take()
        listen(v, viewer, masked(WebSocketCodec.PING, byteArrayOf(1, 2, 3)))
        val pong = WebSocketCodec.encode(WebSocketCodec.PONG, byteArrayOf(1, 2, 3))
        assertArrayEquals(pong, socket.await(pong.size))
        assertTrue("um ping nao e texto para os eventos", events.texts.isEmpty())
    }

    @Test
    fun `um close encerra a escuta e o que vem depois nao e lido`() {
        val v = newViewers()
        val (viewer, _) = v.take(write = false)
        listen(v, viewer, masked(WebSocketCodec.CLOSE, ByteArray(0)) + masked(WebSocketCodec.TEXT, "depois".toByteArray()))
        assertTrue(events.texts.isEmpty())
    }

    @Test
    fun `um pong e uma mensagem binaria do navegador sao ignorados sem encerrar a escuta`() {
        val v = newViewers()
        val (viewer, _) = v.take(write = false)
        listen(
            v, viewer,
            masked(WebSocketCodec.PONG, ByteArray(0)) + masked(WebSocketCodec.BINARY, byteArrayOf(5)) + masked(WebSocketCodec.TEXT, "ok".toByteArray()),
        )
        assertEquals("ok", events.texts.poll())
    }

    @Test
    fun `o fim do fluxo encerra a escuta sem erro`() {
        val v = newViewers()
        val (viewer, _) = v.take(write = false)
        listen(v, viewer, ByteArray(0))
    }

    @Test
    fun `uma violacao do protocolo encerra a escuta sem lancar e sem entregar nada`() {
        val v = newViewers()
        val (viewer, _) = v.take(write = false)
        // Sem mascara: o cliente tem de mascarar sempre.
        val unmasked = byteArrayOf((0x80 or WebSocketCodec.TEXT).toByte(), 2, 'o'.code.toByte(), 'k'.code.toByte())
        listen(v, viewer, unmasked + masked(WebSocketCodec.TEXT, "depois".toByteArray()))
        assertTrue(events.texts.isEmpty())
    }

    @Test
    fun `uma mensagem acima de 4096 bytes e recusada`() {
        val v = newViewers()
        val (viewer, _) = v.take(write = false)
        listen(v, viewer, masked(WebSocketCodec.TEXT, ByteArray(4097) { 'a'.code.toByte() }))
        assertTrue(events.texts.isEmpty())
    }

    @Test
    fun `uma mensagem de exatamente 4096 bytes passa`() {
        val v = newViewers()
        val (viewer, _) = v.take(write = false)
        val payload = ByteArray(4096) { 'a'.code.toByte() }
        listen(v, viewer, masked(WebSocketCodec.TEXT, payload))
        assertEquals(String(payload), events.texts.poll())
    }

    @Test
    fun `uma mensagem cortada no meio encerra a escuta sem lancar`() {
        val v = newViewers()
        val (viewer, _) = v.take(write = false)
        val whole = masked(WebSocketCodec.TEXT, "meio".toByteArray())
        listen(v, viewer, whole.copyOf(whole.size - 2))
        assertTrue(events.texts.isEmpty())
    }

    @Test
    fun `uma falha de leitura do soquete encerra a escuta sem lancar`() {
        val v = newViewers()
        val (viewer, _) = v.take(write = false)
        v.listen(viewer, object : InputStream() {
            override fun read(): Int = throw IOException("Connection reset")
        })
    }
}
