package com.reau.app.web

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * The web server: a `ServerSocket` and a thread for each connection, no library. `GET /` is the page (it holds
 * no secret; it asks for the PIN), `GET /auth?k=<pin>` says whether the PIN is right (204, 403 for a wrong one, 429 for an
 * address held for too many wrong ones, never with a body), and `GET /ws?k=<pin>` becomes a WebSocket under the same rule;
 * a client the network rule refuses ([Settings.allowed]) gets no byte at all. At most [Settings.maxViewers] browsers at once (the next
 * one is a 503). Nothing here knows about video or touch: the caller gives the page and takes the viewer's text through
 * [Events], and sends bytes with [publish] and [sendText].
 *
 * The log lines say the port and the viewer count, never the PIN or an address.
 */
class WebServer(private val settings: Settings, private val events: Events) {
    // One parameter per thing a test or the bridge may set, each with its default: a holder object would only hide them.
    @Suppress("LongParameterList")
    class Settings(
        val guard: WebGuard,
        val ports: IntRange = 8780..8789,
        val maxViewers: Int = 2,
        val queueFrames: Int = 200,
        val allowed: (InetAddress) -> Boolean = LocalNetwork::isAllowed,
        val headTimeoutMs: Int = 5_000,
        /** Whether a client is the phone itself: it is sent no sound ([LocalNetwork.isThePhone]). */
        val thePhone: (InetAddress) -> Boolean = LocalNetwork::isThePhone,
    )

    interface Events {
        /** The page, asked for at each `GET /`. */
        fun page(): ByteArray

        /** A viewer is in: queue on it what it must see first, before any published frame. */
        fun onJoin(viewer: WebViewer) = Unit

        /** A text message of a viewer (the touch). */
        fun onText(text: String) = Unit

        /** The number of viewers changed. */
        fun onViewers(count: Int) = Unit

        fun log(line: String) = Unit
    }

    private val viewers = WebViewers(settings.maxViewers, settings.queueFrames, events)
    private val handlers = AtomicInteger()
    private val perAddress = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var acceptor: Thread? = null

    /** The port in use, or 0 when stopped. */
    val port: Int get() = server?.localPort ?: 0

    /** The viewers in, as the caller was told. */
    val viewerCount: Int get() = viewers.liveCount

    /** Starts listening on the first free port of [Settings.ports]; returns it, or null if none is free. */
    fun start(): Int? {
        if (server != null) return port
        val bound = settings.ports.firstNotNullOfOrNull { p -> runCatching { ServerSocket(p, BACKLOG) }.getOrNull() } ?: return null
        server = bound
        acceptor = Thread({ acceptLoop(bound) }, "web-accept").apply { isDaemon = true }.also { it.start() }
        events.log("web: server on port ${bound.localPort}")
        return bound.localPort
    }

    /** Closes the port and drops every viewer. */
    fun stop() {
        val bound = server ?: return
        server = null
        runCatching { bound.close() }
        kick()
        acceptor?.join(JOIN_MS)
        acceptor = null
    }

    /**
     * Sends [bytes] as one binary message to every viewer in; a viewer too slow for it is dropped. With [sound] the message is one of
     * sound: a viewer too slow for it loses that piece of sound and stays.
     */
    fun publish(bytes: ByteArray, sound: Boolean = false) = viewers.broadcast(WebSocketCodec.encode(WebSocketCodec.BINARY, bytes), sound)

    /** Sends [text] as one text message to every viewer in. */
    fun sendText(text: String) = viewers.broadcast(WebSocketCodec.encode(WebSocketCodec.TEXT, text.toByteArray()))

    /** Drops every viewer. The server goes on listening. */
    fun kick() = viewers.closeAll()

    private fun acceptLoop(bound: ServerSocket) {
        while (!bound.isClosed) {
            val socket = try {
                bound.accept()
            } catch (_: IOException) {
                break
            }
            dispatch(socket)
        }
    }

    private fun dispatch(socket: Socket) {
        // Only this thread adds to the counts, so the check and the increment need no lock.
        val mine = socket.inetAddress?.takeIf(settings.allowed)?.let { perAddress.computeIfAbsent(it.hostAddress.orEmpty()) { AtomicInteger() } }
        if (mine == null || handlers.get() >= MAX_HANDLERS || mine.get() >= MAX_PER_ADDRESS) {
            runCatching { socket.close() }
            return
        }
        mine.incrementAndGet()
        handlers.incrementAndGet()
        Thread({ serve(socket, mine) }, "web-connection").apply { isDaemon = true }.start()
    }

