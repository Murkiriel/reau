// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// Navigation-status (instrument cluster) channel support, following the message flow of
// headunit-revived (AGPLv3): aap/AapNavigation.kt, trimmed to a data feed — no notifications.
//
// Copied into REau from MOTO-HUB: apps/android/app/src/main/java/io/motohub/android/aa/AapNavigationStatus.kt, commit
// 725a541. Changes: package; the snapshots go to the [onGuidance] function given to the class instead of MOTO-HUB's
// process-wide AaNavigationGuidance object (its Snapshot is NavGuidance.kt here); detekt suppressions (the file keeps
// its origin's name, the dispatcher its origin's length). The parsing of the messages and the legacy-turn table are
// unchanged but for one thing: the legacy roundabout follows the driving side (it was always clockwise).
// The two "Nav:" log lines no longer write the street name.
@file:Suppress("MatchingDeclarationName")

package com.reau.app.aa

import com.reau.app.aa.proto.NavigationStatus

/**
 * Parses the ID_NAV channel. Google Maps speaks the modern messages (NavigationState /
 * NavigationCurrentPosition); the legacy NextTurnDetail / NextTurnDistanceEvent pair is kept
 * because other AA nav apps still send it — headunit-revived observed both dialects in the
 * wild. Every change of the guidance goes to [onGuidance].
 */
internal class AapControlNavigation(private val onGuidance: (NavGuidance) -> Unit) : AapControl {

    private var current = NavGuidance.INACTIVE

