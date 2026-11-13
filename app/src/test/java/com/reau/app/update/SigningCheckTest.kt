package com.reau.app.update

import com.reau.app.update.SigningCheck.Verdict
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fingerprint of the signing certificate in "Check for updates": how it is read, shown and compared with the project's. */
class SigningCheckTest {
    private val one = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val other = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun `a fingerprint is accepted with colons, spaces and capitals, and only with 64 hexadecimal digits`() {
        assertEquals(one, SigningCheck.normalize(one))
        assertEquals(one, SigningCheck.normalize(one.uppercase()))
        assertEquals(one, SigningCheck.normalize(one.chunked(2).joinToString(":")))
        assertEquals(one, SigningCheck.normalize(" ${one.chunked(8).joinToString(" ")}\n"))
        assertNull("63 digits", SigningCheck.normalize(one.dropLast(1)))
        assertNull("65 digits", SigningCheck.normalize(one + "0"))
        assertNull("a digit that is not hexadecimal", SigningCheck.normalize(one.dropLast(1) + "g"))
        assertNull("empty", SigningCheck.normalize(""))
        assertNull("a SHA-1 is not a SHA-256", SigningCheck.normalize(one.take(40)))
    }

    @Test
    fun `it is shown as capital pairs with colons, in two lines of 16, and reads back as itself`() {
        val shown = SigningCheck.display(one)
        val lines = shown.split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.split(":").size == 16 && it.length == 47 })
        assertEquals("BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23", lines[0])
        assertEquals("B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD", lines[1])
        assertEquals(one, SigningCheck.normalize(shown))
        assertEquals("what is not a fingerprint stays as it is", "nothing", SigningCheck.display("nothing"))
    }

    @Test
    fun `the fingerprint of a certificate is the SHA-256 of its bytes`() {
        assertEquals(one, SigningCheck.fingerprint("abc".toByteArray()))
        assertEquals(other, SigningCheck.fingerprint(ByteArray(0)))
        assertNotEquals(one, SigningCheck.fingerprint("abd".toByteArray()))
    }

    @Test
    fun `the installed one matches the expected one whatever the way it is written`() {
        assertEquals(Verdict.MATCH, SigningCheck.verdict(one, listOf(one)))
        assertEquals(Verdict.MATCH, SigningCheck.verdict(one.uppercase(), listOf(one)))
        assertEquals(Verdict.MATCH, SigningCheck.verdict(one, listOf(SigningCheck.display(one))))
        assertEquals(Verdict.MATCH, SigningCheck.verdict(SigningCheck.display(one), listOf(one.uppercase())))
    }

    @Test
    fun `a different certificate, or one more signer beside the expected one, does not match`() {
        assertEquals(Verdict.DIFFERENT, SigningCheck.verdict(one, listOf(other)))
        assertEquals(Verdict.DIFFERENT, SigningCheck.verdict(one, listOf(one.dropLast(1) + "e")))
        assertEquals("a second signer is not the project's signature", Verdict.DIFFERENT, SigningCheck.verdict(one, listOf(one, other)))
        assertEquals(Verdict.DIFFERENT, SigningCheck.verdict(one, listOf(other, one)))
    }

    @Test
    fun `with no expected fingerprint there is nothing to compare, and with no installed one nothing is said`() {
        assertEquals(Verdict.NOT_SET, SigningCheck.verdict("", listOf(one)))
        assertEquals(Verdict.NOT_SET, SigningCheck.verdict("not a fingerprint", listOf(one)))
        assertEquals(Verdict.UNREADABLE, SigningCheck.verdict(one, null))
        assertEquals(Verdict.UNREADABLE, SigningCheck.verdict(one, emptyList()))
        assertEquals(Verdict.UNREADABLE, SigningCheck.verdict(one, listOf("broken")))
        assertEquals("what cannot be read comes before what is not set", Verdict.UNREADABLE, SigningCheck.verdict("", null))
    }

    @Test
    fun `the project's fingerprint is the one of the official key, and the comparison uses it`() {
        val official = "63f5c3ba687699b0b4288a878bd8372bed2c9632222d7f673ea71073aa208970"
        assertEquals("the certificate of the Releases 0.1.0 and 0.2.0, confirmed by the owner", official, SigningCheck.EXPECTED_SHA256)
        assertEquals(official, SigningCheck.normalize(SigningCheck.EXPECTED_SHA256))
        assertEquals(Verdict.MATCH, SigningCheck.verdict(SigningCheck.EXPECTED_SHA256, listOf(official)))
        assertEquals(Verdict.MATCH, SigningCheck.verdict(SigningCheck.EXPECTED_SHA256, listOf(official.uppercase()), debugBuild = true))
        assertEquals(Verdict.DIFFERENT, SigningCheck.verdict(SigningCheck.EXPECTED_SHA256, listOf(one)))
        assertEquals("one digit apart is another certificate", Verdict.DIFFERENT, SigningCheck.verdict(SigningCheck.EXPECTED_SHA256, listOf(official.dropLast(1) + "1")))
    }

    @Test
    fun `a debug build with another signature is told neutrally, a release with another signature is a difference`() {
        val official = SigningCheck.EXPECTED_SHA256
        assertEquals("debug key on a debug build", Verdict.DEBUG_BUILD, SigningCheck.verdict(official, listOf(other), debugBuild = true))
        assertEquals("a release signed with another key", Verdict.DIFFERENT, SigningCheck.verdict(official, listOf(other), debugBuild = false))
        assertEquals("the default is a release", Verdict.DIFFERENT, SigningCheck.verdict(official, listOf(other)))
        assertEquals("a debug build with two signers is still not the project's", Verdict.DEBUG_BUILD, SigningCheck.verdict(official, listOf(official, other), debugBuild = true))
        assertEquals("what cannot be read is told as such on a debug build too", Verdict.UNREADABLE, SigningCheck.verdict(official, null, debugBuild = true))
        assertEquals("nothing to compare with is told as such on a debug build too", Verdict.NOT_SET, SigningCheck.verdict("", listOf(other), debugBuild = true))
    }

    @Test
    fun `the debug text is not an alarm in the three languages and says it is the debug key`() {
        val words = mapOf("values" to listOf("Debug build", "debug key"), "values-pt" to listOf("Build de depuração", "chave de depuração"), "values-es" to listOf("Versión de depuración", "clave de depuración"))
        for ((folder, expected) in words) {
            val xml = File("src/main/res/$folder/strings.xml").readText()
            val debug = Regex("""<string name="update_sign_debug">(.*?)</string>""").find(xml)!!.groupValues[1]
            val differ = Regex("""<string name="update_sign_differ">(.*?)</string>""").find(xml)!!.groupValues[1]
            for (word in expected) assertTrue("$folder: '$word' in $debug", word in debug)
            assertNotEquals("$folder: the debug text is not the one of a difference", differ, debug)
        }
    }

    @Test
    fun `the eight texts of the block are in the three languages, each one its own`() {
        val keys = listOf("update_sign_title", "update_sign_expected", "update_sign_installed", "update_sign_match", "update_sign_differ", "update_sign_debug", "update_sign_unset", "update_sign_unreadable")
        val seen = mutableSetOf<String>()
        for (folder in listOf("values", "values-pt", "values-es")) {
            val xml = File("src/main/res/$folder/strings.xml").readText()
            for (key in keys) {
                val text = Regex("""<string name="$key">(.*?)</string>""").find(xml)?.groupValues?.get(1)
                assertTrue("$folder: $key", !text.isNullOrBlank())
                assertTrue("$folder $key is its own text", seen.add("$folder/$text"))
            }
            assertTrue("$folder: SHA-256 is named", "SHA-256" in Regex("""<string name="update_sign_installed">(.*?)</string>""").find(xml)!!.groupValues[1])
        }
    }

    @Test
    fun `the block shows the fingerprints in a monospace font, selectable, from the constant and from the package record`() {
        val source = File("src/main/java/com/reau/app/ui/UpdateGroup.kt").readText()
        val fingerprint = source.substringAfter("private fun Fingerprint(")
        assertTrue("monospace", "fontFamily = FontFamily.Monospace" in fingerprint)
        assertTrue("selectable and copiable", "SelectionContainer {" in fingerprint)
        assertTrue("the expected one is the constant, and the debug build is told from the release", "SigningCheck.verdict(SigningCheck.EXPECTED_SHA256, installed, BuildConfig.DEBUG)" in source)
        assertTrue("the expected one is shown only when it is a real fingerprint", "SigningCheck.normalize(SigningCheck.EXPECTED_SHA256) != null" in source)
        assertTrue("the debug build has its own text", "SigningCheck.Verdict.DEBUG_BUILD -> R.string.update_sign_debug" in source)
        assertTrue("the installed one is read from the package record", "remember { SigningCheck.installed(context) }" in source)
        val reading = File("src/main/java/com/reau/app/update/SigningCheck.kt").readText()
        assertTrue("the signing certificates of the package, not the signing history", "GET_SIGNING_CERTIFICATES" in reading && "apkContentsSigners" in reading)
        assertTrue("both fingerprints are shown with the same formatting", Regex.escape("SigningCheck.display(").toRegex().findAll(source).count() == 2)
    }
}
