package com.reau.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import com.reau.app.ui.AaScreenMask
import com.reau.app.ui.theme.Grafite
import com.reau.app.ui.theme.Marfim
import com.reau.app.ui.theme.OuroSuave
import com.reau.app.video.DashMask

/**
 * The dash's mask over the "Tela do Android Auto": what never reaches the dash is shaded (Grafite at 82% outside the
 * part of the picture that goes to it, at 72% inside it but off the round glass), the glass's edge is outlined in
 * Marfim, and the dash's own manoeuvre bubble (the dash's ring) is drawn where it covers the picture. The geometry is
 * [DashMask]'s, measured on the dash and tested on the JVM; the colours are the physical dash's, the same in both
 * themes.
 */
@Composable
fun PanelMaskOverlay(mask: AaScreenMask, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val m = DashMask.of(mask.mapping, mask.sourceW, mask.sourceH, size.width.toInt(), size.height.toInt()) ?: return@Canvas
        val all = Path().apply { addRect(Rect(0f, 0f, size.width, size.height)) }
        val box = m.shownBox
        val inFrame = Path()
        val seen = Path()
        if (box.right > box.left && box.bottom > box.top) {
            inFrame.addRect(Rect(box.left, box.top, box.right, box.bottom))
            val glass = Path().apply { addOval(Rect(Offset(m.glass.cx, m.glass.cy), m.glass.r)) }
            seen.op(glass, inFrame, PathOperation.Intersect)
        }
        drawPath(Path().apply { op(all, inFrame, PathOperation.Difference) }, Grafite.copy(alpha = OUTSIDE_SHADE))
        drawPath(Path().apply { op(inFrame, seen, PathOperation.Difference) }, Grafite.copy(alpha = OFF_GLASS_SHADE))
        drawPath(seen, Marfim.copy(alpha = GLASS_EDGE_ALPHA), style = Stroke(1.5.dp.toPx()))
        val ringWidth = RING_WIDTH * m.bubble.r / DashMask.BUBBLE_R
        clipPath(seen) {
            drawCircle(Marfim, m.bubble.r, Offset(m.bubble.cx, m.bubble.cy))
            drawCircle(OuroSuave, m.bubble.r - ringWidth / 2, Offset(m.bubble.cx, m.bubble.cy), style = Stroke(ringWidth))
        }
    }
}

/** The ring's width in dash pixels, like the dash's own gold edge. */
const val RING_WIDTH = 5f

private const val OUTSIDE_SHADE = 0.82f
private const val OFF_GLASS_SHADE = 0.72f
private const val GLASS_EDGE_ALPHA = 0.7f
