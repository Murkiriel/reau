package com.reau.app

/**
 * Which edition of REau this is. The Lite one (id com.reau.app.lite) has no accessibility service: the rider
 * switches Android Auto's head unit server on by hand, and Android Auto 17.3 or older is asked to dial in.
 */
object Edition {
    val lite: Boolean get() = BuildConfig.LITE

    /** Whether the accessibility service (and the settings that need it) exists in this edition. */
    val hasAccessibility: Boolean get() = !lite
}
