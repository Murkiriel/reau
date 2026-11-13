package com.reau.app.ui

import com.reau.app.settings.REauSettings

/**
 * The fit on the "Tela do Android Auto" (the whole "Encaixe na cúpula" section lives there): the [settings] as saved,
 * [change] to hand a changed fit over (with the projection on, the dash takes it at once), and the fit [assistant].
 */
class AaScreenFit(
    val settings: REauSettings,
    /** The screen Android Auto shows now; the fit opens on its tab. */
    val screen: com.reau.app.aa.AaScreen = com.reau.app.aa.AaScreen.APPS,
    /** The first-time fit assistant: whether it opens by itself, and whom to tell when it was finished or skipped. */
    val assistant: FitAssistantAsk = FitAssistantAsk(),
    val change: (REauSettings) -> Unit,
)
