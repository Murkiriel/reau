// Written for REau. The technique (an explicit broadcast to Android Auto's WirelessStartupReceiver naming an address and
// a port, with Android Auto's own screen in front) was found by reading OpenCfMoto's AaSelfMode (AGPL-3.0) and tried on
// Android Auto 17.3 on the emulator: the receiver is exported and enabled there, and Android Auto then dials the port.
package com.reau.app.aa

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * The way into Android Auto 17.3 and older, where Android Auto dials the head unit instead of the head unit dialling the
 * server (which 17.4 introduced, behind a developer switch). Android Auto is asked, with no accessibility and no
 * developer option, to project to a port on 127.0.0.1 where [AaReceiver] listens.
 *
 * Android Auto answers the ask by opening its own start-up screen, and Android refuses that from the background; so its
 * settings screen (an exported one) is brought to the front first, and the ask follows a moment later.
 */
internal object AaCallsIn {
    /** The port [AaReceiver] listens on for Android Auto 17.3 and older. */
    const val PORT = 5288

    private const val RECEIVER = "com.google.android.apps.auto.wireless.setup.receiver.WirelessStartupReceiver"
    private const val ACTION = "com.google.android.apps.auto.wireless.setup.receiver.wirelessstartup.START"
    private const val FRONT_MS = 1_200L
    private const val LOOPBACK = "127.0.0.1"

    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Asks Android Auto to dial [LOOPBACK]:[port]; [log] gets what was done and what Android refused. */
    fun poke(context: Context, port: Int = PORT, log: (String) -> Unit) {
        val app = context.applicationContext
        try {
            app.startActivity(AndroidAuto.settingsIntent())
        } catch (e: SecurityException) {
            log("AaCallsIn: Android Auto's screen could not be brought to the front (${e.message})")
        } catch (e: ActivityNotFoundException) {
            log("AaCallsIn: Android Auto has no settings screen to bring forward (${e.message})")
        }
        main.postDelayed({ ask(app, port, log) }, FRONT_MS)
    }

    private fun ask(app: Context, port: Int, log: (String) -> Unit) {
        val intent = Intent(ACTION)
            .setClassName(AndroidAuto.PACKAGE, RECEIVER)
            .putExtra("ip_address", LOOPBACK)
            .putExtra("projection_port", port)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        try {
            app.sendBroadcast(intent)
            log("AaCallsIn: asked Android Auto to dial $LOOPBACK:$port")
        } catch (e: SecurityException) {
            log("AaCallsIn: the ask was refused (${e.message})")
        }
    }
}
