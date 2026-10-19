package com.reau.app.card

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.video.DashGlass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The legibility card as a PNG for the bench: the very picture the card mode sends, saved where
 * `adb pull` reaches it (`Android/data/com.reau.app/files/cartelas/cartela_legibilidade.png`), to go to the dash as
 * another app's wallpaper. The test checks that what is saved reads back the same.
 */
@RunWith(AndroidJUnit4::class)
class LegibilityCardExportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theLegibilityCardIsSavedAsAPngThatReadsBackTheSame() {
        val card = CardPainter.legibility()
        val file = File(context.getExternalFilesDir("cartelas"), "cartela_legibilidade.png")
        file.outputStream().use { assertEquals(true, card.compress(Bitmap.CompressFormat.PNG, 100, it)) }

        val back = BitmapFactory.decodeFile(file.path)
        assertNotNull(back)
        assertEquals(DashGlass.FRAME_W to DashGlass.FRAME_H, back.width to back.height)
        val differing = (0 until back.height step 3).sumOf { y -> (0 until back.width step 3).count { x -> back.getPixel(x, y) != card.getPixel(x, y) } }
        assertEquals("pixels that changed on the way to the file", 0, differing)
    }
}
