package com.reau.app.devtools

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.reau.app.R
import com.reau.app.data.RideDiagnostics
import com.reau.app.devtools.FaultInjection.Fault
import com.reau.app.service.DashLink
import com.reau.app.service.Phase
import com.reau.app.service.ProjectionNotification
import com.reau.app.service.ProjectionService
import com.reau.app.ui.components.ActionsRow
import com.reau.app.ui.components.RowAction

/**
 * Debug builds only: the rows at the end of Ajustes → Depuração that fire each warning of the app, to check its sound, its vibration and its
 * channel on the phone, and that arm the faults of [FaultInjection]. The release build has a [DebugTriggers] with no row (`src/release`), and
 * nothing here is in it. The texts are in English only: they are for whoever builds the app, and a release never shows them.
 */
object DebugTriggers {
    /** The temperature the "phone hot" notice is fired with: above the 44 degrees that sets it off by itself. */
    internal const val HOT_TEMP_C = 45

    /** The notice fired by each action of the first row; the log says which, so a check on the phone can be matched to it. */
    internal fun firedLine(what: String) = "debug trigger fired: $what (debug build only)"

    @Composable
    fun rows(): List<@Composable () -> Unit> {
        val context = LocalContext.current
        return listOf<@Composable () -> Unit>(
            {
                ActionsRow(
                    "Fire a warning", listOf(
                        RowAction("Dash stuck", "debug_fire_dash_stuck", R.drawable.ic_ms_construction) { fire(context, "dash stuck") },
                        RowAction("Phone hot", "debug_fire_phone_hot", R.drawable.ic_ms_construction) { fire(context, "phone hot") },
                        RowAction("Dash gone, stopped", "debug_fire_no_dash", R.drawable.ic_ms_construction) { fire(context, "no dash stop") },
                        RowAction("Alert channel", "debug_alert_channel", R.drawable.ic_ms_construction) { openAlertChannel(context) },
                    ),
                    sub = "Each posts the real notice. Check the sound, the vibration (screen off, in the pocket) and the channel; " +
                        "\"Alert channel\" opens its page in the system's settings.",
                    tag = "debug_fire",
                )
            },
            {
                ActionsRow(
                    "Inject a fault", listOf(
                        RowAction("Decoder stalls", "debug_fault_decoder", R.drawable.ic_ms_construction) { arm(Fault.DECODER_STALL) },
                        RowAction("Encoder mute", "debug_fault_encoder", R.drawable.ic_ms_construction) { arm(Fault.ENCODER_MUTE) },
                        RowAction("Focus cycle", "debug_fault_focus", R.drawable.ic_ms_construction) { arm(Fault.FOCUS_CYCLE) },
                    ),
                    sub = "With a projection running. Decoder: the stall watch restarts it in 3 s. " +
                        "Encoder: the experimental mute watch (switch on) rebuilds it in 3 s. Focus: Android Auto is asked for a keyframe.",
                    tag = "debug_fault",
                )
            },
            { ArrowTestRow() },
        )
    }

    /** Whether the arrow test may run: a projection running with the dash connected. */
    internal fun arrowTestAvailable(phase: Phase, dash: DashLink): Boolean = phase == Phase.RUNNING && dash == DashLink.READY

    /**
     * The arrow test: with the dash connected, Previous and Next send the arrows of the test sequence, one at a time, to the maneuver bubble; the byte
     * and its expected name are written here for whoever photographs the panel; Stop (or leaving this screen, or the dash going) gives the real arrow back.
     * Without the dash connected the row exists as a line of text and nothing to press.
     */
    @Composable
    private fun ArrowTestRow() {
        val projection by ProjectionService.projection.collectAsState()
        val sent by ArrowTrial.now.collectAsState()
        val available = arrowTestAvailable(projection.phase, projection.dash)
        DisposableEffect(Unit) { onDispose { ArrowTrial.stop() } }
        LaunchedEffect(available) { if (!available) ArrowTrial.stop() }
        if (!available) {
            ActionsRow("Arrow test", emptyList(), sub = "Only with the panel connected: start the projection and wait for the panel.", tag = "debug_arrow_off")
            return
        }
        ActionsRow(
            "Arrow test", listOf(
                RowAction("Previous", "debug_arrow_previous", R.drawable.ic_ms_construction) { ArrowTrial.previous() },
                RowAction("Next", "debug_arrow_next", R.drawable.ic_ms_construction) { ArrowTrial.next() },
                RowAction("Stop", "debug_arrow_stop", R.drawable.ic_ms_construction) { ArrowTrial.stop() },
            ),
            sub = sent?.let { "Sending ${ArrowTrial.label(it)}" }
                ?: "Off: the real arrow. Next sends the first arrow of the test sequence to the panel's maneuver bubble.",
            tag = "debug_arrow",
        )
    }

    private fun arm(fault: Fault) = FaultInjection.arm(fault)

    /** Posts the notice named [what]; false for a name that is none of them. */
    internal fun fire(context: Context, what: String): Boolean {
        when (what) {
            "dash stuck" -> ProjectionNotification.showDashStuck(context)
            "phone hot" -> ProjectionNotification.showPhoneHot(context, HOT_TEMP_C)
            "no dash stop" -> ProjectionNotification.showStoppedNoDash(context, 1)
            else -> return false
        }
        RideDiagnostics.log("debug", firedLine(what))
        return true
    }

    private fun openAlertChannel(context: Context) {
        ProjectionNotification.createAlertChannel(context)
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, ProjectionNotification.ALERT_CHANNEL_ID)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { RideDiagnostics.log("debug", "the alert channel's page did not open: ${it.javaClass.simpleName}") }
    }
}
