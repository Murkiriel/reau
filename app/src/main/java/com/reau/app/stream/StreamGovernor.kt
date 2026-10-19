package com.reau.app.stream

import com.reau.app.dash.DashConfig
import com.reau.app.dash.NavProfileMode
import com.reau.app.video.DashCompositor
import java.util.Locale

/**
 * The decisions of the video loop, with the clock and every measurement passed in, so they run in a plain JVM
 * test. It applies the stream rules ([DashLogic], [AutoFpsController], [AutoKeyframeController]) to
 * a source that is Android Auto instead of a map:
 *
 * - **Pace.** The still pace until the dash has confirmed the first frame of the stream; then a tick at the
 *   stream's rate. Which ticks carry a frame is the compositor's business: a new picture, or a repeat every idle
 *   interval.
 * - **Bitrate.** The full bitrate while pictures keep coming ("the map changed recently" there is "Android Auto
 *   sent a new picture recently" here), half of it [MOTION_HOLD_MS] after the last one. The full bitrate is the
 *   rider's [bitrateCeilingBps], raised as needed so the rate keeps [DashLogic.MIN_BITS_PER_FRAME]. In the REDUCED
 *   tier (profile 2) it is [reducedBitrateBps] at most, whatever the picture does: the pace and the bits come down
 *   together, and go back up together.
 * - **Keyframes.** Every [fixedKeyframeSec] seconds, or, with it at 0, as [AutoKeyframeController] decides.
 * - **Keyframes while still.** One every [DashLogic.IDLE_SYNC_EVERY_MS], when the encoder's own would be further
 *   apart than that.
 * - **The window.** Every [windowMs] one line with the stream's measurements, the bike's speed and
 *   Wi-Fi among it ([LinkWindow]), and the automatic controllers get to judge it. A change of the automatic rate or of
 *   the keyframe interval means a new encoder.
 * - **The navigation profiles** change the pace and the live bitrate, never the encoder: with a profile on,
 *   the encoder is always built for the full rate ([encoderFps]) and REDUCED just feeds it fewer frames, at the
 *   lower bitrate, set live, so a change of tier costs no rebuild. The last tier is held [NAV_HOLD_MS] when the data
 *   it came from goes missing.
 *
 * A window is judged only if the stream ran at its full rate for all of it AND the compositor had a frame for
 * (nearly) every tick: a source slower than the rate says nothing about what the phone or the dash can take.
 */
