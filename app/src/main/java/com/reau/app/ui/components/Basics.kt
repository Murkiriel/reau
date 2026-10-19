package com.reau.app.ui.components

import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.reau.app.R
import com.reau.app.ui.theme.LocalREauDark
import com.reau.app.ui.theme.LocalREauStatus

/** A Material Symbols Rounded icon (res/drawable/ic_ms_*, inside the APK) at [size], in [tint]. */
@Composable
fun MsIcon(@DrawableRes icon: Int, size: Dp, tint: Color, modifier: Modifier = Modifier, description: String? = null) {
    Icon(painterResource(icon), description, modifier.size(size), tint = tint)
}

/** A status's colour: off takes the theme's idle colour (Ouro suave in the dark one). */
@Composable
fun StatusKind.color(): Color {
    val status = LocalREauStatus.current
    return when (this) {
        StatusKind.IDLE -> status.idle
        StatusKind.CONNECTING -> status.warning
        StatusKind.OK -> status.success
        StatusKind.ERROR -> status.error
    }
}

/** A status's icon: the colour never goes without it. */
@DrawableRes
fun StatusKind.icon(): Int = when (this) {
    StatusKind.IDLE -> R.drawable.ic_ms_power_settings_new
    StatusKind.CONNECTING -> R.drawable.ic_ms_sync
    StatusKind.OK -> R.drawable.ic_ms_check_circle
    StatusKind.ERROR -> R.drawable.ic_ms_error
}

/** Whether Android lets the screen move: "remove animations" (animator scale 0) stops the spinning and the sliding. */
@Composable
fun rememberAnimationsOn(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        ComponentLogic.animationsOn(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))
    }
}

/** A status's icon in its colour; connecting, it turns once every 1.4 s (unless animations are off). */
@Composable
fun StatusIcon(kind: StatusKind, size: Dp, modifier: Modifier = Modifier) {
    val angle = if (kind == StatusKind.CONNECTING && rememberAnimationsOn()) {
        rememberInfiniteTransition(label = "spin")
            .animateFloat(0f, FULL_TURN, infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing), RepeatMode.Restart), label = "spin").value
    } else {
        0f
    }
    MsIcon(kind.icon(), size, kind.color(), modifier.graphicsLayer { rotationZ = angle })
}

/** Ouro suave at [darkAlpha] in the dark theme; solid in the light one, where every thin border is solid. */
@Composable
fun hairline(darkAlpha: Float, lightAlpha: Float = 1f): Color =
    MaterialTheme.colorScheme.secondary.copy(alpha = if (LocalREauDark.current) darkAlpha else lightAlpha)

/** An accent icon on a surface: Ouro in the dark theme, Grafite in the light one (gold is never an icon on Marfim). */
@Composable
fun accentTint(): Color = if (LocalREauDark.current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface

/**
 * The brand mark's gold on a surface (the "au" and the crown): the Ouro of the main button, in both themes (1.7:1 on
 * Marfim, by design).
 */
@Composable
fun brandGold(): Color = MaterialTheme.colorScheme.primary

/** The "NA HORA" chip: the setting takes effect at once, with the projection on. */
@Composable
fun NowChip(modifier: Modifier = Modifier) {
    val dark = LocalREauDark.current
    val shape = RoundedCornerShape(4.dp)
    Text(
        stringResource(R.string.now_chip),
        style = MaterialTheme.typography.labelSmall,
        color = if (dark) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.border(1.dp, hairline(0.6f), shape).padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

private const val FULL_TURN = 360f
private const val SPIN_MS = 1400
