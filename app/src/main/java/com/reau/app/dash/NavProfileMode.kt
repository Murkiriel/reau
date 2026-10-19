package com.reau.app.dash

/**
 * With both the maneuver and the speed profile switches on and disagreeing, which one wins.
 * [OTIMISTA] (the default, what the two switches did before this choice existed) takes whichever says the fuller
 * profile; [PESSIMISTA] takes whichever says the reduced one. Alone, either switch is unaffected — there is only
 * one of them to listen to.
 */
enum class NavProfileMode { OTIMISTA, PESSIMISTA }
