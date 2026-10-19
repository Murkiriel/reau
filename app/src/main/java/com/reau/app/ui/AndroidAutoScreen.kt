package com.reau.app.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.reau.app.R
import com.reau.app.ui.components.MsIcon
import com.reau.app.ui.components.PanelMaskOverlay
import com.reau.app.ui.components.REauTopBar
import com.reau.app.ui.components.StatsOverlayPreview
import com.reau.app.ui.components.hairline
import com.reau.app.ui.theme.Grafite
import com.reau.app.video.PhonePreview
import com.reau.app.video.StatsOverlay

/**
 * Android Auto running in the background, on the phone's own screen: the compositor draws Android Auto's whole
 * picture on this screen's surface while it is open ([PhonePreview]). View only, unless the rider switched on touching
 * it ([touch]): then a finger on the picture goes to Android Auto (PhoneTouch). The top bar with the mask's chip and
 * the picture stay; only the fit ([fit]) scrolls.
 */
@Composable
fun AndroidAutoScreen(
    onBack: () -> Unit,
    touch: Boolean = false,
    mask: AaScreenMask? = null,
    fit: AaScreenFit? = null,
    stats: StatsOverlay? = null,
) {
    BackHandler(onBack = onBack)
    DisposableEffect(Unit) { onDispose { PhonePreview.hide() } }
    AppTheme {
        Column(Modifier.fillMaxSize()) {
            REauTopBar(
                stringResource(R.string.aa_screen_title), onBack, titleTag = "aa_screen_title", backTag = "aa_screen_back",
                titleStyle = MaterialTheme.typography.titleLarge,
            ) { mask?.let { MaskChip(it) } }
            // With the fit here, the picture takes just its own shape and the fit scrolls under it
            val picture = if (fit != null) Modifier.fillMaxWidth().aspectRatio(PICTURE_SHAPE) else Modifier.fillMaxWidth().weight(1f)
            Box(Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp).then(picture).clip(RoundedCornerShape(6.dp)).background(Grafite)) {
                AndroidView(
                    factory = { context ->
                        PictureSurface(context).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) = Unit
                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                                    PhonePreview.show(holder.surface, width, height)
                                override fun surfaceDestroyed(holder: SurfaceHolder) = PhonePreview.hide()
                            })
                        }
                    },
                    update = { view -> view.setOnTouchListener(if (touch) FingerToAndroidAuto else null) },
                    modifier = Modifier.fillMaxSize().testTag("aa_screen_picture"),
                )
                // Over the picture, drawing only: the finger still reaches the picture under it.
                if (mask?.on == true) PanelMaskOverlay(mask, Modifier.matchParentSize().testTag("aa_screen_mask"))
                // The stats text where it lands on the dash's frame, with the switch on; a sample of its numbers.
                if (mask != null && stats?.on == true) StatsOverlayPreview(mask, stats, Modifier.matchParentSize().testTag("aa_screen_stats"))
            }
            TouchLine(touch)
            if (fit != null) {
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 24.dp),
                ) { FitPanel(fit.settings, fit.change, fit.screen) }
            }
        }
    }
}

/** What a finger on the picture does, under it, with a thin line below. */
@Composable
private fun TouchLine(touch: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MsIcon(R.drawable.ic_ms_touch_app, 18.dp, MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(if (touch) R.string.aa_screen_touch_short else R.string.aa_screen_view_only),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(thickness = 1.dp, color = hairline(0.22f))
}

/**
 * The dash's mask as a chip in the top bar: on, an Ouro border on a faint gold with the open eye. The top bar is
 * Grafite or the Carvão chrome, dark in both themes, so the gold reads there. Off, it takes the bar's own ink
 * ([LocalContentColor], Marfim on the light theme's chrome): the theme's text colour is dark there and was almost
 * invisible on the dark bar.
 */
@Composable
private fun MaskChip(mask: AaScreenMask) {
    val colors = MaterialTheme.colorScheme
    val barInk = LocalContentColor.current
    val shape = RoundedCornerShape(20.dp)
    val what = stringResource(R.string.aa_screen_mask)
    Row(
        Modifier.heightIn(min = 40.dp).clip(shape).background(if (mask.on) colors.primary.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.dp, if (mask.on) colors.primary else barInk.copy(alpha = 0.55f), shape)
            .toggleable(value = mask.on, role = Role.Switch, onValueChange = mask.onChange)
            .semantics { contentDescription = what }.padding(horizontal = 12.dp).testTag("aa_screen_mask_switch"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val ink = if (mask.on) colors.primary else barInk
        MsIcon(if (mask.on) R.drawable.ic_ms_visibility else R.drawable.ic_ms_visibility_off, 18.dp, ink)
        Text(stringResource(R.string.mask_chip), style = MaterialTheme.typography.bodySmall, color = ink)
    }
}

private const val PICTURE_SHAPE = 800f / 480f

/** The finger, one at a time: the first pointer's down, moves and lift (or cancel) go to [PhonePreview.touch]. */
private object FingerToAndroidAuto : android.view.View.OnTouchListener {
    override fun onTouch(view: android.view.View, event: android.view.MotionEvent): Boolean {
        val action = when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> com.reau.app.aa.PhoneTouch.Action.DOWN
            android.view.MotionEvent.ACTION_MOVE -> com.reau.app.aa.PhoneTouch.Action.MOVE
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> com.reau.app.aa.PhoneTouch.Action.UP
            else -> null
        } ?: return true
        PhonePreview.touch(com.reau.app.aa.PhoneTouch(action, event.getX(0), event.getY(0)))
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP) view.performClick()
        return true
    }
}

/** The picture's surface; it takes a click so a finger listener on it stays accessible (lint's ClickableViewAccessibility). */
private class PictureSurface(context: android.content.Context) : SurfaceView(context) {
    override fun performClick(): Boolean = super.performClick()
}
