// One small function per case (splitting them would only scatter the same thing), and a component takes one parameter per
// thing it shows, each with its default (as Material 3's).
@file:Suppress("TooManyFunctions", "LongParameterList")

package com.reau.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reau.app.Edition
import com.reau.app.R
import com.reau.app.aa.AaScreen
import com.reau.app.aa.AaVideo
import com.reau.app.settings.Fit
import com.reau.app.settings.FitParams
import com.reau.app.settings.REauSettings
import com.reau.app.settings.withMenuFitOwn
import com.reau.app.settings.withMenuFitReset
import com.reau.app.ui.components.FineStepper
import com.reau.app.ui.components.GhostButton
import com.reau.app.ui.components.GroupLabel
import com.reau.app.ui.components.Info
import com.reau.app.ui.components.InfoButton
import com.reau.app.ui.components.MsIcon
import com.reau.app.ui.components.StatusKind
import com.reau.app.ui.components.SwitchRow
import com.reau.app.ui.components.TabOption
import com.reau.app.ui.components.TabRail
import com.reau.app.ui.components.color
import com.reau.app.ui.components.hairline
import com.reau.app.ui.theme.LocalREauDark
import com.reau.app.ui.theme.REauMono
import com.reau.app.video.FrameFit
import com.reau.app.video.RectPx
import com.reau.app.video.StatsOverlay

/**
 * The fit on the dome ("Encaixe na cúpula"): on the "Tela do Android Auto", under the picture and the mask, so each
 * step is seen as it is taken. The density and the margins asked of Android Auto (on the same screen where Android Auto
 * is touched) are one for the menu and the apps; then the two tabs, the mode, the fine and coarse counters, what is
 * wrong with the fit, the rectangles it gives and the way back to the default.
 */
@Composable
internal fun FitPanel(s: REauSettings, change: (REauSettings) -> Unit, screen: AaScreen = AaScreen.APPS) {
    // Which screen Android Auto shows is read by the accessibility service: without it (the Lite edition) there is
    // only the apps' fit, and no tab for the menu's own.
    var menuTab by remember(screen) { mutableStateOf(Edition.hasAccessibility && screen == AaScreen.MENU) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
        Column(verticalArrangement = Arrangement.spacedBy(LABEL_GAP)) {
            GroupLabel(null, stringResource(R.string.section_fit))
            Column(verticalArrangement = Arrangement.spacedBy(BLOCK_GAP)) { FitBlocks(menuTab, { menuTab = it }, s, change) }
        }
        // The stats text and its switch (here, not in the debug settings).
        StatsSection(s, change)
    }
}

/** The fit's blocks (the density, the margins, the tabs, the mode and its counters, the way back), [BLOCK_GAP] apart. */
@Composable
private fun FitBlocks(menuTab: Boolean, onMenuTab: (Boolean) -> Unit, s: REauSettings, change: (REauSettings) -> Unit) {
    Card {
        FineStepper(
            stringResource(R.string.density_title), stringResource(R.string.density_hint), s.density, REauSettings.DENSITY,
            { change(s.copy(density = it)) }, "density",
        )
        Text(stringResource(R.string.density_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Margins(s, change)
    if (Edition.hasAccessibility) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TabRail(
                listOf(
                    TabOption(stringResource(R.string.tab_apps), stringResource(R.string.tab_apps_sub), "fit_tab_apps"),
                    TabOption(stringResource(R.string.tab_menu), stringResource(R.string.tab_menu_sub), "fit_tab_menu"),
                ),
                if (menuTab) 1 else 0, { onMenuTab(it == 1) },
            )
        }
    }
    val menu = s.menuFit
    if (menuTab) MenuOwn(s, change)
    when {
        !menuTab -> FitSteps(s.appsFit, s.mapping, s) { change(s.withAppsFit(it)) }
        menu == null -> Text(
            stringResource(R.string.menu_fit_same), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("menu_fit_same"),
        )
        else -> FitSteps(menu, s.menuMapping, s) { change(s.copy(menuFit = it)) }
    }
    GhostButton(R.drawable.ic_ms_restart_alt, stringResource(R.string.fit_reset), "fit_reset", {
        val defaults = REauSettings()
        change(if (menuTab) s.withMenuFitReset() else s.withAppsFit(defaults.appsFit))
    })
}

/**
 * The stats text on the dash's video: its switch and, with it on, where the text goes and how big it is (the picture above
 * shows it where it lands). One card: the switch row brings its own padding, the steps under it are padded and spaced as
 * the cards of the fit above (it was cramped otherwise).
 */
@Composable
private fun StatsSection(s: REauSettings, change: (REauSettings) -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Column(verticalArrangement = Arrangement.spacedBy(LABEL_GAP)) {
        GroupLabel(null, stringResource(R.string.section_stats))
        Column(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer)) {
            // No line under the title ("Texto vermelho com borda preta no painel"); the info says it.
            SwitchRow(
                stringResource(R.string.stats_title), s.statsOverlay, { change(s.copy(statsOverlay = it)) }, "stats_overlay",
                info = Info(stringResource(R.string.stats_title), stringResource(R.string.stats_overlay)),
            )
            if (s.statsOverlay) {
                HorizontalDivider(thickness = 1.dp, color = hairline(0.14f, 0.6f))
                StatsSteps(s, change)
            }
        }
    }
}

