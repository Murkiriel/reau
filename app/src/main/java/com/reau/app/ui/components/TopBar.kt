@file:Suppress("LongParameterList") // a component takes one parameter per thing it shows, each with its default (as Material 3's)

package com.reau.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reau.app.R
import com.reau.app.ui.theme.LocalREauDark
import com.reau.app.ui.theme.LocalREauStatus

/** The top bar's height, the same on every screen: the main screen's ("REau" centred), which the inner screens' bars match. */
val TopBarHeight = 56.dp

/**
 * The fixed top bar (only the content under it scrolls). Without [title], the main screen's: "REau" centred (with the
 * launcher mark beside it when [markBesideName], and a back button at the start when [onBack] is given). With it,
 * an inner screen's: back ([onBack], tag [backTag]) and the title in Fraunces ([titleTag], [titleStyle]), then [actions]. Dark, it sits
 * on Grafite over a thin Ouro suave line; light, on the Carvão chrome with Marfim text.
 */
@Composable
fun REauTopBar(
    title: String? = null,
    onBack: (() -> Unit)? = null,
    titleTag: String = "screen_title",
    backTag: String = "back",
    titleStyle: TextStyle = MaterialTheme.typography.headlineSmall,
    markBesideName: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val dark = LocalREauDark.current
    val status = LocalREauStatus.current
    val background = if (dark) MaterialTheme.colorScheme.background else status.chrome
    val ink = if (dark) MaterialTheme.colorScheme.onSurface else status.onChrome
    Column(Modifier.fillMaxWidth().background(background).statusBarsPadding().testTag("top_bar")) {
        if (title == null) {
            Box(Modifier.fillMaxWidth().heightIn(min = TopBarHeight).padding(top = 6.dp, bottom = 14.dp), contentAlignment = Alignment.Center) {
                if (markBesideName) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BrandIcon(36.dp, ink = ink) // the arrow in the bar's own text colour: the bar is dark in both themes
                        BrandName(28.sp)
                    }
                } else {
                    BrandName(28.sp)
                }
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).size(48.dp).testTag(backTag)) {
                        MsIcon(R.drawable.ic_ms_arrow_back, 26.dp, ink, description = stringResource(R.string.action_back))
                    }
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().heightIn(min = TopBarHeight).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag(backTag)) {
                        MsIcon(R.drawable.ic_ms_arrow_back, 26.dp, ink, description = stringResource(R.string.action_back))
                    }
                }
                Text(brandTitle(title, brandGold()), style = titleStyle, color = ink, modifier = Modifier.weight(1f).testTag(titleTag))
                // The actions take the bar's ink: Marfim on the Carvão chrome of the light theme too.
                CompositionLocalProvider(LocalContentColor provides ink) { actions() }
            }
        }
        if (dark) HorizontalDivider(thickness = 1.dp, color = hairline(0.22f))
    }
}

/** A title with the app's name in it ("O REau e os seus dados"): the "au" of every "REau" in [gold], as on the main screen. */
internal fun brandTitle(title: String, gold: Color): AnnotatedString = buildAnnotatedString {
    var from = 0
    while (true) {
        val at = title.indexOf("REau", from)
        if (at < 0) {
            append(title.substring(from))
            break
        }
        append(title.substring(from, at + 2))
        withStyle(SpanStyle(color = gold)) { append("au") }
        from = at + 4
    }
}

/** The paths of the launcher icon's drawing (`ic_launcher_foreground`, a 108 x 108 canvas), parsed once. */
private class BrandMark(
    val ring: Path = path("M34,61 A20,20 0 1,1 74,61 A20,20 0 1,1 34,61 Z"),
    val crown: Path = path("M38,46 L35,31 L45.5,38.5 L54,25.5 L62.5,38.5 L73,31 L70,46 Z"),
    val dots: Path = path(
        "M32.4,30.2 A2.6,2.6 0 1,1 37.6,30.2 A2.6,2.6 0 1,1 32.4,30.2 Z M51.4,24.3 A2.6,2.6 0 1,1 56.6,24.3 " +
            "A2.6,2.6 0 1,1 51.4,24.3 Z M70.4,30.2 A2.6,2.6 0 1,1 75.6,30.2 A2.6,2.6 0 1,1 70.4,30.2 Z",
    ),
    val arrow: Path = path("M54,50.5 L62,70 L54,65.5 L46,70 Z"),
)

private fun path(data: String): Path = PathParser().parsePathString(data).toPath()

/**
 * The launcher icon's drawing (the crown over the ring, and the arrow) without its black plate, in the theme's colours: the
 * crown and the ring in [brandGold], the arrow in [ink] (the text colour of where it sits). [height] is its height; it is 50/64 as wide (the drawing's
 * own box, cut out of the 108 dp canvas).
 */
@Composable
fun BrandIcon(
    height: Dp,
    modifier: Modifier = Modifier,
    tag: String = "brand_icon",
    ink: Color = MaterialTheme.colorScheme.onSurface,
) {
    val gold = brandGold()
    val mark = remember { BrandMark() }
    Canvas(modifier.size(width = height * 50f / 64f, height = height).testTag(tag)) {
        val k = size.height / 64f
        withTransform({
            scale(k, k, Offset.Zero)
            translate(-29f, -20.5f)
        }) {
            drawPath(mark.ring, gold, style = Stroke(width = 5f))
            drawPath(mark.crown, gold)
            drawPath(mark.dots, gold)
            drawPath(mark.arrow, ink)
        }
    }
}

/** "REau" in Fraunces 600: "RE" in [re], "au" in [au] (Ouro). Gold text only where the background is dark. */
@Composable
fun BrandName(
    size: TextUnit,
    modifier: Modifier = Modifier,
    re: Color = LocalREauStatus.current.onChrome,
    au: Color = MaterialTheme.colorScheme.primary,
    tag: String = "title",
) {
    val gold = au
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = re)) { append("RE") }
            withStyle(SpanStyle(color = gold)) { append("au") }
        },
        style = MaterialTheme.typography.displaySmall.copy(fontSize = size, lineHeight = size),
        modifier = modifier.testTag(tag),
    )
}
