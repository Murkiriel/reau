package com.reau.app.service

/**
 * What a change of the dash side counts for in the ride summary. Only the dash that was connected ([DashLink.READY])
 * and is gone is a drop. A try that ended without ever getting the dash ([DashLink.CONNECTING] to
 * [DashLink.DROPPED]) is a failed try: while the bike is away every try the machine makes ends that way, and counting
 * them as drops showed many more drops than the ride had.
 */
object DashDrops {
    enum class Kind { DROP, FAILED_TRY, NONE }

    fun of(before: DashLink, after: DashLink): Kind = when {
        after != DashLink.DROPPED -> Kind.NONE
        before == DashLink.READY -> Kind.DROP
        before == DashLink.CONNECTING -> Kind.FAILED_TRY
        else -> Kind.NONE
    }
}
