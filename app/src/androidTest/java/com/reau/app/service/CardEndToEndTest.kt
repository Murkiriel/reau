package com.reau.app.service

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Network
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.aa.AaLinkState
import com.reau.app.card.CardPainter
import com.reau.app.card.LegibilityCard
import com.reau.app.card.PicturePlacement
import com.reau.app.dash.DashSocket
import com.reau.app.dash.FakeDashPeer
import com.reau.app.video.H264TestDecoder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The card mode on the emulator, inside the service: a still picture → compositor → encoder
 * → RTP → the fake dash, with no Android Auto at all. The video the fake dash gets is decoded and must match the
 * picture at its control positions.
 */
@RunWith(AndroidJUnit4::class)
class CardEndToEndTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val originalCardSides = ProjectionService.cardSidesFactory
    private val dash = FakeDashPeer().apply { autoAck = true }

    private val loopbackLinkUp = object : DashLinkUp {
        override fun start(onReady: (ssid: String, network: Network?) -> Unit, onFailed: (String) -> Unit) = onReady("DashFake", null)
        override fun stop() = Unit
    }

    private fun startCard(picture: () -> Bitmap) {
        ProjectionService.cardSidesFactory = { ctx, report ->
            LiveSides(
                ctx, report,
                LiveSides.Setup(
                    dashSocket = { DashSocket(null, ctrlDest = dash.ctrlDest, rtpDest = dash.rtpDest) },
                    linkUp = { _, _ -> loopbackLinkUp },
                    card = picture,
                ),
            )
        }
        ProjectionService.startCard(context)
        await("the card at the dash", 40_000) { ProjectionService.projection.value.streaming && dash.firstUnits.size >= 30 }
    }

    @After
    fun tearDown() {
        ProjectionService.stop(context)
        await("stopped", 10_000) { ProjectionService.projection.value.phase == Phase.STOPPED }
        await("the service gone", 10_000) { !serviceRunning() }
        dash.close()
        ProjectionService.cardSidesFactory = originalCardSides
    }

    private fun await(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue("$what (projection: ${ProjectionService.projection.value}, units at the dash: ${dash.firstUnits.size})", condition())
    }

    @Suppress("DEPRECATION") // still answers for the app's own services
    private fun serviceRunning(): Boolean =
        context.getSystemService(android.app.ActivityManager::class.java).getRunningServices(50)
            .any { it.service.className == ProjectionService::class.java.name }

    private fun lastPicture(): H264TestDecoder.Picture {
        val chunks = dash.firstUnits.mapIndexed { i, unit -> H264TestDecoder.Chunk(unit, i * 62L) }
        return H264TestDecoder().decode(chunks).last()
    }

    @Test
    fun theLegibilityCardReachesTheDashAndMatchesAtTheControlPositions() {
        startCard { CardPainter.legibility() }

        val p = ProjectionService.projection.value
        assertTrue("the card mode", p.card)
        assertEquals(Headline.CARD, p.headline)
        assertEquals("the card counts as the picture side, up", AaLinkState.ACTIVE, p.androidAuto)

        val picture = lastPicture()
        val wrong = LegibilityCard.layout(CardPainter::measure).controlPoints()
            .map { Triple(it.x, it.y, name(it.color)) }
            .filter { (x, y, expected) -> name(picture.colorAt(x, y)) != expected }
        assertTrue("control points wrong: $wrong", wrong.isEmpty())
    }

    @Test
    fun aRidersPictureReachesTheDashWholeWhereItWasPlaced() {
        startCard { CardPainter.picture(quadrants(800, 480)) }

        val picture = lastPicture()
        val at = PicturePlacement.inFrame(800, 480)
        assertEquals("red", name(picture.colorAt(at.x + at.w / 4, at.y + at.h / 4)))
        assertEquals("green", name(picture.colorAt(at.x + 3 * at.w / 4, at.y + at.h / 4)))
        assertEquals("blue", name(picture.colorAt(at.x + at.w / 4, at.y + 3 * at.h / 4)))
        assertEquals("yellow", name(picture.colorAt(at.x + 3 * at.w / 4, at.y + 3 * at.h / 4)))
        assertEquals("black", name(picture.colorAt(at.x / 2, at.y + at.h / 2)))
    }

    private fun quadrants(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
        val canvas = Canvas(it)
        val paint = Paint()
        for ((i, color) in listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).withIndex()) {
            paint.color = color
            val left = (i % 2) * w / 2f
            val top = (i / 2) * h / 2f
            canvas.drawRect(left, top, left + w / 2f, top + h / 2f, paint)
        }
    }

    private companion object {
        val NAMED = mapOf(
            "red" to Color.RED, "green" to Color.GREEN, "blue" to Color.BLUE, "yellow" to Color.YELLOW,
            "white" to Color.WHITE, "black" to Color.BLACK,
        )

        /** The nearest of the six colours the pictures here are made of. */
        fun name(color: Int): String = NAMED.minByOrNull { (_, c) ->
            val dr = Color.red(color) - Color.red(c)
            val dg = Color.green(color) - Color.green(c)
            val db = Color.blue(color) - Color.blue(c)
            dr * dr + dg * dg + db * db
        }!!.key
    }
}
