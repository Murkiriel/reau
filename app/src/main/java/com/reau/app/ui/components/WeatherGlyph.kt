// The file is the composable WeatherGlyph; the enum is only its layers.
@file:Suppress("MatchingDeclarationName")

package com.reau.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.reau.app.weather.WeatherCondition

/** The drawable pieces a weather icon is composed from, back to front. */
internal enum class WeatherGlyphLayer { SUN, SUN_BEHIND, CLOUD, DARK_CLOUD, FOG, DRIZZLE, RAIN, SNOW, BOLT, UNKNOWN }

/**
 * Icons that describe the weather at a glance instead of one tint (a single yellow tint read badly): each condition
 * is a stack of coloured layers (sun, clouds, drops, flakes, bolt, fog).
 */
internal fun weatherGlyphLayers(condition: WeatherCondition): List<WeatherGlyphLayer> = when (condition) {
    WeatherCondition.CLEAR -> listOf(WeatherGlyphLayer.SUN)
    WeatherCondition.PARTLY_CLOUDY -> listOf(WeatherGlyphLayer.SUN_BEHIND, WeatherGlyphLayer.CLOUD)
    WeatherCondition.CLOUDY -> listOf(WeatherGlyphLayer.CLOUD)
    WeatherCondition.FOG -> listOf(WeatherGlyphLayer.CLOUD, WeatherGlyphLayer.FOG)
    WeatherCondition.DRIZZLE -> listOf(WeatherGlyphLayer.CLOUD, WeatherGlyphLayer.DRIZZLE)
    WeatherCondition.RAIN -> listOf(WeatherGlyphLayer.DARK_CLOUD, WeatherGlyphLayer.RAIN)
    WeatherCondition.SNOW -> listOf(WeatherGlyphLayer.CLOUD, WeatherGlyphLayer.SNOW)
    WeatherCondition.THUNDERSTORM -> listOf(WeatherGlyphLayer.DARK_CLOUD, WeatherGlyphLayer.BOLT, WeatherGlyphLayer.RAIN)
    WeatherCondition.UNKNOWN -> listOf(WeatherGlyphLayer.UNKNOWN)
}

/** Mid-tone colours that read on both the light and the dark card background. */
internal fun weatherGlyphColor(layer: WeatherGlyphLayer): Long = when (layer) {
    WeatherGlyphLayer.SUN, WeatherGlyphLayer.SUN_BEHIND -> 0xFFFFB300
    WeatherGlyphLayer.CLOUD -> 0xFFB0BEC5
    WeatherGlyphLayer.DARK_CLOUD -> 0xFF78909C
    WeatherGlyphLayer.FOG -> 0xFF90A4AE
    WeatherGlyphLayer.DRIZZLE -> 0xFF64B5F6
    WeatherGlyphLayer.RAIN -> 0xFF1E88E5
    WeatherGlyphLayer.SNOW -> 0xFF4FC3F7
    WeatherGlyphLayer.BOLT -> 0xFFFFD600
    WeatherGlyphLayer.UNKNOWN -> 0xFF9E9E9E
}

/** The weather icon of [condition], [size] square, described to a screen reader by [contentDescription]. */
@Composable
fun WeatherGlyph(condition: WeatherCondition, contentDescription: String, size: Dp, modifier: Modifier = Modifier) {
    val layers = weatherGlyphLayers(condition)
    Canvas(modifier.size(size).semantics { this.contentDescription = contentDescription }) {
        // Drawn on a 24-unit grid.
        val u = this.size.minDimension / 24f
        for (layer in layers) drawLayer(layer, u, Color(weatherGlyphColor(layer)))
    }
}

