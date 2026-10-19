package com.reau.app.ui

import com.reau.app.settings.REauSettings

/**
 * The fit on the "Tela do Android Auto" (the whole "Encaixe na cúpula" section lives there): the [settings] as saved,
 * and [change] to hand a changed fit over (with the projection on, the dash takes it at once).
 */
class AaScreenFit(
    val settings: REauSettings,
    /** The screen Android Auto shows now; the fit opens on its tab. */
    val screen: com.reau.app.aa.AaScreen = com.reau.app.aa.AaScreen.APPS,
    val change: (REauSettings) -> Unit,
)
