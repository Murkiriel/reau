// Copied from OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/MediaAck.kt, commit 0abbe2a.
// Changes: package only.
// Ported from headunit-revived (AGPLv3): aap/protocol/messages/MediaAck.kt
package com.reau.app.aa

import com.google.protobuf.Message
import com.reau.app.aa.proto.Media

class MediaAck(channel: Int, sessionId: Int)
    : AapMessage(channel, Media.MsgType.MEDIA_MESSAGE_ACK_VALUE, makeProto(sessionId)) {

    companion object {
        private fun makeProto(sessionId: Int): Message =
            Media.Ack.newBuilder().apply {
                this.sessionId = sessionId
                this.ack = 1
            }.build()
    }
}
