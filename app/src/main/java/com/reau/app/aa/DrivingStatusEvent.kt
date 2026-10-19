// Copied from OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/DrivingStatusEvent.kt, commit 0abbe2a.
// Changes: package only.
// Ported from headunit-revived (AGPLv3): aap/protocol/messages/DrivingStatusEvent.kt
package com.reau.app.aa

import com.google.protobuf.Message
import com.reau.app.aa.proto.Sensors

class DrivingStatusEvent(status: Sensors.SensorBatch.DrivingStatusData.Status)
    : AapMessage(Channel.ID_SEN, Sensors.SensorsMsgType.SENSOR_EVENT_VALUE, makeProto(status)) {

    companion object {
        private fun makeProto(status: Sensors.SensorBatch.DrivingStatusData.Status): Message =
            Sensors.SensorBatch.newBuilder()
                .addDrivingStatus(Sensors.SensorBatch.DrivingStatusData.newBuilder().setStatus(status.number))
                .build()
    }
}