@Composable
private fun StatsSteps(s: REauSettings, change: (REauSettings) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(CARD_PADDING).testTag("stats_steps"), verticalArrangement = Arrangement.spacedBy(CARD_GAP)) {
        FineStepper(
            stringResource(R.string.stats_x_title), stringResource(R.string.stats_x_hint), s.statsX, StatsOverlay.X,
            { change(s.copy(statsX = it)) }, "stats_x", format = { "$it px" },
        )
        HorizontalDivider(thickness = 1.dp, color = hairline(0.14f, 0.6f))
        FineStepper(
            stringResource(R.string.stats_y_title), stringResource(R.string.stats_y_hint), s.statsY, StatsOverlay.Y,
            { change(s.copy(statsY = it)) }, "stats_y", format = { "$it px" },
        )
        HorizontalDivider(thickness = 1.dp, color = hairline(0.14f, 0.6f))
        FineStepper(
            stringResource(R.string.stats_size_title), stringResource(R.string.stats_size_hint), s.statsSize, StatsOverlay.SIZE,
            { change(s.copy(statsSize = it)) }, "stats_size", format = { "$it px" },
        )
    }
}

/** The margins asked of Android Auto: it draws only in the middle of its 800×480 frame, black around (taken at once). */
@Composable
private fun Margins(s: REauSettings, change: (REauSettings) -> Unit) {
    val marginsInfo = Info(stringResource(R.string.margins_title), stringResource(R.string.margins_info))
    Card {
        FineStepper(
            stringResource(R.string.label_margin_w), stringResource(R.string.margin_sub), s.marginW, 0..AaVideo.WIDTH,
            { change(s.copy(marginW = it)) }, "margin_w", format = { "$it px" }, info = marginsInfo,
        )
        HorizontalDivider(thickness = 1.dp, color = hairline(0.14f, 0.6f))
        FineStepper(
            stringResource(R.string.label_margin_h), stringResource(R.string.margin_sub), s.marginH, 0..AaVideo.HEIGHT,
            { change(s.copy(marginH = it)) }, "margin_h", format = { "$it px" }, info = marginsInfo,
        )
    }
}

/** The app menu's own fit, on as it was kept (the default until one was set; off, the menu uses the apps' fit). */
@Composable
private fun MenuOwn(s: REauSettings, change: (REauSettings) -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Column(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer)) {
        SwitchRow(
            stringResource(R.string.menu_own_title), s.menuFit != null, { on -> change(s.withMenuFitOwn(on)) }, "menu_fit_own",
            sub = stringResource(if (s.menuFit != null) R.string.menu_own_sub_on else R.string.menu_own_sub_off),
            info = Info(stringResource(R.string.menu_own_title), stringResource(R.string.menu_fit_own)),
        )
    }
}

/** One fit ([p]): its mode, its counters, what is wrong with it and the rectangles of [mapping]; each step to [set]. */
@Composable
private fun FitSteps(p: FitParams, mapping: FrameFit.Mapping, s: REauSettings, set: (FitParams) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.mode_label), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
            InfoButton(Info(stringResource(R.string.mode_label), stringResource(R.string.fit_mode_info)), "fit_mode_info")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((fit, label, icon) in MODES) {
                ModeCard(stringResource(label), icon, p.fit == fit, "fit_${fit.name}", Modifier.weight(1f)) { set(p.copy(fit = fit)) }
            }
        }
    }
    Card {
        if (p.fit == Fit.CROP) {
            CropSteps(p, s.content, set)
        } else {
            // Hidden on the crop, which already fills the dash's frame (FrameFit.moved has nowhere to take it)
            FineStepper(
                stringResource(R.string.move_x_title), stringResource(R.string.move_x_hint), p.moveX, REauSettings.MOVE,
                { set(p.copy(moveX = it)) }, "move_x", format = { "$it px" },
            )
            HorizontalDivider(thickness = 1.dp, color = hairline(0.14f, 0.6f))
            FineStepper(
                stringResource(R.string.move_y_title), stringResource(R.string.move_y_hint), p.moveY, REauSettings.MOVE,
                { set(p.copy(moveY = it)) }, "move_y", format = { "$it px" },
            )
        }
    }
    val problems = FrameFit.problems(mapping, AaVideo.WIDTH, AaVideo.HEIGHT)
    if (problems.isNotEmpty()) BadFit(problems.map { stringResource(ScreenText.problem(it)) }.joinToString("; "))
    Technical(mapping)
}

