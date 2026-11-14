package com.reau.app.aa

import com.reau.app.aa.proto.NavigationStatus

/**
 * What the fuzz targets need of the Android Auto readers, which are internal to this package: the message reader over a connection that
 * is only the bytes given, the video's unit splitter and size header, and the navigation channel.
 */
internal object AaReadFuzz {
    /** A connection that holds [data] and nothing else: a full read gives what is left (less than asked at the end), then the end of the stream. */
    private class Bytes(private val data: ByteArray) : AccessoryConnection {
        private var at = 0
        override val isSingleMessage = true
        override val isConnected = true
        override fun connect() = true
        override fun disconnect() = Unit
        override fun sendBlocking(buf: ByteArray, length: Int, timeout: Int) = length
        override fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int {
            if (at >= data.size) return -1
            val n = minOf(length, data.size - at)
            System.arraycopy(data, at, buf, 0, n)
            at += n
            return n
        }
    }

    /** No TLS: what came is what is read. */
    private object Plain : AapSsl {
        override fun decrypt(start: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit? =
            ByteArrayWithLimit(buffer.copyOfRange(start, start + length), length)
        override fun encrypt(offset: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit? = null
        override fun postHandshakeReset() = Unit
        override fun performHandshake(connection: AccessoryConnection) = true
        override fun release() = Unit
    }

    /**
     * Reads [data] as a stream of messages until the reader says the stream ended, or for as many reads as the stream has bytes (each read
     * takes at least one): what each read answered, and the bytes of the messages handed over.
     */
    fun readAll(data: ByteArray): Pair<List<Int>, Int> {
        var handedOver = 0
        val handler = object : AapMessageHandler {
            override fun handle(message: AapMessage) {
                handedOver += message.size
            }
        }
        val reader = AapReadSingleMessage(Bytes(data), Plain, handler)
        val results = mutableListOf<Int>()
        repeat(data.size + 2) {
            if (results.lastOrNull().let { it == null || it == 0 }) results += reader.read()
        }
        return results to handedOver
    }

    fun units(data: ByteArray, each: (ByteArray, Int) -> Unit): Int = NalSplitter.forEachNalUnit(data, 0, data.size, each)

    fun sps(data: ByteArray, offset: Int, size: Int): SpsData? = SpsParser.parse(data, offset, size)

    private val KINDS = listOf(
        NavigationStatus.MsgType.INSTRUMENT_CLUSTER_START_VALUE,
        NavigationStatus.MsgType.INSTRUMENT_CLUSTER_STOP_VALUE,
        NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATUS_VALUE,
        NavigationStatus.MsgType.NEXTTURNDETAILS_VALUE,
        NavigationStatus.MsgType.NEXTTURNDISTANCEANDTIME_VALUE,
        NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE,
        NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE,
    )

    /**
     * [data] as navigation messages to a guidance that is already active: the first byte picks the kind of the message, the rest is its body,
     * given once whole and once as every kind in turn. What the channel published.
     */
    fun navigation(data: ByteArray): List<NavGuidance> {
        val seen = mutableListOf<NavGuidance>()
        val control = AapControlNavigation { seen += it }
        val active = NavigationStatus.NavigationClusterStatus.newBuilder()
            .setStatus(NavigationStatus.NavigationClusterStatus.NavigationStatusEnum.ACTIVE).buildPartial()
        control.execute(AapMessage(Channel.ID_NAV, NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATUS_VALUE, active))
        if (data.isEmpty()) return seen
        val body = data.copyOfRange(1, data.size)
        val first = KINDS[(data[0].toInt() and 0xFF) % KINDS.size]
        for (kind in listOf(first) + KINDS) control.execute(AapMessage(Channel.ID_NAV, 0x0B, kind, 0, body.size, body))
        return seen
    }
}
