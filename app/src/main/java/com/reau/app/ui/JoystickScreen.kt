package com.reau.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reau.app.R
import com.reau.app.aa.JoystickBindings
import com.reau.app.aa.JoystickBindings.Action
import com.reau.app.aa.JoystickBindings.Control
import com.reau.app.aa.JoystickBindings.Gesture
import com.reau.app.aa.JoystickBindings.Mode
import com.reau.app.aa.JoystickBindings.Slot
import com.reau.app.settings.REauSettings
import com.reau.app.ui.components.DPadMap
import com.reau.app.ui.components.GhostButton
import com.reau.app.ui.components.MsIcon
import com.reau.app.ui.components.PickSheet
import com.reau.app.ui.components.REauTopBar
import com.reau.app.ui.components.TabOption
import com.reau.app.ui.components.TabRail
import com.reau.app.ui.components.hairline
import com.reau.app.ui.theme.REauMono

/**
 * The joystick page: the dash's two modes in tabs, the map mode first; the cross with what each control does; a
 * control picked, its one tap opens the list of actions. One tap only (no hold; held choices saved before stay in the
 * settings, unused as always). In the map mode, up and the centre are the dash's own and locked
 * ([JoystickBindings.LOCKED]); down is the info ring's by default and can be given another action. Every pick is handed over at once ([onSettings]); a running projection takes it at the
 * next tap.
 */
// One screen composed in a run (the pages, the call tab, the picker); cutting it would only pass state around.
@Suppress("LongMethod")
@Composable
fun JoystickScreen(settings: REauSettings, onSettings: (REauSettings) -> Unit, onBack: () -> Unit) {
    val page = JoystickBindings.decode(settings.joystick)
    var mode by rememberSaveable { mutableStateOf(Mode.MAP) }
    var selected by rememberSaveable { mutableStateOf(Control.LEFT) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var onCallTab by rememberSaveable { mutableStateOf(false) }
    var moment by rememberSaveable { mutableStateOf(CallMoment.RINGING) }
    val slot = Slot(mode, selected, Gesture.ONCE)
    val locked = JoystickBindings.isLocked(slot)
    AppTheme {
        Column(Modifier.fillMaxSize()) {
            REauTopBar(stringResource(R.string.joystick_title), onBack, titleTag = "joystick_title")
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TabRail(
                    listOf(
                        TabOption(stringResource(R.string.joystick_mode_map), null, "joystick_mode_map"),
                        TabOption(stringResource(R.string.joystick_tab_media), null, "joystick_mode_media"),
                        TabOption(stringResource(R.string.joystick_mode_call), null, "joystick_mode_call"),
                    ),
                    if (onCallTab) 2 else if (mode == Mode.MAP) 0 else 1,
                    {
                        onCallTab = it == 2
                        if (it != 2) mode = if (it == 0) Mode.MAP else Mode.MEDIA
                    },
                )
                if (onCallTab) {
                    CallTab(moment, { moment = it }, selected) { selected = it }
                } else {
                    Text(
                        stringResource(R.string.joystick_intro),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        DPadMap(padLabels(page, mode), lockedOf(mode), selected, { selected = it })
                    }
                    SelectedCard(slot, page[slot], locked) { if (!locked) picking = true }
                    if (mode == Mode.MEDIA) MusicCardNotice(settings.dashMusic, page.mediaControls)
                    GhostButton(R.drawable.ic_ms_restart_alt, stringResource(R.string.joystick_reset), "reset_joystick", {
                        onSettings(settings.copy(joystick = REauSettings.DEFAULT_JOYSTICK))
                    })
                }
            }
        }
        if (picking && !locked) {
            val actions = Action.entries.filter { it.allowedAt(slot) }
            PickSheet(
                stringResource(R.string.joystick_pick_title, stringResource(CONTROLS.getValue(selected))),
                actions.map { stringResource(ACTIONS.getValue(it)) to "action_${it.name}" }, actions.indexOf(page[slot]),
                { picking = false; onSettings(settings.copy(joystick = page.with(slot, actions[it]).encode())) },
                { picking = false },
            )
        }
    }
}

/** Whether the dash shows the song, said under the media mode's cross: the card needs its switch and a music control on this page. */
@Composable
private fun MusicCardNotice(switchOn: Boolean, controls: Boolean) {
    val text = when {
        !switchOn -> R.string.joystick_music_switch_off
        controls -> R.string.joystick_music_on
        else -> R.string.joystick_music_off
    }
    Text(
        stringResource(text), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f), modifier = Modifier.padding(horizontal = 4.dp).testTag("joystick_music"),
    )
}

/** The short words on each button of the cross: its action, or why it is locked. */
@Composable
private fun padLabels(page: JoystickBindings, mode: Mode): Map<Control, String> = Control.entries.associateWith { control ->
    val slot = Slot(mode, control, Gesture.ONCE)
    when {
        !JoystickBindings.isLocked(slot) -> stringResource(ACTIONS.getValue(page[slot]))
        control == Control.CENTER -> stringResource(R.string.joystick_locked_center_short)
        else -> stringResource(R.string.joystick_locked_up_short)
    }
}

private fun lockedOf(mode: Mode): Set<Control> = Control.entries.filter { JoystickBindings.isLocked(Slot(mode, it, Gesture.ONCE)) }.toSet()