    @Suppress("LongMethod", "CyclomaticComplexMethod") // one branch per message type, as in MOTO-HUB
    override fun execute(message: AapMessage): Int {
        when (message.type) {
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_START_VALUE -> {
                AaLog.i("Nav: instrument cluster start")
                publish(NavGuidance.INACTIVE)
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_STOP_VALUE -> {
                AaLog.i("Nav: instrument cluster stop")
                publish(NavGuidance.INACTIVE)
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATUS_VALUE -> runParsing("status") {
                val status = message.parse(NavigationStatus.NavigationClusterStatus.newBuilder()).buildPartial()
                val statusEnum = status.status
                AaLog.i("Nav: cluster status %s", statusEnum)
                val active = statusEnum == NavigationStatus.NavigationClusterStatus.NavigationStatusEnum.ACTIVE ||
                    statusEnum == NavigationStatus.NavigationClusterStatus.NavigationStatusEnum.REROUTING
                publish(
                    if (active) {
                        current.copy(
                            active = true,
                            rerouting = statusEnum ==
                                NavigationStatus.NavigationClusterStatus.NavigationStatusEnum.REROUTING
                        )
                    } else {
                        // Guidance ended: drop the last turn with it rather than freezing it.
                        NavGuidance.INACTIVE
                    }
                )
            }
            NavigationStatus.MsgType.NEXTTURNDETAILS_VALUE -> runParsing("turn detail") {
                val detail = message.parse(NavigationStatus.NextTurnDetail.newBuilder()).buildPartial()
                AaLog.i("Nav: next turn event=%s side=%s", detail.nextTurn, detail.side)
                publish(
                    current.copy(
                        active = true,
                        maneuverType = legacyTurnToManeuverType(detail),
                        roundaboutExitNumber = if (detail.hasTurnNumber()) detail.turnNumber else 0,
                        road = detail.road.orEmpty().ifBlank { current.road }
                    )
                )
            }
            NavigationStatus.MsgType.NEXTTURNDISTANCEANDTIME_VALUE -> runParsing("turn distance") {
                val event = message.parse(NavigationStatus.NextTurnDistanceEvent.newBuilder()).buildPartial()
                publish(
                    current.copy(
                        active = true,
                        distanceToManeuverMeters = if (event.hasDistanceMeters()) event.distanceMeters else -1,
                        timeToManeuverSeconds = if (event.hasTimeToTurnSeconds()) event.timeToTurnSeconds else -1
                    )
                )
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_STATE_VALUE -> runParsing("state") {
                val state = message.parse(NavigationStatus.NavigationState.newBuilder()).buildPartial()
                val step = state.stepsList.firstOrNull() ?: return@runParsing
                val maneuver = if (step.hasManeuver()) step.maneuver else null
                val after = state.stepsList.getOrNull(1)?.takeIf { it.hasManeuver() }?.maneuver
                val road = step.takeIf { it.hasRoad() }?.road?.name.orEmpty()
                    .ifBlank { step.takeIf { it.hasCue() }?.cue?.alternateTextList?.firstOrNull().orEmpty() }
                AaLog.i("Nav: state steps=%d maneuver=%s", state.stepsCount, maneuver?.type)
                publish(
                    current.copy(
                        active = true,
                        maneuverType = maneuver?.type?.number ?: current.maneuverType,
                        roundaboutExitNumber = maneuver
                            ?.takeIf { it.hasRoundaboutExitNumber() }?.roundaboutExitNumber ?: 0,
                        road = road.ifBlank { current.road },
                        nextManeuverType = after?.type?.number ?: -1,
                        nextRoundaboutExit = after?.takeIf { it.hasRoundaboutExitNumber() }?.roundaboutExitNumber ?: 0,
                    )
                )
            }
            NavigationStatus.MsgType.INSTRUMENT_CLUSTER_NAVIGATION_CURRENT_POSITION_VALUE -> runParsing("position") {
                val position = message.parse(NavigationStatus.NavigationCurrentPosition.newBuilder()).buildPartial()
                val stepDistance = position.takeIf { it.hasStepDistance() }?.stepDistance
                val destination = position.destinationDistancesList.firstOrNull()
                logPosition(position)
                publish(
                    current.copy(
                        active = true,
                        distanceToManeuverMeters = stepDistance
                            ?.takeIf { it.hasDistance() && it.distance.hasMeters() }
                            ?.distance?.meters ?: current.distanceToManeuverMeters,
                        timeToManeuverSeconds = stepDistance
                            ?.takeIf { it.hasTimeToStepSeconds() }
                            ?.timeToStepSeconds?.toInt() ?: current.timeToManeuverSeconds,
                        distanceRemainingMeters = destination
                            ?.takeIf { it.hasDistance() && it.distance.hasMeters() }
                            ?.distance?.meters ?: current.distanceRemainingMeters,
                        timeToArrivalSeconds = destination
                            ?.takeIf { it.hasTimeToArrivalSeconds() }
                            ?.timeToArrivalSeconds ?: current.timeToArrivalSeconds,
                        estimatedTimeAtArrival = destination
                            ?.takeIf { it.hasEstimatedTimeAtArrival() }
                            ?.estimatedTimeAtArrival ?: current.estimatedTimeAtArrival
                    )
                )
            }
            else -> AaLog.i("Nav: unhandled message type %d", message.type)
        }
        return 0
    }

    // The ride log says what the position messages carry, once in POSITION_LOG_MS: a ride had a route and no
    // speed in the whole log, and the speed is the distance to the maneuver going down.
    private var lastPositionLogMs = Long.MIN_VALUE / 2

    private fun logPosition(position: NavigationStatus.NavigationCurrentPosition) {
        val now = System.nanoTime() / 1_000_000L
        if (now - lastPositionLogMs < POSITION_LOG_MS) return
        lastPositionLogMs = now
        AaLog.i("Nav: position %s", positionSummary(position))
    }

    private fun publish(snapshot: NavGuidance) {
        if (snapshot == current) return
        current = snapshot
        onGuidance(snapshot)
    }

    // As in MOTO-HUB: a message that breaks the parser costs that message only, never the read loop.
    @Suppress("TooGenericExceptionCaught")
    private inline fun runParsing(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            AaLog.e("Nav: failed to parse $what: $e")
        }
    }

    /**
     * Folds the legacy (event, side) pair into the modern NavigationType vocabulary the
     * parcel speaks, so downstream consumers only ever deal with one enum.
     */
    @Suppress("CyclomaticComplexMethod") // a plain lookup, one branch per legacy event (as in MOTO-HUB)
    private fun legacyTurnToManeuverType(detail: NavigationStatus.NextTurnDetail): Int {
        val left = detail.side == NavigationStatus.NextTurnDetail.Side.LEFT
        fun pick(leftType: NavigationStatus.NavigationManeuver.NavigationType,
                 rightType: NavigationStatus.NavigationManeuver.NavigationType) =
            (if (left) leftType else rightType).number
        if (!detail.hasNextTurn()) return -1
        return when (detail.nextTurn) {
            NavigationStatus.NextTurnDetail.NextEvent.DEPART ->
                NavigationStatus.NavigationManeuver.NavigationType.DEPART.number
            NavigationStatus.NextTurnDetail.NextEvent.NAME_CHANGE ->
                NavigationStatus.NavigationManeuver.NavigationType.NAME_CHANGE.number
            NavigationStatus.NextTurnDetail.NextEvent.SLIGHT_TURN -> pick(
                NavigationStatus.NavigationManeuver.NavigationType.TURN_SLIGHT_LEFT,
                NavigationStatus.NavigationManeuver.NavigationType.TURN_SLIGHT_RIGHT
            )
            NavigationStatus.NextTurnDetail.NextEvent.TURN -> pick(
                NavigationStatus.NavigationManeuver.NavigationType.TURN_NORMAL_LEFT,
                NavigationStatus.NavigationManeuver.NavigationType.TURN_NORMAL_RIGHT
            )
            NavigationStatus.NextTurnDetail.NextEvent.SHARP_TURN -> pick(
                NavigationStatus.NavigationManeuver.NavigationType.TURN_SHARP_LEFT,
                NavigationStatus.NavigationManeuver.NavigationType.TURN_SHARP_RIGHT
            )
            NavigationStatus.NextTurnDetail.NextEvent.U_TURN -> pick(
                NavigationStatus.NavigationManeuver.NavigationType.U_TURN_LEFT,
                NavigationStatus.NavigationManeuver.NavigationType.U_TURN_RIGHT
            )
            NavigationStatus.NextTurnDetail.NextEvent.ON_RAMP -> pick(
                NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_NORMAL_LEFT,
                NavigationStatus.NavigationManeuver.NavigationType.ON_RAMP_NORMAL_RIGHT
            )
            NavigationStatus.NextTurnDetail.NextEvent.OFFRAMP -> pick(
                NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_NORMAL_LEFT,
                NavigationStatus.NavigationManeuver.NavigationType.OFF_RAMP_NORMAL_RIGHT
            )
            NavigationStatus.NextTurnDetail.NextEvent.FORK -> pick(
                NavigationStatus.NavigationManeuver.NavigationType.FORK_LEFT,
                NavigationStatus.NavigationManeuver.NavigationType.FORK_RIGHT
            )
            NavigationStatus.NextTurnDetail.NextEvent.MERGE -> pick(
                NavigationStatus.NavigationManeuver.NavigationType.MERGE_LEFT,
                NavigationStatus.NavigationManeuver.NavigationType.MERGE_RIGHT
            )
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_ENTER ->
                NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER.number
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_EXIT ->
                NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_EXIT.number
            // The side is the driving side; when driving on the right (what Waze sends for right-hand traffic),
            // the roundabout turns counterclockwise. Copied from MOTO-HUB it was always clockwise.
            NavigationStatus.NextTurnDetail.NextEvent.ROUNDABOUT_ENTER_AND_EXIT -> pick(
                NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CW,
                NavigationStatus.NavigationManeuver.NavigationType.ROUNDABOUT_ENTER_AND_EXIT_CCW,
            )
            NavigationStatus.NextTurnDetail.NextEvent.STRAIGHT ->
                NavigationStatus.NavigationManeuver.NavigationType.STRAIGHT.number
            NavigationStatus.NextTurnDetail.NextEvent.FERRY_BOAT ->
                NavigationStatus.NavigationManeuver.NavigationType.FERRY_BOAT.number
            NavigationStatus.NextTurnDetail.NextEvent.FERRY_TRAIN ->
                NavigationStatus.NavigationManeuver.NavigationType.FERRY_TRAIN.number
            NavigationStatus.NextTurnDetail.NextEvent.DESTINATION ->
                NavigationStatus.NavigationManeuver.NavigationType.DESTINATION.number
            else -> -1
        }
    }

    companion object {
        private const val POSITION_LOG_MS = 5_000L

        /** "step=250m time=12s destination=3200m"; "none" for what the message did not carry, "(no meters)" for a distance with only a display text. */
        internal fun positionSummary(position: NavigationStatus.NavigationCurrentPosition): String {
            fun meters(has: Boolean, distance: NavigationStatus.NavigationDistance): String = when {
                !has -> "none"
                distance.hasMeters() -> "${distance.meters}m"
                else -> "(no meters)"
            }
            val step = position.takeIf { it.hasStepDistance() }?.stepDistance
            val destination = position.destinationDistancesList.firstOrNull()
            val stepText = meters(step?.hasDistance() == true, step?.distance ?: NavigationStatus.NavigationDistance.getDefaultInstance())
            val destinationText = meters(destination?.hasDistance() == true, destination?.distance ?: NavigationStatus.NavigationDistance.getDefaultInstance())
            val time = step?.takeIf { it.hasTimeToStepSeconds() }?.let { "${it.timeToStepSeconds}s" } ?: "none"
            return "step=$stepText time=$time destination=$destinationText"
        }
    }
}
