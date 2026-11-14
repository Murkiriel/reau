package com.reau.app.net

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A probe, not a check of the app: what the process of REau can reach on the local network, to see how Android 16's `RESTRICT_LOCAL_NETWORK` (turned on
 * for the app with `adb shell am compat enable RESTRICT_LOCAL_NETWORK com.reau.app`, with the app stopped) treats it while the app is still on
 * `targetSdk` 36 and declares no permission for it (plan 25). It reports one line per case under the log tag
 * `LOCALNET` and never fails on what it finds: it only runs when `-e hostPort N` names a port where listeners on the host (the emulator's
 * 10.0.2.2) wait for a TCP connection and a UDP datagram (`scripts/emu/escutas_rede_local.py`), and it is skipped otherwise.
 */
class LocalNetworkProbeTest {
    private val tag = "LOCALNET"

    private fun say(line: String) {
        Log.i(tag, line)
        println("$tag $line")
    }

    private inline fun probe(what: String, block: () -> String) {
        val line = try {
            block()
        } catch (e: java.io.IOException) {
            "FAILED ${e.javaClass.simpleName}: ${e.message}"
        } catch (e: SecurityException) {
            "FAILED ${e.javaClass.simpleName}: ${e.message}"
        }
        say("$what: $line")
    }

    @Test
    fun whatTheProcessReachesOnTheLocalNetwork() {
        val port = InstrumentationRegistry.getArguments().getString("hostPort")?.toIntOrNull()
        assumeTrue("no -e hostPort: nothing to probe", port != null)
        val host = InstrumentationRegistry.getArguments().getString("host") ?: "10.0.2.2"

        probe("loopback TCP 127.0.0.1 (a server of this process, as the Android Auto link is)") {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                Socket().use { client ->
                    client.connect(InetSocketAddress("127.0.0.1", server.localPort), TIMEOUT_MS)
                    server.accept().close()
                    "connected"
                }
            }
        }
        probe("loopback UDP 127.0.0.1") {
            DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = TIMEOUT_MS
                DatagramSocket().use { client ->
                    client.send(DatagramPacket(byteArrayOf(1), 1, InetAddress.getByName("127.0.0.1"), server.localPort))
                    server.receive(DatagramPacket(ByteArray(8), 8))
                    "delivered"
                }
            }
        }
        probe("TCP $host:$port (the host's local network)") {
            Socket().use { it.connect(InetSocketAddress(host, port!!), TIMEOUT_MS) }
            "connected"
        }
        probe("UDP $host:$port (the host's local network; the dash's link is UDP)") {
            DatagramSocket().use { it.send(DatagramPacket("reau-probe".toByteArray(), 10, InetAddress.getByName(host), port!!)) }
            "sent (the host's listener says whether it arrived)"
        }
    }

    private companion object {
        const val TIMEOUT_MS = 4_000
    }
}