/**
 * The picked control: its name in Fraunces and the mode, then its one tap with the action ([onPick] opens the list); a
 * locked one says what the dash does with it, with no list.
 */
@Composable
private fun SelectedCard(slot: Slot, action: Action, locked: Boolean, onPick: () -> Unit) {
    val (what, note) = when {
        !locked -> stringResource(ACTIONS.getValue(action)) to stringResource(R.string.joystick_info_ring_note).takeIf { action == Action.INFO_RING }
        slot.control == Control.CENTER -> stringResource(R.string.joystick_locked_center) to stringResource(R.string.joystick_locked_center_note)
        else -> stringResource(R.string.joystick_locked_up) to stringResource(R.string.joystick_locked_up_note)
    }
    ControlCard(
        stringResource(CONTROLS.getValue(slot.control)),
        stringResource(if (slot.mode == Mode.MAP) R.string.joystick_mode_map else R.string.joystick_mode_media),
        stringResource(R.string.joystick_one_tap), what, note, locked, onPick,
    )
}

/** The card of a control: its name and where it is, then the caption with what it does (and why it cannot change). */
@Composable
private fun ControlCard(title: String, badge: String, caption: String, what: String, note: String?, locked: Boolean, onPick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    Column(
        Modifier.fillMaxWidth().clip(shape).background(colors.surfaceContainer).border(1.dp, hairline(0.18f), shape)
            .semantics(mergeDescendants = true) {}.testTag("joystick_selected"),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = colors.onSurface, modifier = Modifier.weight(1f))
            Text(
                badge.uppercase(),
                style = REauMono.copy(fontWeight = FontWeight.Medium), color = colors.onSurfaceVariant,
            )
        }
        HorizontalDivider(thickness = 1.dp, color = hairline(0.14f, 0.6f))
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !locked, role = Role.Button, onClick = onPick)
                .padding(horizontal = 16.dp, vertical = 14.dp).testTag("joystick_action_row"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The padlock says "Locked" to a screen reader (the cross's own padlocks do); the touch icon is decoration.
            MsIcon(
                if (locked) R.drawable.ic_ms_lock else R.drawable.ic_ms_touch_app, 22.dp, colors.onSurfaceVariant,
                description = if (locked) stringResource(R.string.cd_locked) else null,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(caption, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text(what, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurface.copy(alpha = 0.74f)) }
            }
            if (!locked) MsIcon(R.drawable.ic_ms_expand_more, 22.dp, colors.onSurfaceVariant)
        }
    }
}

/**
 * The "Chamada" tab, to read only: a rail for the moment of the call, the cross with what each control does then (all
 * of them fixed, drawn locked), and the card of the control picked, with no list to open. What it says is
 * [CallPage]'s, which JoystickMap's tests hold to.
 */
@Composable
private fun CallTab(moment: CallMoment, onMoment: (CallMoment) -> Unit, selected: Control, onSelect: (Control) -> Unit) {
    TabRail(
        listOf(
            TabOption(stringResource(R.string.joystick_call_ringing), null, "joystick_call_ringing"),
            TabOption(stringResource(R.string.joystick_call_in_call), null, "joystick_call_in_call"),
        ),
        moment.ordinal, { onMoment(CallMoment.entries[it]) },
    )
    Text(stringResource(R.string.joystick_call_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val labels = Control.entries.associateWith { stringResource(CallPage.effect(moment, it).short) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        DPadMap(labels, Control.entries.toSet(), selected, onSelect, tag = "call_dpad")
    }
    val effect = CallPage.effect(moment, selected)
    val momentName = stringResource(if (moment == CallMoment.RINGING) R.string.joystick_call_ringing else R.string.joystick_call_in_call)
    ControlCard(
        stringResource(CONTROLS.getValue(selected)), "${stringResource(R.string.joystick_mode_call)} · $momentName",
        stringResource(R.string.joystick_call_fixed), stringResource(effect.what), stringResource(effect.note), true, {},
    )
}

private val CONTROLS = mapOf(
    Control.LEFT to R.string.joystick_control_left,
    Control.RIGHT to R.string.joystick_control_right,
    Control.UP to R.string.joystick_control_up,
    Control.DOWN to R.string.joystick_control_down,
    Control.CENTER to R.string.joystick_control_center,
)

private val ACTIONS = mapOf(
    Action.NOTHING to R.string.joystick_action_nothing,
    Action.WHEEL_PREVIOUS to R.string.joystick_action_wheel_previous,
    Action.WHEEL_NEXT to R.string.joystick_action_wheel_next,
    Action.CONFIRM to R.string.joystick_action_confirm,
    Action.BACK to R.string.joystick_action_back,
    Action.HOME to R.string.joystick_action_home,
    Action.PLAY_PAUSE to R.string.joystick_action_play_pause,
    Action.NEXT_SONG to R.string.joystick_action_next_song,
    Action.PREVIOUS_SONG to R.string.joystick_action_previous_song,
    Action.ASSISTANT to R.string.joystick_action_assistant,
    Action.ZOOM_IN to R.string.joystick_action_zoom_in,
    Action.ZOOM_OUT to R.string.joystick_action_zoom_out,
    Action.INFO_RING to R.string.joystick_action_info_ring,
)