    /** One connection, from its first byte to its close. A WebSocket stays inside [route] until the viewer leaves. */
    private fun serve(socket: Socket, mine: AtomicInteger) {
        val input = BufferedInputStream(socket.getInputStream())
        try {
            socket.soTimeout = settings.headTimeoutMs
            when (val parsed = HttpRequestParser.parse(input, settings.headTimeoutMs.toLong())) {
                is HttpRequestParser.Result.Bad -> HttpResponses.empty(socket.getOutputStream(), parsed.status)
                is HttpRequestParser.Result.Ok -> route(socket, input, parsed.request)
            }
        } catch (_: IOException) {
            // the peer went, or stalled past the head's time: nothing to answer
        } finally {
            HttpResponses.closeAfterAnswer(socket, input)
            handlers.decrementAndGet()
            mine.decrementAndGet()
        }
    }

    private fun route(socket: Socket, input: InputStream, request: HttpRequest) {
        val out = socket.getOutputStream()
        when {
            request.path == "/" -> HttpResponses.page(out, events.page())
            // A page of another site can open a connection to the phone's address too; it is refused before the PIN is looked at.
            request.path in listOf("/auth", "/ws") && !sameSite(request) -> HttpResponses.empty(out, 403)
            request.path == "/auth" -> HttpResponses.empty(out, settings.guard.verdictOf(socket, request, events::log).status)
            request.path == "/ws" -> webSocket(socket, input, request)
            else -> HttpResponses.empty(out, 404)
        }
    }

    private fun webSocket(socket: Socket, input: InputStream, request: HttpRequest) {
        val out = socket.getOutputStream()
        val verdict = settings.guard.verdictOf(socket, request, events::log)
        val key = upgradeKey(request)
        val viewer = if (verdict == WebGuard.Verdict.OK && key != null) viewers.reserve(socket, soundOff = settings.isOnThePhone(socket)) else null
        if (key == null || viewer == null) {
            return HttpResponses.empty(out, if (verdict != WebGuard.Verdict.OK) verdict.status else if (key == null) 400 else 503)
        }
        try {
            HttpResponses.upgrade(out, WebSocketCodec.acceptKey(key))
            socket.soTimeout = 0
            viewer.startWriter()
            events.onJoin(viewer)
            viewers.join(viewer)
            viewers.listen(viewer, input)
        } finally {
            viewer.close()
        }
    }

    private companion object {
        const val BACKLOG = 8
        const val MAX_HANDLERS = 16
        const val MAX_PER_ADDRESS = 10
        const val JOIN_MS = 1_000L
    }
}

/**
 * The guard's word on the PIN this request brought, counted against the address it came from. A wrong one and a held address are
 * said to [log] (the fact only: never the PIN tried, the PIN, or an address).
 */
private fun WebGuard.verdictOf(socket: Socket, request: HttpRequest, log: (String) -> Unit): WebGuard.Verdict =
    check(socket.inetAddress?.hostAddress.orEmpty(), request.query["k"]).also {
        when (it) {
            WebGuard.Verdict.WRONG -> log("web: wrong pin")
            WebGuard.Verdict.LOCKED -> log("web: address held for too many wrong pins")
            WebGuard.Verdict.OK -> Unit
        }
    }

/** Whether [socket]'s client is the phone itself: it is sent no sound. */
private fun WebServer.Settings.isOnThePhone(socket: Socket): Boolean = socket.inetAddress?.let(thePhone) == true

/**
 * Whether the request is from this server's own page (or from no browser at all): a browser says where the page came from in
 * `Origin`, and it must be this very address, port included. A request with no `Origin` is not a page of some other site.
 */
private fun sameSite(request: HttpRequest): Boolean {
    val origin = request.headers["origin"] ?: return true
    val host = request.headers["host"] ?: return false
    return origin.equals("http://$host", ignoreCase = true)
}

/** The key of a well formed WebSocket upgrade, or null. */
private fun upgradeKey(request: HttpRequest): String? {
    val upgrade = request.headers["upgrade"].equals("websocket", ignoreCase = true) &&
        request.headers["connection"].orEmpty().contains("upgrade", ignoreCase = true) &&
        request.headers["sec-websocket-version"] == "13"
    return request.headers["sec-websocket-key"]?.takeIf { upgrade && it.isNotBlank() }
}
