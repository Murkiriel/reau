package com.reau.app.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

/**
 * Test helper: 800×480 pictures that look like what Android Auto draws for navigation, made on the spot (no real
 * capture of Android Auto is kept in the repository). They carry what a shrink hurts most: text at the sizes the
 * Android Auto navigation screens use, one-pixel lines, thin streets, a route line, a turn arrow, on a day palette
 * and a night one. The numbers they give are for comparing filters with each other, not absolute quality.
 */
object SyntheticAaFrames {
    const val W = 800
    const val H = 480

    class Scene(val name: String, val bitmap: Bitmap)

    fun all(): List<Scene> = listOf(
        Scene("mapa-dia", mapa(night = false)),
        Scene("mapa-noite", mapa(night = true)),
        Scene("cartao-manobra", cartaoManobra()),
        Scene("lista-texto", listaTexto()),
        Scene("linhas-finas", linhasFinas()),
    )

    private fun paint(color: Int, width: Float = 0f, style: Paint.Style = Paint.Style.FILL) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeWidth = width
        this.style = style
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private fun text(color: Int, size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun canvasOf(): Pair<Bitmap, Canvas> = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888).let { it to Canvas(it) }

    private fun mapa(night: Boolean): Bitmap {
        val (bmp, c) = canvasOf()
        val bg = if (night) Color.rgb(27, 35, 48) else Color.rgb(242, 239, 233)
        val street = if (night) Color.rgb(58, 70, 88) else Color.WHITE
        val casing = if (night) Color.rgb(75, 90, 112) else Color.rgb(205, 200, 190)
        val label = if (night) Color.rgb(232, 236, 242) else Color.rgb(60, 64, 67)
        val route = if (night) Color.rgb(76, 154, 255) else Color.rgb(26, 115, 232)
        c.drawColor(bg)
        // blocks of "green" and "water"
        c.drawRect(560f, 40f, 760f, 200f, paint(if (night) Color.rgb(30, 52, 44) else Color.rgb(200, 224, 190)))
        c.drawRect(0f, 380f, 300f, 480f, paint(if (night) Color.rgb(24, 44, 70) else Color.rgb(170, 205, 235)))
        // street grid: wide, medium, thin, each with its casing
        val widths = floatArrayOf(14f, 9f, 5f, 3f)
        val xs = floatArrayOf(90f, 250f, 400f, 560f, 710f)
        val ys = floatArrayOf(70f, 190f, 310f, 420f)
        for ((i, x) in xs.withIndex()) {
            val w = widths[i % widths.size]
            c.drawLine(x, 0f, x + 40f, H.toFloat(), paint(casing, w + 2f, Paint.Style.STROKE))
            c.drawLine(x, 0f, x + 40f, H.toFloat(), paint(street, w, Paint.Style.STROKE))
        }
        for ((i, y) in ys.withIndex()) {
            val w = widths[(i + 1) % widths.size]
            c.drawLine(0f, y, W.toFloat(), y - 30f, paint(casing, w + 2f, Paint.Style.STROKE))
            c.drawLine(0f, y, W.toFloat(), y - 30f, paint(street, w, Paint.Style.STROKE))
        }
        // one-pixel alleys
        for (k in 0 until 12) c.drawLine(40f + k * 60f, 100f, 80f + k * 60f, 460f, paint(casing, 1f, Paint.Style.STROKE))
        // route
        val path = Path().apply {
            moveTo(120f, 470f); lineTo(250f, 330f); lineTo(400f, 300f); lineTo(430f, 150f); lineTo(600f, 120f); lineTo(700f, 40f)
        }
        c.drawPath(path, paint(route, 12f, Paint.Style.STROKE))
        // street names at the sizes of the map labels
        val sizes = floatArrayOf(14f, 16f, 18f, 22f, 26f)
        val names = listOf("Rua das Palmeiras", "Av. Brasil", "Rua Dr. Carvalho", "Travessa Sete", "Alameda Santos", "R. do Comércio")
        for ((i, n) in names.withIndex()) {
            c.save()
            c.rotate(if (i % 2 == 0) -4f else 0f, 100f + i * 110f, 100f + i * 60f)
            c.drawText(n, 100f + i * 110f, 100f + i * 60f, text(label, sizes[i % sizes.size]))
            c.restore()
        }
        c.drawText("Centro", 330f, 440f, text(label, 30f, bold = true))
        // current position marker
        c.drawCircle(120f, 440f, 15f, paint(Color.WHITE))
        c.drawCircle(120f, 440f, 11f, paint(route))
        return bmp
    }

