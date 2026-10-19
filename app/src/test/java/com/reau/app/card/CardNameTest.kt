package com.reau.app.card

import org.junit.Assert.assertEquals
import org.junit.Test

/** The short name the screens show for the picture the rider picked, from the content URI the picker gives. */
class CardNameTest {

    @Test
    fun `a file picked from a folder shows its file name`() {
        assertEquals("card.png", CardName.of("content://com.android.externalstorage.documents/document/primary%3APictures%2Fcard.png"))
        assertEquals(
            "cartela 1.png",
            CardName.of("content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fcartela%201.png"),
        )
    }

    @Test
    fun `a picture from the gallery shows what the URI has`() {
        assertEquals("1234", CardName.of("content://com.android.providers.media.documents/document/image%3A1234"))
    }

    @Test
    fun `a plain file URI shows its file name`() {
        assertEquals("grade.png", CardName.of("file:///sdcard/grade.png"))
    }

    @Test
    fun `something that is not a URI is shown as it is`() {
        assertEquals("cartela", CardName.of("cartela"))
    }

    // The gallery's number gives way to the file's own name, with its extension.

    @Test
    fun `the name the provider gives is shown, with its extension`() {
        val gallery = "content://com.android.providers.media.documents/document/image%3A5678"
        assertEquals("cartela.png", CardName.shown(gallery) { "cartela.png" })
        assertEquals("foto da moto.jpg", CardName.shown("content://media/external/images/media/91") { "foto da moto.jpg" })
    }

    @Test
    fun `without a name from the provider the URI's last part stays`() {
        val gallery = "content://com.android.providers.media.documents/document/image%3A4321"
        assertEquals("4321", CardName.shown(gallery) { null })
        assertEquals("4321", CardName.shown(gallery) { " " })
        assertEquals("4321", CardName.shown(gallery) { throw SecurityException("permission gone") })
    }

    @Test
    fun `the provider is asked once for the same picture`() {
        val uri = "content://com.android.providers.media.documents/document/image%3A2468"
        var asked = 0
        repeat(3) { assertEquals("grade.jpg", CardName.shown(uri) { asked++; "grade.jpg" }) }
        assertEquals(1, asked)
        assertEquals("outra.png", CardName.shown("content://media/external/images/media/13579") { "outra.png" })
    }
}