@Suppress("TooManyFunctions") // one small function per case; splitting them would only scatter the same thing
internal class StreamGovernor(
    plan: AutoFpsController.Plan,
    private val clockSlowPercent: Int = 0,
    private val windowMs: Long = WINDOW_MS,
    private val bitrateCeilingBps: Int = DashConfig.MAX_STREAM_BITRATE_BPS,
    private val fixedKeyframeSec: Int = DashConfig.KEYFRAME_INTERVAL_AUTO,
    /** The bitrate of profile 2 (REDUCED), set live with its pace; profile 1's is [bitrateCeilingBps]. */
    private val reducedBitrateBps: Int = DashConfig.PROFILE_REDUCED_BPS,
) {
    /** What happened since the previous poll ([dashAcks] is the dash's running total). */
    data class Poll(
        val nowMs: Long,
        val compositor: DashCompositor.Stats,
        val framesOut: Int,
        val bytesOut: Long,
        /** The keyframes of this poll: how many, the biggest one's bytes and RTP packets. */
        val keyframes: Int = 0,
        val keyBytesMax: Int = 0,
        val keyPacketsMax: Int = 0,
        val lateFrames: Int,
        val encodeNs: Long,
        val dashAcks: Long,
        val firstFrameConfirmed: Boolean,
        val thermalOk: Boolean = true,
        /** The bike's speed and Wi-Fi at this poll, for the window's line. */
        val link: LinkSample? = null,
        /** The navigation profiles' input: the distance to the next maneuver and the route's speed, read every poll. */
        val navSample: NavSample? = null,
    )

    /**
     * The navigation profiles' live input, read every poll like [Poll.link]: each data field is trusted only when its
     * own switch ([maneuverProfileEnabled]/[speedProfileEnabled]) is on; both data fields are null at exactly the
     * same time — no active route — since [NavSpeed] only has a reading when `distanceToManeuverMeters` does too.
     */
    data class NavSample(
        val maneuverProfileEnabled: Boolean,
        val speedProfileEnabled: Boolean,
        val distanceToManeuverMeters: Int?,
        val speedKmh: Double?,
        /** Only matters with both switches on and disagreeing — see [NavProfileMode]. */
        val mode: NavProfileMode = NavProfileMode.OTIMISTA,
    )

    sealed interface Action {
        data class SetPace(val pace: DashCompositor.Pace) : Action
        data class SetBitrate(val bps: Int) : Action
        data class RequestSync(val reason: String) : Action

        /** Replace the encoder: its rate hint and keyframe interval are fixed when it is prepared. */
        data class Rebuild(val fps: Int, val keyframeSec: Int, val bitrateBps: Int) : Action
        data class Log(val line: String) : Action

        /** What the last second measured, for the stats text on the dash's video (every second). */
        data class Stats(val sentFps: Double, val kbps: Double) : Action
    }

    /** One measurement window. */
    private class Window(val startMs: Long, val ackStart: Long) {
        var frames = 0
        var bytes = 0L
        var source = 0
        var drawn = 0
        var repeated = 0
        var composeNs = 0L
        var encodeNs = 0L
        var late = 0
        var keyframes = 0
        var keyBytesMax = 0
        var keyPacketsMax = 0
        var allFull = true
        var thermalOk = true
        val link = LinkWindow()

        fun add(p: Poll, full: Boolean) {
            frames += p.framesOut
            bytes += p.bytesOut
            source += p.compositor.sourceFrames
            drawn += p.compositor.drawn
            repeated += p.compositor.repeated
            composeNs += p.compositor.composeNs
            encodeNs += p.encodeNs
            late += p.lateFrames
            keyframes += p.keyframes
            keyBytesMax = maxOf(keyBytesMax, p.keyBytesMax)
            keyPacketsMax = maxOf(keyPacketsMax, p.keyPacketsMax)
            allFull = allFull && full
            thermalOk = thermalOk && p.thermalOk
            link.add(p.link)
        }
    }

    private val autoFps = AutoFpsController(plan.startFps, plan.ceiling)
    private val autoKeyframe = AutoKeyframeController()
    private val maneuverProfile = ManeuverProfile()
    private val speedProfile = SpeedProfile()
    private var navTier: QualityTier? = null

    // The last verdict a profile gave and when, held while its data is missing (NAV_HOLD_MS).
    private var heldTier: QualityTier? = null
    private var heldAtMs = 0L

    // When the tier last changed from one tier to the other (NAV_DWELL_MS keeps it that long); none yet at the start.
    private var tierChangedAtMs = Long.MIN_VALUE / 2

    /** A profile is switched on: the encoder is then built for the full rate and the tiers only change the pace. */
    private var navProfilesOn = false

    private var dashAckSeen = false
    private var builtFps = autoFps.currentFps

    /** The navigation profiles: the tier's fps/bitrate override [fps]/[activeBitrateBps], or null for autoFps. */
    val fps: Int get() = navTier?.let(::tierFps) ?: autoFps.currentFps
    val keyframeSec: Int get() = fixedKeyframeSec.takeIf { it > 0 } ?: autoKeyframe.currentSec

    /**
     * The rate the encoder is built for (its frame-rate hint, and so the bits each frame gets). With a navigation profile
     * on, always the full rate: REDUCED only feeds it fewer frames ([fps]), so a frame keeps its bits and a change of
     * tier needs no new encoder. Without one, the automatic rate, as it always was.
     */
    val encoderFps: Int get() = if (navProfilesOn) autoFps.ceiling else autoFps.currentFps

    /** The bitrate the encoder is built for, and the one profile 1 sets while frames keep coming: the rider's ceiling. */
    val activeBitrateBps: Int get() = DashLogic.effectiveActiveBitrate(bitrateCeilingBps, encoderFps)

    /** The most the live bitrate goes to now: [activeBitrateBps], or [reducedBitrateBps] in the REDUCED tier. */
    private val tierBitrateBps: Int
        get() = if (navTier == QualityTier.REDUCED) minOf(activeBitrateBps, reducedBitrateBps) else activeBitrateBps

    /**
     * FULL is [AutoFpsController.ceiling] — already the Settings' own ceiling, lowered by anything this session
     * learned the dash cannot take (AutoFpsController.plan) — never above what the dash has already shown it can
     * hold, and never whatever fps autoFps happens to be sitting at. REDUCED is half of it, snapped to an even
     * step — allowed down to [NAV_MIN_FPS] (2, REau's idle pace too,
     * `DashEncoder.kt`'s "4 fps active / 2 fps idle"), below [DashConfig.MIN_STREAM_FPS] on purpose: that floor is
     * about autoFps's own climb, not this deliberate, bounded reduction.
     */
    private fun tierFps(tier: QualityTier): Int = when (tier) {
        QualityTier.FULL -> autoFps.ceiling
        QualityTier.REDUCED -> snapNavFps(autoFps.ceiling / 2)
    }

    private fun snapNavFps(fps: Int): Int {
        val clamped = fps.coerceAtLeast(NAV_MIN_FPS)
        return clamped - (clamped - NAV_MIN_FPS) % DashConfig.STREAM_FPS_STEP
    }

    private var pace: DashCompositor.Pace? = null
    private var bitrateBps = 0
    private var lastPictureAtMs: Long? = null
    private var lastSyncAtMs = 0L
    private var window: Window? = null

    // The stats text's own second: updated every second, not every 5 s window.
    private var statsStartMs = -1L
    private var statsFrames = 0
    private var statsBytes = 0L
    private val syncReasons = LinkedHashMap<String, Int>()

    /** A stream to the dash begins: the still pace, the still bitrate, and a keyframe. */
    fun start(nowMs: Long): List<Action> {
        pace = null
        bitrateBps = 0
        lastPictureAtMs = null
        window = null
        statsStartMs = -1L
        builtFps = encoderFps
        return paceChange(firstFrameConfirmed = false) + bitrateChange(full = false) + sync("stream start", nowMs)
    }

    /** A keyframe was asked for by someone else (a stream restart): it counts in the window's line. */
    fun syncRequested(reason: String, nowMs: Long) {
        sync(reason, nowMs)
    }

    fun onPoll(p: Poll): List<Action> {
        if (p.compositor.drawn > 0) lastPictureAtMs = p.nowMs
        val moving = lastPictureAtMs?.let { p.nowMs - it < MOTION_HOLD_MS } ?: false
        val full = DashLogic.fullRate(moving, p.firstFrameConfirmed)
        val tierChange = updateNavOverride(p.navSample, p.nowMs)
        val actions = mutableListOf<Action>()
        tierChange?.let { actions += it }
        actions += paceChange(p.firstFrameConfirmed)
        actions += bitrateChange(full)
        // A tier change moves the pace only. The encoder is replaced only when what it is built for moves: a profile
        // switched on or off while running, or the plan's own rate.
        if (encoderFps != builtFps) {
            builtFps = encoderFps
            actions += Action.Rebuild(encoderFps, keyframeSec, bitrateBps)
        }
        actions += idleSync(full, p.nowMs)
        actions += account(p, full)
        actions += statsSecond(p)
        return actions
    }

    /**
     * The frames and bytes sent in the last second: one [Action.Stats] when a second has gone by since the previous one.
     * The first poll only starts counting (what it carries came before).
     */
    private fun statsSecond(p: Poll): List<Action> {
        if (statsStartMs < 0) {
            statsStartMs = p.nowMs
            statsFrames = 0
            statsBytes = 0L
            return emptyList()
        }
        statsFrames += p.framesOut
        statsBytes += p.bytesOut
        val elapsedMs = p.nowMs - statsStartMs
        if (elapsedMs < STATS_MS) return emptyList()
        val stats = Action.Stats(DashLogic.computeActualFps(statsFrames, elapsedMs), statsBytes * 8.0 / elapsedMs)
        statsStartMs = p.nowMs
        statsFrames = 0
        statsBytes = 0L
        return listOf(stats)
    }

    /**
     * The navigation profiles, read live every poll, nothing baked in at construction (same pattern as [Poll.link]). A
     * profile with no data this poll (the route's speed or distance missing for a moment) keeps its last verdict for
     * [NAV_HOLD_MS] instead of falling back to the automatic rate at once: that fall-back, and the climb back, caused
     * most of the encoder rebuilds measured in use. A log line when the tier changes (null: no override).
     */
    private fun updateNavOverride(sample: NavSample?, nowMs: Long): Action? {
        navProfilesOn = sample != null && (sample.maneuverProfileEnabled || sample.speedProfileEnabled)
        val before = navTier
        val verdict = combineNavTier(sample)
        if (verdict != null) {
            heldTier = verdict
            heldAtMs = nowMs
        } else if (!navProfilesOn || nowMs - heldAtMs >= NAV_HOLD_MS) {
            heldTier = null
        }
        navTier = afterDwell(before, verdict ?: heldTier, nowMs)
        if (navTier == before) return null
        val why = if (verdict == null) "no data for ${NAV_HOLD_MS / 1000} s" else "data"
        return Action.Log("nav profile: ${before ?: "none"} -> ${navTier ?: "none"} ($why), pace $fps fps, encoder $encoderFps fps")
    }

    /**
     * A change from one tier to the other waits until the tier has been kept [NAV_DWELL_MS]; the first verdict, a return from
     * no data and the fall to no data are not changes of that kind and are never delayed. A tier taken from no tier starts
     * the count too (without it, a FULL taken from no tier was seen turning REDUCED 6 s later).
     */
    private fun afterDwell(before: QualityTier?, wanted: QualityTier?, nowMs: Long): QualityTier? {
        if (before == null && wanted != null) tierChangedAtMs = nowMs
        return when {
            before == null || wanted == null || wanted == before -> wanted
            nowMs - tierChangedAtMs < NAV_DWELL_MS -> before
            else -> {
                tierChangedAtMs = nowMs
                wanted
            }
        }
    }

    /** No switch enabled (or no active route) is null — no override, the automatic rate stands. With
     *  one switch enabled and data, its own verdict stands. With both and disagreeing, [NavSample.mode] decides:
     *  OTIMISTA takes whichever says FULL, PESSIMISTA whichever says REDUCED. */
    private fun combineNavTier(sample: NavSample?): QualityTier? {
        if (sample == null) return null
        val maneuverTier = if (sample.maneuverProfileEnabled) maneuverProfile.onSample(sample.distanceToManeuverMeters) else null
        val speedTier = if (sample.speedProfileEnabled) speedProfile.onSample(sample.speedKmh) else null
        val tiers = listOfNotNull(maneuverTier, speedTier)
        val wins = if (sample.mode == NavProfileMode.OTIMISTA) QualityTier.FULL else QualityTier.REDUCED
        val loses = if (wins == QualityTier.FULL) QualityTier.REDUCED else QualityTier.FULL
        return when {
            tiers.isEmpty() -> null
            tiers.any { it == wins } -> wins
            else -> loses
        }
    }

    private fun paceChange(firstFrameConfirmed: Boolean): List<Action> {
        val (activeMs, idleMs) = DashLogic.frameIntervalsMsForFps(fps)
        val wanted = DashCompositor.Pace(if (firstFrameConfirmed) activeMs else idleMs, idleMs, clockSlowPercent)
        if (wanted == pace) return emptyList()
        pace = wanted
        return listOf(Action.SetPace(wanted))
    }

    private fun bitrateChange(full: Boolean): List<Action> {
        // Still, profile 1 halves its bitrate; profile 2 already is at the half, and stays there.
        val wanted = if (full) tierBitrateBps else minOf(tierBitrateBps, activeBitrateBps / 2)
        if (wanted == bitrateBps) return emptyList()
        bitrateBps = wanted
        return listOf(Action.SetBitrate(wanted))
    }

    /**
     * While the stream runs full the count does not run: the first request comes a whole interval after it stops. The
     * encoder places a keyframe every [encoderFps] × keyframeSec frames, so at a pace below its rate (REDUCED) they
     * would come further apart than the rider's interval: one is asked for at the interval itself.
     */
    private fun idleSync(full: Boolean, nowMs: Long): List<Action> {
        if (full) return paceSync(nowMs)
        val idleMs = DashLogic.frameIntervalsMsForFps(fps).second
        val due = DashLogic.idleSyncNeeded(encoderFps, keyframeSec, idleMs) && nowMs - lastSyncAtMs >= DashLogic.IDLE_SYNC_EVERY_MS
        return if (due) sync("idle", nowMs) else emptyList()
    }

    /** At the full rate the encoder's own keyframes do; below it, one at the rider's interval. */
    private fun paceSync(nowMs: Long): List<Action> {
        if (fps >= encoderFps) {
            lastSyncAtMs = nowMs
            return emptyList()
        }
        return if (nowMs - lastSyncAtMs >= keyframeSec * 1000L) sync("pace", nowMs) else emptyList()
    }

    private fun sync(reason: String, nowMs: Long): List<Action> {
        lastSyncAtMs = nowMs
        syncReasons[reason] = (syncReasons[reason] ?: 0) + 1
        return listOf(Action.RequestSync(reason))
    }

    /** Adds this poll to the window; the window opens with the first frames that come out, which are not counted. */
    private fun account(p: Poll, full: Boolean): List<Action> {
        val w = window
        if (w == null) {
            if (p.framesOut > 0) window = Window(p.nowMs, p.dashAcks)
            return emptyList()
        }
        w.add(p, full)
        val elapsedMs = p.nowMs - w.startMs
        return if (elapsedMs < windowMs) {
            emptyList()
        } else {
            window = Window(p.nowMs, p.dashAcks)
            closeWindow(w, elapsedMs, p)
        }
    }

    /**
     * `dash=…` for the window's line, once the dash has acknowledged a frame in this stream (on a real dash it
     * almost never did, and a column of `dash=0.0` says nothing); empty until then.
     */
    private fun dashPart(decodedFps: Double, acksInWindow: Long): String {
        if (acksInWindow > 0) dashAckSeen = true
        return if (dashAckSeen) String.format(Locale.US, " dash=%.1f", decodedFps) else ""
    }

    private fun closeWindow(w: Window, elapsedMs: Long, p: Poll): List<Action> {
        val target = fps
        val sentFps = DashLogic.computeActualFps(w.frames, elapsedMs)
        val composedFps = DashLogic.computeActualFps(w.drawn + w.repeated, elapsedMs)
        val decodedFps = DashLogic.computeActualFps((p.dashAcks - w.ackStart).toInt(), elapsedMs)
        val kbps = w.bytes * 8.0 / elapsedMs
        val judged = w.allFull && composedFps >= target * AutoFpsController.DELIVERY_RATIO
        val rate = autoFps.onWindow(
            AutoFpsController.Window(target, sentFps, decodedFps, kbps, allActive = judged, thermalOk = w.thermalOk),
            p.nowMs,
        )
        val auto = autoKeyframe.onWindow(
            AutoKeyframeController.Window(
                sentFps = sentFps,
                // Acks far below the sent rate do not track frames at all: then only the bitrate rule applies.
                decodedFps = decodedFps.takeIf { sentFps > 0 && it >= sentFps * AutoFpsController.ACK_TRACKING_MIN_RATIO },
                kbps = kbps, appliedKbps = bitrateBps / 1000.0, allActive = judged,
            ),
        )
        // A fixed interval is the rider's: the automatic one only counts, it never changes it.
        val keyframe = if (fixedKeyframeSec > 0) AutoKeyframeController.Decision(fixedKeyframeSec, false, "fixed") else auto
        val perFrame = { ns: Long, frames: Int -> if (frames > 0) ns / 1e6 / frames else 0.0 }
        val line = String.format(
            Locale.US,
            "video fps target=%d sent=%.1f (%dframes/%.1fs) composed=%.1f%s kbps=%.0f set=%dk frame=%dB " +
                "source=%d drawn=%d repeated=%d compose=%.1fms encode=%.1fms late=%d full=%s thermal=%s %s kf=%ds " +
                "idr=%d(max %dB/%dpkt) sync=%s auto=%dfps [%s]",
            target, sentFps, w.frames, elapsedMs / 1000.0, composedFps, dashPart(decodedFps, p.dashAcks - w.ackStart), kbps, bitrateBps / 1000,
            if (w.frames > 0) w.bytes / w.frames else 0L,
            w.source, w.drawn, w.repeated, perFrame(w.composeNs, w.drawn + w.repeated), perFrame(w.encodeNs, w.frames), w.late,
            judged, if (w.thermalOk) "OK" else "HOT", w.link.text(), keyframe.intervalSec,
            w.keyframes, w.keyBytesMax, w.keyPacketsMax, syncSummary(), rate.fps, rate.reason,
        )
        val actions = mutableListOf<Action>(Action.Log(line))
        if (rate.changed || keyframe.changed) {
            // With a navigation profile on, the encoder is built for the full rate whatever the automatic rate does.
            if (keyframe.changed || encoderFps != builtFps) {
                builtFps = encoderFps
                actions += Action.Rebuild(encoderFps, keyframeSec, bitrateBps)
            }
            actions += paceChange(p.firstFrameConfirmed)
        }
        return actions
    }

    /** "2[stream start:1,idle:1]", or "0"; the counts start again. */
    private fun syncSummary(): String {
        if (syncReasons.isEmpty()) return "0"
        val text = "${syncReasons.values.sum()}[${syncReasons.entries.joinToString(",") { "${it.key}:${it.value}" }}]"
        syncReasons.clear()
        return text
    }

    companion object {
        /** One line, and one judgement, every 5 s. */
        const val WINDOW_MS = 5_000L

        /** The stats text on the dash's video is made again every second. */
        const val STATS_MS = 1_000L

        /** How long after the last new picture the stream still counts as moving. */
        const val MOTION_HOLD_MS = 1_500L

        /** The navigation profiles' own floor for the REDUCED tier, a cruise rate the dash shows well, below
         *  [DashConfig.MIN_STREAM_FPS] on purpose: see [tierFps]. */
        const val NAV_MIN_FPS = 2

        /**
         * How long a profile keeps its last verdict once the data behind it is gone (7 s, after the
         * route's speed has been stale for [NavSpeed.STALE_MS], 3 s: 10 s in all).
         */
        const val NAV_HOLD_MS = 7_000L

        /**
         * The least a tier is kept once it was changed to, before the other may be taken. The speed the profile
         * reads is worked out from the distance to the next maneuver and was measured wobbling across its
         * 55-65 km/h band 12 times in 11 min (at worst every 3 s).
         */
        const val NAV_DWELL_MS = 10_000L
    }
}
