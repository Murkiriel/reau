package com.reau.app.settings

/**
 * The factory fits, by the density REau asks of Android Auto.
 *
 * What Android Auto draws does not depend on the phone's own screen: REau always asks for the same 800×480 frame
 * ([com.reau.app.aa.AaVideo]) at the density of the settings ([REauSettings.density], 180 on a new install) and with the margins of
 * the settings (none on a new install), and those go out in the service discovery response. So the one number of the frame that can
 * differ between two riders is that density, and it is the key here; the phone's screen density is not one.
 *
 * There is one row, and it is measured: the fits of [REauSettings] at density [MEASURED_DENSITY], adjusted by eye on the owner's own
 * phone (one phone, one Android Auto, Waze and Google Maps as they were in October 2026). No other density has a row, because nobody
 * measured one: a guessed place for the crop would be a number with no proof, so [measured] answers null and [forDensity] hands the
 * measured row back. A new row goes in only with a picture of that density to measure it on. What still differs from phone to phone at
 * the same density (the version of Android Auto and of the navigation app, the side Android Auto puts its bar on, the language) has no
 * number here either: that is what the fit assistant on the "Tela do Android Auto" is for.
 */
object FitDefaults {
    /** The fits of one density: the apps' ("Outros"), the navigation apps' ("Navegação") and the app menu's own. */
    data class Fits(val apps: FitParams, val nav: FitParams, val menu: FitParams)

    /** The one density the factory fits were measured at: the density of a new install. */
    const val MEASURED_DENSITY = 180

    private val TABLE: Map<Int, Fits> = mapOf(
        MEASURED_DENSITY to Fits(REauSettings().appsFit, REauSettings.DEFAULT_NAV_FIT, REauSettings.DEFAULT_MENU_FIT),
    )

    /** The densities that have a measured row. */
    val densities: Set<Int> get() = TABLE.keys

    /** The fits measured at [density]; null when nobody measured that density. */
    fun measured(density: Int): Fits? = TABLE[density]

    /** The factory fits for [density]: its own measured row or, with none, the row of [MEASURED_DENSITY] (what every density got before this table). */
    fun forDensity(density: Int): Fits = measured(density) ?: TABLE.getValue(MEASURED_DENSITY)
}
