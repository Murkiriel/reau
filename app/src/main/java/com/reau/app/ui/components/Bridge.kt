@file:Suppress("LongParameterList") // a component takes one parameter per thing it shows, each with its default (as Material 3's)

package com.reau.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.reau.app.R
import com.reau.app.ui.theme.LocalREauDark
import com.reau.app.ui.theme.LocalREauStatus
import com.reau.app.ui.theme.REauMono

/**
 * The bridge card of the main screen: the headline and its line, the two sides joined by the bridge line, and the
 * Ligar/Desligar button. [on] is a projection that is not stopped; [elapsed] its time (mm:ss).
 */
@Composable
fun BridgeCard(
    headline: String,
    sub: String,
    aa: BridgeSide,
    panel: BridgeSide,
    on: Boolean,
    elapsed: String?,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    /** Drawn over the corner of Android Auto's side (the server's icon). */
    aaOverlay: @Composable BoxScope.() -> Unit = {},
    /** The stop is still being carried out: the button says "Desligando…" and cannot be pressed. */
    stopping: Boolean = false,
) {
    val shape = MaterialTheme.shapes.extraLarge
    Column(
        modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).border(1.dp, hairline(0.22f), shape)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                headline, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center, modifier = Modifier.testTag("status"),
            )
            Text(
                sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.testTag("status_sub"),
            )
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                StatusSide(aa.label, aa.value, aa.kind, R.drawable.ic_ms_smartphone, Modifier.fillMaxSize(), aa.tag)
                aaOverlay()
            }
            BridgeLine(ComponentLogic.bridgeKind(aa.kind, panel.kind), Modifier.width(26.dp).padding(horizontal = 2.dp))
            StatusSide(panel.label, panel.value, panel.kind, R.drawable.ic_ms_two_wheeler, Modifier.weight(1f).fillMaxHeight(), panel.tag)
        }
        PrimaryPowerButton(on, elapsed, onToggle, stopping = stopping)
    }
}

/**
 * One side of the bridge (REau's own): the [device] in a Carvão circle, the [label] in capitals and the [value] with
 * the state's icon; the state's colour is on the icon and the border, always with the text.
 */
@Composable
fun StatusSide(label: String, value: String, kind: StatusKind, @DrawableRes device: Int, modifier: Modifier = Modifier, tag: String = "") {
    val dark = LocalREauDark.current
    val colors = MaterialTheme.colorScheme
    val status = LocalREauStatus.current
    val shape = RoundedCornerShape(18.dp)
    val border = when {
        kind == StatusKind.IDLE -> hairline(0.22f)
        dark -> kind.color().copy(alpha = 0.53f)
        else -> kind.color()
    }
    Column(
        modifier.clip(shape).background(colors.background).border(if (dark) 1.dp else 1.5.dp, border, shape)
            .padding(horizontal = 10.dp, vertical = 12.dp).semantics(mergeDescendants = true) {}.testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(46.dp).background(status.chrome, CircleShape), contentAlignment = Alignment.Center) {
            MsIcon(device, 26.dp, status.onChrome)
        }
        Text(
            label.uppercase(), color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp, letterSpacing = 0.16.em),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusIcon(kind, 18.dp)
            Text(
                value, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, lineHeight = 18.sp),
                color = if (kind == StatusKind.IDLE || !dark) colors.onSurface else kind.color(),
            )
        }
    }
}

/**
 * The bridge line between the sides, 3 dp: dashed Ouro suave at half (off), dashed orange sliding 10 dp every 0.5 s
 * (connecting), solid green (both ready), dashed red standing (a problem).
 */
@Composable
fun BridgeLine(kind: StatusKind, modifier: Modifier = Modifier) {
    val color = if (kind == StatusKind.IDLE) MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f) else kind.color()
    val shift = if (kind == StatusKind.CONNECTING && rememberAnimationsOn()) {
        rememberInfiniteTransition(label = "bridge")
            .animateFloat(0f, DASH_PERIOD_DP, infiniteRepeatable(tween(SLIDE_MS, easing = LinearEasing), RepeatMode.Restart), label = "bridge").value
    } else {
        0f
    }
    Canvas(modifier.fillMaxWidth().height(3.dp).testTag("bridge_line")) {
        val y = size.height / 2
        val dash = (DASH_PERIOD_DP / 2).dp.toPx()
        val effect = if (kind == StatusKind.OK) null else PathEffect.dashPathEffect(floatArrayOf(dash, dash), -shift.dp.toPx())
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = size.height, cap = StrokeCap.Butt, pathEffect = effect)
    }
}

/**
 * The main button, 72 dp (one golden button per screen at most): Ligar in Ouro; with the projection on, Desligar with
 * the time since it started ([elapsed], tag `elapsed`), outlined in the dark theme and Carvão in the light one. While the
 * stop is carried out ([stopping], the projection already off), the Ligar button is disabled and says "Desligando…", so
 * that a tap that could not start anything does not look like a dead button.
 */
@Composable
fun PrimaryPowerButton(on: Boolean, elapsed: String?, onClick: () -> Unit, modifier: Modifier = Modifier, stopping: Boolean = false) {
    val dark = LocalREauDark.current
    val colors = MaterialTheme.colorScheme
    val status = LocalREauStatus.current
    val shape = MaterialTheme.shapes.large
    val fill = modifier.fillMaxWidth().height(72.dp).testTag("toggle")
    val padding = PaddingValues(horizontal = 16.dp)
    if (!on) {
        Button(
            onClick, fill, enabled = !stopping, shape = shape, contentPadding = padding,
            colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
        ) {
            MsIcon(R.drawable.ic_ms_power_settings_new, 32.dp, LocalContentColor.current)
            Spacer(Modifier.width(12.dp))
            val label = if (stopping) R.string.action_stopping else R.string.action_start
            Text(stringResource(label), style =MaterialTheme.typography.labelLarge.copy(fontSize = 22.sp, lineHeight = 26.sp))
        }
        return
    }
    val content: @Composable RowScope.() -> Unit = {
        MsIcon(R.drawable.ic_ms_stop_circle, 30.dp, colors.primary)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.action_stop), style = MaterialTheme.typography.labelLarge.copy(fontSize = 20.sp, lineHeight = 24.sp))
        if (elapsed != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                elapsed, color = colors.secondary, modifier = Modifier.testTag("elapsed"),
                style = REauMono.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            )
        }
    }
    if (dark) {
        OutlinedButton(
            onClick, fill, shape = shape, contentPadding = padding, border = BorderStroke(1.5.dp, colors.primary),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface), content = content,
        )
    } else {
        Button(
            onClick, fill, shape = shape, contentPadding = padding,
            colors = ButtonDefaults.buttonColors(containerColor = status.chrome, contentColor = status.onChrome), content = content,
        )
    }
}

private const val DASH_PERIOD_DP = 10f
private const val SLIDE_MS = 500
