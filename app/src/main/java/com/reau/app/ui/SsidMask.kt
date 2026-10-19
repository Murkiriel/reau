package com.reau.app.ui

/**
 * The Wi-Fi name is masked, shown whole only when the user asks. The screens show the dash's Wi-Fi name as the
 * beginning every dash shares and its last two letters ("RE_••••78"), never its length; only the Wi-Fi screen's
 * "Mostrar" shows it whole.
 */
object SsidMask {
    private const val DOTS = "••••"
    private const val HEAD = 3
    private const val TAIL = 2

    fun of(ssid: String): String = when {
        ssid.isEmpty() -> ""
        ssid.length <= HEAD + TAIL -> ssid.take(1) + DOTS
        else -> ssid.take(HEAD) + DOTS + ssid.takeLast(TAIL)
    }
}
