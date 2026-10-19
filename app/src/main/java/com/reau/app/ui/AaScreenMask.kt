package com.reau.app.ui

import com.reau.app.video.FrameFit

/**
 * The dash's mask over the "Tela do Android Auto": [on] as saved, the settings' fit [mapping] of Android Auto's
 * [sourceW]×[sourceH] frame, and [onChange] for the switch.
 */
class AaScreenMask(
    val on: Boolean,
    val mapping: FrameFit.Mapping,
    val sourceW: Int,
    val sourceH: Int,
    val onChange: (Boolean) -> Unit,
)
