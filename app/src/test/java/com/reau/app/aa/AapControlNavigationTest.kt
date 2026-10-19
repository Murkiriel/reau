package com.reau.app.aa

import com.google.protobuf.Message
import com.reau.app.aa.proto.NavigationStatus
import com.reau.app.aa.proto.NavigationStatus.NavigationClusterStatus.NavigationStatusEnum
import com.reau.app.aa.proto.NavigationStatus.NavigationManeuver.NavigationType
import com.reau.app.aa.proto.NavigationStatus.NextTurnDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The navigation status channel as MOTO-HUB reads it (aa/AapNavigationStatus.kt, copied). MOTO-HUB has
 * no test for it: this is a characterization test, written AFTER the code, of what each message does to the guidance.
 */
class AapControlNavigationTest {
    private val seen = mutableListOf<NavGuidance>()
    private val control = AapControlNavigation { seen += it }

    private fun on(type: Int, proto: Message) = control.execute(AapMessage(Channel.ID_NAV, type, proto))

    private fun status(value: NavigationStatusEnum) =
        on(NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATUS_VALUE, NavigationStatus.NavigationClusterStatus.newBuilder().setStatus(value).buildPartial())

    private fun state(type: NavigationType, road: String = "", exit: Int? = null) {
        val maneuver = NavigationStatus.NavigationManeuver.newBuilder().setType(type)
        exit?.let { maneuver.setRoundaboutExitNumber(it) }
        val step = NavigationStatus.NavigationStep.newBuilder().setManeuver(maneuver.buildPartial())
        if (road.isNotEmpty()) step.setRoad(NavigationStatus.NavigationRoad.newBuilder().setName(road).buildPartial())
        on(NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE, NavigationStatus.NavigationState.newBuilder().addSteps(step.buildPartial()).buildPartial())
    }

    private fun position(toManeuverM: Int, toDestinationM: Int) {
        fun d(m: Int) = NavigationStatus.NavigationDistance.newBuilder().setMeters(m).buildPartial()
        on(
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE,
            NavigationStatus.NavigationCurrentPosition.newBuilder()
                .setStepDistance(NavigationStatus.NavigationStepDistance.newBuilder().setDistance(d(toManeuverM)).buildPartial())
                .addDestinationDistances(NavigationStatus.NavigationDestinationDistance.newBuilder().setDistance(d(toDestinationM)).buildPartial())
                .buildPartial(),
        )
    }

    @Test
    fun `the modern messages build up the guidance`() {
        status(NavigationStatusEnum.ACTIVE)
        state(NavigationType.TURN_NORMAL_LEFT, road = "Rua Augusta")
        position(250, 3_200)
        val g = seen.last()
        assertTrue(g.active)
        assertEquals(NavigationType.TURN_NORMAL_LEFT.number, g.maneuverType)
        assertEquals("Rua Augusta", g.road)
        assertEquals(250, g.distanceToManeuverMeters)
        assertEquals(3_200, g.distanceRemainingMeters)
    }

    /** The second step of the route is the maneuver after the next one (the dash's 05 03). */
    @Test
    fun `the second step is the maneuver after the next, and none when the route has one step`() {
        fun step(type: NavigationType, exit: Int? = null): NavigationStatus.NavigationStep {
            val maneuver = NavigationStatus.NavigationManeuver.newBuilder().setType(type)
            exit?.let { maneuver.setRoundaboutExitNumber(it) }
            return NavigationStatus.NavigationStep.newBuilder().setManeuver(maneuver.buildPartial()).buildPartial()
        }
        on(
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE,
            NavigationStatus.NavigationState.newBuilder().addSteps(step(NavigationType.TURN_NORMAL_LEFT))
                .addSteps(step(NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW, exit = 3)).buildPartial(),
        )
        assertEquals(NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW.number, seen.last().nextManeuverType)
        assertEquals(3, seen.last().nextRoundaboutExit)
        state(NavigationType.TURN_NORMAL_RIGHT)
        assertEquals("one step: no next maneuver", -1, seen.last().nextManeuverType)
    }

    @Test
    fun `a roundabout carries its exit, and a road is kept when the next step has none`() {
        state(NavigationType.TURN_NORMAL_RIGHT, road = "Av. Paulista")
        state(NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW, exit = 2)
        assertEquals(2, seen.last().roundaboutExitNumber)
        assertEquals("Av. Paulista", seen.last().road)
    }

    @Test
    fun `rerouting is said, and guidance ending drops the turn`() {
        state(NavigationType.TURN_NORMAL_LEFT)
        status(NavigationStatusEnum.REROUTING)
        assertTrue(seen.last().rerouting)
        status(NavigationStatusEnum.INACTIVE)
        assertEquals(NavGuidance.INACTIVE, seen.last())
    }

    @Test
    fun `the cluster stopping ends the guidance`() {
        state(NavigationType.STRAIGHT)
        on(NavigationStatus.MsgType.INSTRUMENT_CLUSTER_STOP_VALUE, NavigationStatus.NavigationStatusStop.newBuilder().buildPartial())
        assertEquals(NavGuidance.INACTIVE, seen.last())
    }