/**
 * The crop's size and place, in pixels: the place is the window's top left pixel in Android Auto's picture, past the
 * edges up to FrameFit.cropRange; a new size keeps the window's centre where it was.
 */
@Composable
private fun CropSteps(p: FitParams, content: RectPx, set: (FitParams) -> Unit) {
    val (w, h) = FrameFit.cropSize(content, p.cropPercent)
    val x = p.cropX ?: ((content.w - w) / 2)
    val y = p.cropY ?: ((content.h - h) / 2)
    val (rangeX, rangeY) = FrameFit.cropRange(content, p.cropPercent)
    fun sized(percent: Int): FitParams {
        val (newW, newH) = FrameFit.cropSize(content, percent)
        return p.copy(cropPercent = percent, cropX = p.cropX?.let { it + (w - newW) / 2 }, cropY = p.cropY?.let { it + (h - newH) / 2 })
    }
    FineStepper(
        stringResource(R.string.label_crop), stringResource(R.string.crop_hint), p.cropPercent, REauSettings.CROP,
        { set(sized(it)) }, "crop", format = { "$it%" },
    )
    HorizontalDivider(thickness = 1.dp, color = hairline(0.14f, 0.6f))
    // Which part of Android Auto's frame the crop takes; with the projection on it applies at once.
    FineStepper(
        stringResource(R.string.crop_x_title), stringResource(R.string.crop_x_hint), x, rangeX, { set(p.copy(cropX = it)) }, "crop_center_x",
        format = { "$it px" },
    )
    HorizontalDivider(thickness = 1.dp, color = hairline(0.14f, 0.6f))
    FineStepper(
        stringResource(R.string.crop_y_title), stringResource(R.string.crop_y_hint), y, rangeY, { set(p.copy(cropY = it)) }, "crop_center_y",
        format = { "$it px" },
    )
}

/** One of the three modes: a card of 64 dp with its icon; chosen, Ouro border on primaryContainer. */
@Composable
private fun ModeCard(label: String, icon: Int, chosen: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
    val dark = LocalREauDark.current
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    val ink = if (chosen && dark) colors.primary else colors.onSurface
    Column(
        modifier.heightIn(min = 64.dp).clip(shape).background(if (chosen) colors.primaryContainer else Color.Transparent)
            .border(if (chosen && !dark) 1.5.dp else 1.dp, if (chosen) colors.primary else hairline(0.3f), shape)
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onClick).padding(6.dp).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        MsIcon(icon, 22.dp, ink)
        Text(label, color = ink, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium))
    }
}

/** A fit that does not work, said in red with its reasons. */
@Composable
private fun BadFit(why: String) {
    val error = StatusKind.ERROR.color()
    val shape = MaterialTheme.shapes.medium
    Row(
        Modifier.fillMaxWidth().clip(shape).border(1.dp, error, shape).padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {}.testTag("fit_problem"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MsIcon(R.drawable.ic_ms_error, 20.dp, error)
        Text(stringResource(R.string.fit_problem, why), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The rectangles the fit gives, in mono: what is taken of Android Auto's frame and where it goes on the dash. */
@Composable
private fun Technical(mapping: FrameFit.Mapping) {
    val shape = MaterialTheme.shapes.small
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, hairline(0.35f), shape).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            stringResource(R.string.technical_label).uppercase(), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.rects, mapping.source.label(), mapping.dest.label()), style = REauMono,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag("rects"),
        )
    }
}

/** A Carvão card of 16 dp radius holding counters. */
@Composable
private fun Card(content: @Composable () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Column(
        Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).padding(CARD_PADDING),
        verticalArrangement = Arrangement.spacedBy(CARD_GAP),
    ) { content() }
}

// The spacing of the whole screen: sections 24 dp apart, a label 8 dp over its content (as the settings' groups), the
// blocks of a section 14 dp apart, and inside a card the same padding as a settings row and 12 dp between its steps.
private val SECTION_GAP = 24.dp
internal val LABEL_GAP = 8.dp
private val BLOCK_GAP = 14.dp
internal val CARD_GAP = 12.dp
internal val CARD_PADDING = PaddingValues(horizontal = 16.dp, vertical = 14.dp)

private val MODES = listOf(
    Triple(Fit.FILL_DOME, R.string.fit_fill_dome, R.drawable.ic_ms_fullscreen),
    Triple(Fit.WHOLE_VISIBLE, R.string.fit_whole_short, R.drawable.ic_ms_fit_screen),
    Triple(Fit.CROP, R.string.fit_crop, R.drawable.ic_ms_crop),
)

private fun RectPx.label() = "$x,$y $w×$h"
