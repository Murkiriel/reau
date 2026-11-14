package com.reau.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.reau.app.R
import com.reau.app.ui.components.MsIcon
import com.reau.app.ui.components.StatusKind
import com.reau.app.ui.components.color
import com.reau.app.ui.components.rememberAnimationsOn

/** The status colour of the network server's icon: green while it is up, quiet otherwise. */
internal fun serverKind(on: Boolean): StatusKind = if (on) StatusKind.OK else StatusKind.IDLE

/**
 * The server icon's opacity, as a function read in the icon's layer (the draw step): blinking while the network server is up (unless
 * Android's animations are off), dim when it is not. Read there, the blinking redraws the icon and recomposes nothing.
 */
@Composable
private fun rememberServerAlpha(on: Boolean): () -> Float = when {
    !on -> DIM_ALPHA
    !rememberAnimationsOn() -> FULL_ALPHA
    else -> {
        val pulse = infiniteRepeatable<Float>(tween(BLINK_MS), RepeatMode.Reverse)
        val blink = rememberInfiniteTransition(label = "server").animateFloat(1f, BLINK_LOW, pulse, label = "server")
        val read: () -> Float = { blink.value }
        read
    }
}

/** The server's icon, in its status colour and blinking while the network server is up; shown by the badge and by the server screen. */
@Composable
internal fun ServerGlyph(on: Boolean, size: Dp, modifier: Modifier = Modifier, description: String? = null) {
    val alpha = rememberServerAlpha(on)
    MsIcon(R.drawable.ic_ms_dns, size, serverKind(on).color(), modifier.graphicsLayer { this.alpha = alpha() }, description)
}

/**
 * The icon of the network server (the browser's, "Ver no navegador"), over the corner of Android Auto's side of the
 * bridge; its tap opens the server's screen. It blinks in green while the server is up on the network; off it is still
 * and dim. The colour never goes without the words (the description).
 */
@Composable
internal fun ServerBadge(on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(if (on) R.string.server_badge_on else R.string.server_badge_off)
    Box(
        modifier.size(BADGE_DP.dp).clickable(role = Role.Button, onClickLabel = description, onClick = onClick).testTag("server_badge"),
        contentAlignment = Alignment.Center,
    ) {
        ServerGlyph(on, ICON_DP.dp, description = description)
    }
}

private const val BADGE_DP = 40
private const val ICON_DP = 20
private const val BLINK_MS = 700
private const val BLINK_LOW = 0.25f
private const val DIM = 0.55f
private val DIM_ALPHA: () -> Float = { DIM }
private val FULL_ALPHA: () -> Float = { 1f }