    private fun cartaoManobra(): Bitmap {
        val (bmp, c) = canvasOf()
        c.drawColor(Color.rgb(30, 34, 40))
        c.drawRect(0f, 0f, 380f, 190f, paint(Color.rgb(20, 90, 60)))
        val arrow = Path().apply {
            moveTo(70f, 150f); lineTo(70f, 80f); lineTo(40f, 80f); lineTo(95f, 25f); lineTo(150f, 80f); lineTo(120f, 80f); lineTo(120f, 150f); close()
        }
        c.drawPath(arrow, paint(Color.WHITE))
        c.drawText("300 m", 175f, 78f, text(Color.WHITE, 46f, bold = true))
        c.drawText("Av. Paulista", 175f, 120f, text(Color.WHITE, 30f))
        c.drawText("Rodovia dos Bandeirantes", 20f, 178f, text(Color.rgb(220, 235, 225), 20f))
        // the strip under the map: arrival time, distance, remaining
        c.drawText("18:42  ·  12,4 km  ·  22 min", 20f, 440f, text(Color.WHITE, 24f))
        c.drawText("chegada", 20f, 465f, text(Color.rgb(170, 175, 185), 14f))
        // a lane guide made of thin shapes
        for (i in 0 until 4) {
            val x = 480f + i * 60f
            c.drawRect(x, 40f, x + 36f, 160f, paint(Color.rgb(60, 66, 76)))
            c.drawLine(x + 18f, 140f, x + 18f, 60f, paint(Color.WHITE, 3f, Paint.Style.STROKE))
        }
        return bmp
    }

    private fun listaTexto(): Bitmap {
        val (bmp, c) = canvasOf()
        c.drawColor(Color.WHITE)
        val dark = Color.rgb(32, 33, 36)
        val grey = Color.rgb(95, 99, 104)
        var y = 44f
        val rows = listOf(
            Triple("Chegada às 18:42", 28f, true),
            Triple("Via Rodovia dos Bandeirantes, trânsito leve", 22f, false),
            Triple("Rota mais rápida · 12,4 km · 22 min", 20f, false),
            Triple("Outras rotas: 13,1 km (24 min) · 15,8 km (27 min)", 18f, false),
            Triple("Posto Shell · 800 m · aberto 24 horas", 16f, false),
            Triple("Radar a 450 m · limite 60 km/h", 14f, false),
            Triple("0123456789 abcdefghijklmnopqrstuvwxyz", 13f, false),
            Triple("0123456789 abcdefghijklmnopqrstuvwxyz", 12f, false),
        )
        for ((s, size, bold) in rows) {
            c.drawText(s, 24f, y, text(if (bold) dark else grey, size, bold))
            y += size + 22f
            c.drawLine(24f, y - size - 8f, 776f, y - size - 8f, paint(Color.rgb(218, 220, 224), 1f, Paint.Style.STROKE))
        }
        c.drawText("Texto em branco sobre cor", 24f, 440f, text(Color.WHITE, 22f))
        c.drawRect(0f, 400f, 800f, 480f, paint(Color.rgb(26, 115, 232)))
        c.drawText("Iniciar navegação", 24f, 450f, text(Color.WHITE, 26f, bold = true))
        return bmp
    }

    private fun linhasFinas(): Bitmap {
        val (bmp, c) = canvasOf()
        c.drawColor(Color.WHITE)
        val black = paint(Color.BLACK, 1f, Paint.Style.STROKE)
        // horizontal and vertical one-pixel lines at growing pitch, then a diagonal fan
        for (k in 0 until 40) c.drawLine(10f, 10.5f + k * (2 + k / 8), 390f, 10.5f + k * (2 + k / 8), black)
        for (k in 0 until 40) c.drawLine(410.5f + k * (2 + k / 8), 10f, 410.5f + k * (2 + k / 8), 230f, black)
        for (k in 0 until 24) c.drawLine(10f, 400f, 390f, 250f + k * 10f, black)
        c.drawText("Aa Bb Cc 0123456789", 410f, 270f, text(Color.BLACK, 12f))
        c.drawText("Aa Bb Cc 0123456789", 410f, 300f, text(Color.BLACK, 14f))
        c.drawText("Aa Bb Cc 0123456789", 410f, 335f, text(Color.BLACK, 18f))
        c.drawText("Aa Bb Cc 0123456789", 410f, 380f, text(Color.BLACK, 24f))
        c.drawText("Aa Bb Cc", 410f, 440f, text(Color.BLACK, 40f, bold = true))
        return bmp
    }
}