private fun DrawScope.drawLayer(layer: WeatherGlyphLayer, u: Float, color: Color) {
    when (layer) {
        WeatherGlyphLayer.SUN -> drawSun(Offset(12 * u, 12 * u), 4.6f * u, 8.5f * u, u, color)
        WeatherGlyphLayer.SUN_BEHIND -> drawSun(Offset(9 * u, 8.5f * u), 3.4f * u, 6.4f * u, u, color)
        WeatherGlyphLayer.CLOUD, WeatherGlyphLayer.DARK_CLOUD -> drawCloud(u, color)
        WeatherGlyphLayer.FOG, WeatherGlyphLayer.DRIZZLE, WeatherGlyphLayer.RAIN, WeatherGlyphLayer.SNOW ->
            drawBelowCloud(layer, u, color)
        WeatherGlyphLayer.BOLT -> drawPath(
            Path().apply {
                moveTo(13 * u, 13 * u); lineTo(9.5f * u, 19 * u); lineTo(12 * u, 19 * u)
                lineTo(10.5f * u, 23.5f * u); lineTo(15 * u, 17 * u); lineTo(12.5f * u, 17 * u); lineTo(14.5f * u, 13 * u)
                close()
            },
            color,
        )
        WeatherGlyphLayer.UNKNOWN -> drawCircle(color, 8 * u, Offset(12 * u, 12 * u), style = Stroke(2 * u))
    }
}

/** What falls or hangs under the cloud: fog lines, drizzle, rain, snow. */
private fun DrawScope.drawBelowCloud(layer: WeatherGlyphLayer, u: Float, color: Color) {
    when (layer) {
        WeatherGlyphLayer.FOG -> for (y in listOf(19.5f, 22f)) {
            drawLine(color, Offset(5 * u, y * u), Offset(19 * u, y * u), 1.6f * u, StrokeCap.Round)
        }
        WeatherGlyphLayer.DRIZZLE -> for (x in listOf(9f, 15f)) drawDrop(x * u, 20.5f * u, u, color)
        WeatherGlyphLayer.RAIN -> for (x in listOf(8f, 12f, 16f)) drawDrop(x * u, 20.5f * u, u, color)
        else -> for (x in listOf(8.5f, 15.5f)) drawFlake(Offset(x * u, 20.5f * u), 2.2f * u, u, color)
    }
}

private fun DrawScope.drawSun(center: Offset, radius: Float, rayEnd: Float, u: Float, color: Color) {
    drawCircle(color, radius, center)
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        val cos = kotlin.math.cos(a).toFloat()
        val sin = kotlin.math.sin(a).toFloat()
        val start = radius + 1.4f * u
        val from = Offset(center.x + cos * start, center.y + sin * start)
        drawLine(color, from, Offset(center.x + cos * rayEnd, center.y + sin * rayEnd), 1.6f * u, StrokeCap.Round)
    }
}

private fun DrawScope.drawCloud(u: Float, color: Color) {
    drawCircle(color, 3.8f * u, Offset(8 * u, 13 * u))
    drawCircle(color, 5 * u, Offset(12.5f * u, 10.5f * u))
    drawCircle(color, 3.6f * u, Offset(17 * u, 13.2f * u))
    drawRoundRect(color, Offset(4.2f * u, 12 * u), Size(16.6f * u, 4.8f * u), CornerRadius(2.4f * u))
}

private fun DrawScope.drawDrop(x: Float, y: Float, u: Float, color: Color) =
    drawLine(color, Offset(x + 0.8f * u, y - 2 * u), Offset(x - 0.8f * u, y + 1.6f * u), 1.6f * u, StrokeCap.Round)

private fun DrawScope.drawFlake(center: Offset, r: Float, u: Float, color: Color) {
    for (i in 0 until 3) {
        val a = Math.toRadians(i * 60.0)
        val dx = kotlin.math.cos(a).toFloat() * r
        val dy = kotlin.math.sin(a).toFloat() * r
        drawLine(color, Offset(center.x - dx, center.y - dy), Offset(center.x + dx, center.y + dy), 1.2f * u, StrokeCap.Round)
    }
}
