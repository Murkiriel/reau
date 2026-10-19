// Copied from OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/AapRead.kt, commit 0abbe2a.
// Changes: package; Factory.create takes the TLS object and the message handler instead of the transport and the video
// path, which it used only to build that handler (AapMessageHandlerType).
// Ported from headunit-revived (AGPLv3): aap/AapRead.kt (trimmed to video-only)
package com.reau.app.aa

internal interface AapRead {
    fun read(): Int

    abstract class Base internal constructor(
        private val connection: AccessoryConnection?,
        internal val ssl: AapSsl,
        internal val handler: AapMessageHandler
    ) : AapRead {

        override fun read(): Int {
            if (connection == null) {
                AaLog.e("No connection.")
                return -1
            }
            return doRead(connection)
        }

        protected abstract fun doRead(connection: AccessoryConnection): Int
    }

    object Factory {
        fun create(connection: AccessoryConnection, ssl: AapSsl, handler: AapMessageHandler): AapRead =
            if (connection.isSingleMessage) {
                AapReadSingleMessage(connection, ssl, handler)
            } else {
                AapReadMultipleMessages(connection, ssl, handler)
            }
    }
}
