package com.reau.app.service

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.card.LegibilityCard
import com.reau.app.card.PicturePlacement
import com.reau.app.settings.REauSettings
import com.reau.app.settings.SettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What the card mode sends on the phone: the measuring grid built in, the legibility card, or the
 * picture picked in the settings.
 */
@RunWith(AndroidJUnit4::class)
class CardSetupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.cacheDir, "card_setup_test.png")

    @After
    fun tearDown() {
        SettingsStore.open(context).save(REauSettings())
        file.delete()
    }

    private fun sent(): Bitmap = LiveSides.Setup.forCard(context).card!!.invoke()

    private fun isTheLegibilityCard(bitmap: Bitmap) {
        val red = LegibilityCard.layout({ _, _ -> 0 }).patches.first { it.name == "red" }.rect
        assertEquals(LegibilityCard.layout({ _, _ -> 0 }).patches.first().color, bitmap.getPixel(red.x + red.w / 2, red.y + red.h / 2))
        assertEquals("the top of the glass's circle", LegibilityCard.OUTLINE_GREY, bitmap.getPixel(263, 0))
    }

    @Test
    fun withTheLegibilityCardChosenItIsTheLegibilityCard() {
        SettingsStore.open(context).save(REauSettings(cardImage = null))
        isTheLegibilityCard(sent())
    }

    /** A measuring grid, built in, is the card's default. */
    @Test
    fun withNothingPickedItIsTheBuiltInGrid() {
        SettingsStore.open(context).save(REauSettings())
        val grid = android.graphics.BitmapFactory.decodeResource(
            context.resources, com.reau.app.R.drawable.grade_medicao, android.graphics.BitmapFactory.Options().apply { inScaled = false },
        )
        assertEquals(526 to 300, grid.width to grid.height)
        val bitmap = sent()
        for ((x, y) in listOf(0 to 0, 263 to 150, 100 to 50, 500 to 290, 40 to 260)) {
            assertEquals("pixel $x,$y", grid.getPixel(x, y), bitmap.getPixel(x, y))
        }
    }

    @Test
    fun aPickedPictureIsSentPlaced() {
        Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            .let { b -> file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        SettingsStore.open(context).save(REauSettings(cardImage = Uri.fromFile(file).toString()))

        val bitmap = sent()
        val at = PicturePlacement.inFrame(400, 400)
        assertEquals(Color.BLUE, bitmap.getPixel(at.x + at.w / 2, at.y + at.h / 2))
        assertEquals("black beside it", Color.BLACK, bitmap.getPixel(at.x / 2, 150))
    }

    @Test
    fun aPictureThatCannotBeReadFallsBackToTheLegibilityCard() {
        SettingsStore.open(context).save(REauSettings(cardImage = Uri.fromFile(File(context.cacheDir, "gone.png")).toString()))
        isTheLegibilityCard(sent())
    }
}
