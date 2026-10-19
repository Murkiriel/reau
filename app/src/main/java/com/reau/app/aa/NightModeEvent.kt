// Copied from OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/NightModeEvent.kt, commit 0abbe2a.
// Changes: package only.
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package com.reau.app.aa

import com.google.protobuf.Message
import com.reau.app.aa.proto.Sensors

/**
 * Reports the head unit's NIGHT sensor to Android Auto. Maps/Waze switch between their light and dark
 * map styles based on this, so sending is_night_mode=true is what makes the dash go dark.
 */
class NightModeEvent(isNight: Boolean)
    : AapMessage(Channel.ID_SEN, Sensors.SensorsMsgType.SENSOR_EVENT_VALUE, makeProto(isNight)) {

    companion object {
        private fun makeProto(isNight: Boolean): Message =
            Sensors.SensorBatch.newBuilder()
                .addNightMode(Sensors.SensorBatch.NightData.newBuilder().setIsNightMode(isNight))
                .build()
    }
}