    @Test
    fun `the legacy turn detail is folded into the same maneuver numbers`() {
        fun legacy(event: NextTurnDetail.NextEvent, side: NextTurnDetail.Side) = on(
            NavigationStatus.MsgType.NEXTTURNDETAILS_VALUE,
            NextTurnDetail.newBuilder().setNextTurn(event).setSide(side).setRoad("Rua X").buildPartial(),
        )
        legacy(NextTurnDetail.NextEvent.TURN, NextTurnDetail.Side.LEFT)
        assertEquals(NavigationType.TURN_NORMAL_LEFT.number, seen.last().maneuverType)
        legacy(NextTurnDetail.NextEvent.SHARP_TURN, NextTurnDetail.Side.RIGHT)
        assertEquals(NavigationType.TURN_SHARP_RIGHT.number, seen.last().maneuverType)
        legacy(NextTurnDetail.NextEvent.ROUNDABOUT_ENTER_AND_EXIT, NextTurnDetail.Side.LEFT)
        assertEquals("driving on the left: clockwise", NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW.number, seen.last().maneuverType)
        // What Waze sends ("Nav: next turn event=ROUNDABOUT_ENTER_AND_EXIT side=RIGHT"): driving on the right, the
        // roundabout turns counterclockwise; it was always clockwise (the dash drew it backwards)
        legacy(NextTurnDetail.NextEvent.ROUNDABOUT_ENTER_AND_EXIT, NextTurnDetail.Side.RIGHT)
        assertEquals("driving on the right: counterclockwise", NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW.number, seen.last().maneuverType)
        legacy(NextTurnDetail.NextEvent.DESTINATION, NextTurnDetail.Side.RIGHT)
        assertEquals(NavigationType.DESTINATION.number, seen.last().maneuverType)
    }

    @Test
    fun `the legacy distance event gives the distance to the turn`() {
        on(
            NavigationStatus.MsgType.NEXTTURNDISTANCEANDTIME_VALUE,
            NavigationStatus.NextTurnDistanceEvent.newBuilder().setDistanceMeters(420).setTimeToTurnSeconds(30).buildPartial(),
        )
        assertEquals(420, seen.last().distanceToManeuverMeters)
        assertEquals(30, seen.last().timeToManeuverSeconds)
    }

    @Test
    fun `the same guidance twice is passed on once`() {
        state(NavigationType.STRAIGHT)
        state(NavigationType.STRAIGHT)
        assertEquals(1, seen.size)
    }

    @Test
    fun `a broken message costs that message only`() {
        // A field that says 127 bytes follow, then one byte: not a message.
        val body = byteArrayOf(0x0A, 0x7F, 0x01)
        val type = NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE
        control.execute(AapMessage(Channel.ID_NAV, 0x0B.toByte(), type, HEADER, body.size, ByteArray(HEADER) + body))
        state(NavigationType.STRAIGHT)
        assertEquals(NavigationType.STRAIGHT.number, seen.last().maneuverType)
    }

    @Test
    fun `the street names Android Auto sends never reach the log, only the maneuver`() {
        val lines = mutableListOf<String>()
        AaLog.sink = { lines += it }
        try {
            state(NavigationType.TURN_NORMAL_LEFT, road = "Rua Augusta")
            on(
                NavigationStatus.MsgType.NEXTTURNDETAILS_VALUE,
                NextTurnDetail.newBuilder().setRoad("Avenida Paulista").setNextTurn(NextTurnDetail.NextEvent.TURN).buildPartial(),
            )
        } finally {
            AaLog.sink = null
        }
        assertEquals("Avenida Paulista", seen.last().road) // the guidance keeps it, for the dash
        val nav = lines.filter { "Nav:" in it }
        assertEquals("both messages logged: $lines", 2, nav.size)
        assertTrue("no street in $nav", nav.none { "Augusta" in it || "Paulista" in it || "road=" in it })
        assertTrue("the maneuver stays: $nav", nav.any { "TURN_NORMAL_LEFT" in it })
    }

    /** A ride had a route and no speed; the log now says what the position carries, so the cause can be read. */
    @Test
    fun `the position's distances reach the log, once in a few seconds`() {
        val lines = mutableListOf<String>()
        AaLog.sink = { lines += it }
        try {
            position(250, 3_200)
            position(200, 3_150)
        } finally {
            AaLog.sink = null
        }
        val logged = lines.filter { "Nav: position" in it }
        assertEquals("one line for two messages in a row: $lines", 1, logged.size)
        assertTrue(logged.single(), "step=250m" in logged.single() && "destination=3200m" in logged.single())
    }

    @Test
    fun `the summary says none for what the position did not carry and no meters for a text only`() {
        val none = NavigationStatus.NavigationCurrentPosition.newBuilder().buildPartial()
        assertEquals("step=none time=none destination=none", AapControlNavigation.positionSummary(none))
        val text = NavigationStatus.NavigationDistance.newBuilder().setDisplayValue("1,2").buildPartial()
        val onlyText = NavigationStatus.NavigationCurrentPosition.newBuilder()
            .setStepDistance(NavigationStatus.NavigationStepDistance.newBuilder().setDistance(text).setTimeToStepSeconds(9).buildPartial())
            .buildPartial()
        assertEquals("step=(no meters) time=9s destination=none", AapControlNavigation.positionSummary(onlyText))
    }

    private companion object {
        /** Channel, flags, two length bytes and the two type bytes. */
        const val HEADER = 6
    }
}
