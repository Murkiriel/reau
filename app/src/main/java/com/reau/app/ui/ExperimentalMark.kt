package com.reau.app.ui

/**
 * Every setting that is "experimental" or "de teste" has its letters in red, to say it is not yet the app's standard
 * and may be removed. A row is marked by what its own title and line say: writing "Experimental" or "(teste)" in the
 * text is all it takes for a new experiment to turn red.
 */
object ExperimentalMark {
    private val WORDS = Regex("""\b(experimental|teste)\b""", RegexOption.IGNORE_CASE)

    /** Whether any of [texts] (a row's title, its line) says experimental or test. */
    fun marks(vararg texts: String?): Boolean = texts.any { it != null && WORDS.containsMatchIn(it) }
}
