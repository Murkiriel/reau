package com.reau.app.card

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A picture from the gallery is shown by its file's name ("cartela.png"), not by the
 * gallery's number at the end of its URI. A real picture in the emulator's MediaStore, asked through Android.
 */
@RunWith(AndroidJUnit4::class)
class CardNameDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var made: Uri? = null

    /**
     * A run that was cut short leaves its pictures in the MediaStore, and Android then names the next one "name (1).png":
     * the test clears what an earlier run left before it starts.
     */
    @Before
    fun clearWhatAnEarlierRunLeft() {
        context.contentResolver.delete(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("cartela_reau_teste%", "moto_reau_teste%"),
        )
    }

    @After
    fun tearDown() {
        made?.let { context.contentResolver.delete(it, null, null) }
    }

    private fun picture(name: String, mime: String, format: Bitmap.CompressFormat): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/REauTest")
        }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        made = uri
        context.contentResolver.openOutputStream(uri).use { out ->
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(format, 100, checkNotNull(out))
        }
        return uri
    }

    @Test
    fun aGalleryPictureIsShownByItsFileNameWithTheExtension() {
        val uri = picture("cartela_reau_teste.png", "image/png", Bitmap.CompressFormat.PNG)
        assertEquals("the gallery's number, before", uri.lastPathSegment, CardName.of(uri.toString()))
        assertEquals("cartela_reau_teste.png", CardName.shown(context, uri.toString()))
    }

    @Test
    fun aJpegKeepsItsOwnExtension() {
        val uri = picture("moto_reau_teste.jpg", "image/jpeg", Bitmap.CompressFormat.JPEG)
        assertEquals("moto_reau_teste.jpg", CardName.shown(context, uri.toString()))
    }

    @Test
    fun theBuiltInGridKeepsItsName() {
        assertEquals("grade_medicao", CardName.shown(context, com.reau.app.settings.REauSettings.DEFAULT_CARD))
    }
}
