package com.reau.app.dash.nav

/** What a route step asks the rider to do. [DashManeuverGlyphs.codeFor] turns it into the byte the dash's nav bubble draws. */
enum class ManeuverType { CONTINUE, TURN_LEFT, TURN_RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT,
    SHARP_LEFT, SHARP_RIGHT, UTURN, ROUNDABOUT, DEPART, ARRIVE,
    MERGE_LEFT, MERGE_RIGHT, KEEP_LEFT, KEEP_RIGHT, ARRIVE_LEFT, ARRIVE_RIGHT }
