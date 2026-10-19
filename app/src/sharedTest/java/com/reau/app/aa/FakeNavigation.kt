package com.reau.app.aa

import com.reau.app.aa.proto.NavigationStatus
import com.reau.app.aa.proto.NavigationStatus.NavigationManeuver.NavigationType

/**
 * Test helper: the navigation status messages a phone's navigation app sends on Android Auto's channel 10, as the fake
 * Android Auto puts them on the wire ([FakeAndroidAutoPeer.send]): (message type, body).
 */
object FakeNavigation {
    fun status(active: Boolean): Pair<Int, ByteArray> {
        val value = if (active) NavigationStatus.NavigationClusterStatus.NavigationStatusEnum.ACTIVE
        else NavigationStatus.NavigationClusterStatus.NavigationStatusEnum.INACTIVE
        return NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATUS_VALUE to
            NavigationStatus.NavigationClusterStatus.newBuilder().setStatus(value).buildPartial().toByteArray()
    }

    fun rerouting(): Pair<Int, ByteArray> =
        NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATUS_VALUE to
            NavigationStatus.NavigationClusterStatus.newBuilder()
                .setStatus(NavigationStatus.NavigationClusterStatus.NavigationStatusEnum.REROUTING).buildPartial().toByteArray()

    /** The next step: a maneuver (with its roundabout exit, if any) on a road. */
    fun state(type: NavigationType, road: String = "", exit: Int? = null): Pair<Int, ByteArray> {
        val maneuver = NavigationStatus.NavigationManeuver.newBuilder().setType(type)
        exit?.let { maneuver.setRoundaboutExitNumber(it) }
        val step = NavigationStatus.NavigationStep.newBuilder().setManeuver(maneuver.buildPartial())
        if (road.isNotEmpty()) step.setRoad(NavigationStatus.NavigationRoad.newBuilder().setName(road).buildPartial())
        return NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE to
            NavigationStatus.NavigationState.newBuilder().addSteps(step.buildPartial()).buildPartial().toByteArray()
    }

    /**
     * The old dialect Waze speaks (NextTurnDetail), as the ride logs show it ("Nav: next turn event=TURN
     * side=RIGHT"); [event] UNKNOWN with side UNSPECIFIED is what Waze sends on a stretch with no turn ahead.
     */
    fun nextTurn(
        event: NavigationStatus.NextTurnDetail.NextEvent,
        side: NavigationStatus.NextTurnDetail.Side = NavigationStatus.NextTurnDetail.Side.UNSPECIFIED,
    ): Pair<Int, ByteArray> =
        NavigationStatus.MsgType.NEXTTURNDETAILS_VALUE to
            NavigationStatus.NextTurnDetail.newBuilder().setNextTurn(event).setSide(side).buildPartial().toByteArray()

    /** Where the phone is: metres to the next maneuver and to the destination. */
    fun position(toManeuverM: Int, toDestinationM: Int): Pair<Int, ByteArray> {
        fun distance(m: Int) = NavigationStatus.NavigationDistance.newBuilder().setMeters(m).buildPartial()
        val step = NavigationStatus.NavigationStepDistance.newBuilder().setDistance(distance(toManeuverM)).buildPartial()
        val destination = NavigationStatus.NavigationDestinationDistance.newBuilder().setDistance(distance(toDestinationM)).buildPartial()
        return NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE to
            NavigationStatus.NavigationCurrentPosition.newBuilder().setStepDistance(step).addDestinationDistances(destination)
                .buildPartial().toByteArray()
    }
}
