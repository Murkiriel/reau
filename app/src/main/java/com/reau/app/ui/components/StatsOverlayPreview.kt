package com.reau.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.reau.app.ui.AaScreenMask
import com.reau.app.video.DashGlass
import com.reau.app.video.DashMask
import com.reau.app.video.StatsOverlay
import com.reau.app.video.StatsOverlayInk
import kotlinx.coroutines.delay

/**
 * The stats text of the dash's video ([StatsOverlay]) on the "Tela do Android Auto", where it lands: placed in the
 * dash's 526×300 frame as the mask places that frame on the picture, inked as the video inks it ([StatsOverlayInk]).
 * The numbers are the ones the video carries, read twice a second ([StatsOverlay.latest]); with no stream, dashes.
 */
@Composable
fun StatsOverlayPreview(mask: AaScreenMask, stats: StatsOverlay, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf(StatsOverlay.latest) }
    LaunchedEffect(Unit) {
        while (true) {
            text = StatsOverlay.latest
            delay(REFRESH_MS)
        }
    }
    Canvas(modifier) {
        val m = DashMask.of(mask.mapping, mask.sourceW, mask.sourceH, size.width.toInt(), size.height.toInt()) ?: return@Canvas
        // Screen pixels per frame pixel; the fit can stretch the picture, so one for each way (the letters take the width's).
        val scaleX = (m.frame.right - m.frame.left) / DashGlass.FRAME_W
        val scaleY = (m.frame.bottom - m.frame.top) / DashGlass.FRAME_H
        drawIntoCanvas {
            StatsOverlayInk.draw(it.nativeCanvas, text, stats.size, m.frame.left + stats.x * scaleX, m.frame.top + stats.y * scaleY, scaleX)
        }
    }
}

private const val REFRESH_MS = 500L
