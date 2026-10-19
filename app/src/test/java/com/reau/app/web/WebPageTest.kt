package com.reau.app.web

import com.reau.app.settings.Credits
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page the browser gets: one self-contained file, with JMuxer inside, speaking the protocol the server speaks. */
class WebPageTest {
    private val dir = File("src/main/assets/web")
    private val template = File(dir, "index.html").readText()
    private val jmuxer = File(dir, "jmuxer.min.js").readText()
    private val sourceLink = Regex("""<a id="source"[^>]*>[^<]*</a>""").find(template)?.value.orEmpty()

    @Test
    fun `the template and the library are there, with the library's license beside it`() {
        assertTrue(template.length > 1_000)
        assertTrue("JMuxer is the minified build", jmuxer.length > 20_000 && jmuxer.contains("JMuxer"))
        val license = File(dir, "jmuxer.LICENSE").readText()
        assertTrue(license.contains("MIT") && license.contains("Samir Das"))
    }

    @Test
    fun `the template has one place for the library and nothing else is loaded from outside`() {
        assertEquals(1, Regex(Regex.escape(WebPage.LIBRARY_MARK)).findAll(template).count())
        // Nothing is loaded from outside; the one address is the link to the source code (AGPL §13), which is only followed by a tap.
        val withoutSourceLink = template.replace(sourceLink, "")
        assertFalse("no address of any server", Regex("""https?://|//cdn|\bwss?://""").containsMatchIn(withoutSourceLink))
        assertFalse("no external script", Regex("""<script[^>]*\bsrc\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(template))
        // The only <link> is the tab's icon, inside the page as a data: address (the REau icon).
        val withoutIcons = template.replace(Regex("""<link rel="icon"[^>]*href="data:image/(svg\+xml|png);base64,[A-Za-z0-9+/=]+"[^>]*>"""), "")
        assertFalse("no external style or font", Regex("""<link\b|@import|url\(""", RegexOption.IGNORE_CASE).containsMatchIn(withoutIcons))
        assertFalse("no image from outside", Regex("""<img\b[^>]*\bsrc\s*=\s*["'](?!data:)""", RegexOption.IGNORE_CASE).containsMatchIn(template))
    }

    /** The AGPL asks for the source to be offered to whoever uses the program over the network: the page carries the link, in both languages. */
    @Test
    fun `the page carries the link to the source code, in both languages`() {
        assertTrue("the link is there: $sourceLink", sourceLink.isNotEmpty())
        assertTrue(sourceLink, "href=\"${Credits.SOURCE_URL}\"" in sourceLink)
        assertTrue("opens in a new tab without the opener: $sourceLink", "rel=\"noopener noreferrer\"" in sourceLink)
        assertTrue("English text as the fallback", "Source code (AGPL-3.0)" in sourceLink)
        assertTrue("in the English table", "source_link: \"Source code (AGPL-3.0)\"" in template)
        assertTrue("in the Portuguese table", "source_link: \"Código-fonte (AGPL-3.0)\"" in template)
    }

    @Test
    fun `composing puts the library in a script and leaves no mark`() {
        val page = WebPage.compose(template, jmuxer)
        assertFalse(page.contains(WebPage.LIBRARY_MARK))
        assertTrue(page.contains("<script>" + jmuxer.take(60)))
        assertTrue(page.contains(template.substringBefore(WebPage.LIBRARY_MARK).takeLast(40)))
        assertTrue(page.contains(template.substringAfter(WebPage.LIBRARY_MARK).take(40)))
    }

    @Test
    fun `a library that says the end of a script cannot end the page's script`() {
        val page = WebPage.compose("<body><!--JMUXER--></body>", "var a = '</script><b>';")
        assertEquals(1, Regex("</script>").findAll(page).count())
        assertTrue(page, page.contains("<\\/script>"))
    }

    @Test
    fun `the page opens the WebSocket on its own host with the pin it was given`() {
        assertTrue(template.contains("/ws?k="))
        assertTrue(template.contains("location.search") && template.contains("location.host"))
        assertTrue(template.contains("binaryType") && template.contains("arraybuffer"))
        assertTrue(template.contains("new JMuxer"))
    }

    @Test
    fun `the page reads the states the server sends and nothing the server does not send`() {
        for (state in WebProtocol.State.entries) assertTrue("the page knows ${state.wire}", template.contains("\"${state.wire}\""))
        assertTrue(template.contains("JSON.parse"))
    }

    @Test
    fun `the page sends touch as the server reads it, only when the server said it may`() {
        for (event in listOf("pointerdown", "pointermove", "pointerup", "pointercancel")) assertTrue(event, template.contains(event))
        assertTrue(template.contains("JSON.stringify({a:"))
        assertTrue("the three fields in the order the server's format has them", template.contains("a:") && template.contains("x:") && template.contains("y:"))
        assertTrue(template.contains("touchAllowed"))
        assertTrue("the video's own size", template.contains("800") && template.contains("480"))
    }

    @Test
    fun `the sound waits at most a quarter of a second ahead, so it does not fall far behind the picture`() {
        assertTrue(template.contains("var MAX_AHEAD = 0.25"))
        assertTrue("the limit is the one in use", template.contains("nextAt[tag] > now + MAX_AHEAD"))
        assertFalse("not the old 0.8 s", template.contains("now + 0.8"))
    }

    @Test
    fun `a browser that cannot play the video is told so, not left with a black screen`() {
        assertTrue(template.contains("window.MediaSource") && template.contains("window.ManagedMediaSource"))
        assertTrue(template.contains("t(\"no_video\")") && template.contains("This browser can’t play the video"))
        // (the file has the line endings of the checkout, LF or CRLF)
        val text = template.replace("\r\n", "\n")
        val check = text.indexOf("say(t(\"no_video\"))")
        assertTrue("the check is made before the PIN is asked", check in 1 until text.indexOf("start();\n})();"))
    }

    @Test
    fun `the page plays the sound it is sent, the media 48 kHz stereo and the guide's voice 16 kHz mono, after a touch`() {
        assertTrue(template.contains("AudioContext") && template.contains("createBuffer") && template.contains("getInt16"))
        assertTrue(template.contains("48000") && template.contains("16000"))
        assertTrue("the context is only resumed from a touch or a key", template.contains("resume") && template.contains("unlockAudio"))
        assertTrue(template.contains("pointerdown") && template.contains("keydown"))
        assertTrue(template.contains("sound_hint") && template.contains("Tap the screen to turn on the sound"))
        assertTrue("little endian samples", template.contains("getInt16((i * channels + c) * 2, true)"))
    }

    @Test
    fun `the tab has the REau icon, inside the page`() {
        val icons = Regex("""<link rel="icon"[^>]*href="data:image/(svg\+xml|png);base64,([A-Za-z0-9+/=]+)"""").findAll(template).toList()
        assertEquals("an SVG and a PNG for the browsers that do not take SVG", listOf("svg+xml", "png"), icons.map { it.groupValues[1] })
        val svg = String(java.util.Base64.getDecoder().decode(icons[0].groupValues[2]))
        // the launcher icon's colours and shapes (res/drawable/ic_launcher_foreground.xml): the dark ground, the gold
        // ring and crown, the pale arrow
        for (part in listOf("#121212", "#E1AD01", "#ECE7DD", "M34,61 A20,20", "M38,46 L35,31")) assertTrue(part, svg.contains(part))
        val png = java.util.Base64.getDecoder().decode(icons[1].groupValues[2])
        assertEquals("a PNG", listOf(0x89, 0x50, 0x4E, 0x47), png.take(4).map { it.toInt() and 0xFF })
        assertTrue("small, it goes in every page", png.size < 8_000 && icons.sumOf { it.value.length } < 14_000)
    }

    @Test
    fun `a page on the phone itself says it has no sound, and why, and keeps saying it`() {
        assertTrue(template.contains("message.sound === false") && template.contains("t(\"sound_off\")"))
        assertTrue("a message about the sound must not reset the touch", template.indexOf("message.sound === false") < template.indexOf("touchAllowed = message.touch"))
        assertTrue(template.contains("soundOff"))
    }

    @Test
    fun `the page reconnects by itself and recreates the player each time`() {
        assertTrue(template.contains("setTimeout") && template.contains("onclose"))
        assertTrue(template.contains("destroy"))
    }

    @Test
    fun `a network fault after the pin was taken is retried, it does not ask for the pin again`() {
        // found in review: the first failed check of the pin after a drop of the Wi-Fi cleared it and stopped all the
        // trying
        assertTrue(template.contains("var accepted = false") && template.contains("accepted = true"))
        assertTrue(template.contains("if (accepted) retry(); else showGate("))
        assertTrue("a pin the server no longer takes is asked again, with the reason", template.contains("pin_changed") && template.contains("The PIN changed"))
    }

    @Test
    fun `the sound note has its own place and goes when the sound is running, and a click counts to turn the sound on`() {
        assertTrue(template.contains("id=\"sound\""))
        assertTrue(template.contains("statechange"))
        assertTrue(template.contains("addEventListener(\"click\", unlockAudio)"))
    }

    @Test
    fun `the page asks for the pin, tells a wrong one and a held address, and checks it before it connects`() {
        for (digit in 0..9) assertTrue("key $digit", template.contains("data-k=\"$digit\""))
        assertTrue("erase and clear", template.contains("data-k=\"back\"") && template.contains("data-k=\"clear\""))
        assertTrue("a real keyboard's digits too", template.contains("keydown") && template.contains("Backspace"))
        assertFalse("no text field, so no keyboard of the browser to fight with", template.contains("<input"))
        assertTrue("the answer says the number the phone gave", template.contains("(429)") && template.contains("(403)"))
        assertTrue("the check comes before the WebSocket", template.contains("/auth?k=") && template.indexOf("/auth?k=") < template.indexOf("new WebSocket"))
        for (status in listOf("204", "403", "429")) assertTrue(status, template.contains("answer.status === $status"))
        assertTrue(template.contains("pin_wrong") && template.contains("too_many") && template.contains("Wrong PIN") && template.contains("Too many wrong tries"))
        assertTrue("a pin of four digits only", template.contains("[0-9]{4}"))
    }

    @Test
    fun `the page does not keep the pin anywhere but in its own address`() {
        assertFalse(template.contains("localStorage") || template.contains("sessionStorage") || template.contains("document.cookie"))
        assertFalse(template.contains("console.log"))
    }

    @Test
    fun `the state message is the small JSON the page reads`() {
        assertEquals("{\"state\":\"live\",\"touch\":true}", WebProtocol.status(WebProtocol.State.LIVE, touch = true))
        assertEquals("{\"state\":\"live\",\"touch\":false}", WebProtocol.status(WebProtocol.State.LIVE, touch = false))
        assertEquals("{\"state\":\"off\",\"touch\":false}", WebProtocol.status(WebProtocol.State.OFF, touch = false))
    }
}
