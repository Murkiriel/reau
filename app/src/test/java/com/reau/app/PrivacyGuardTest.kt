package com.reau.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A guard on what gets published: nothing in the versioned tree may identify a rider, a bike or a machine. No VIN, no
 * Wi-Fi hardware address (BSSID or MAC), no precise coordinates and no path of the developer's computer. Sample
 * coordinates in tests are city centres, listed below on purpose.
 */
class PrivacyGuardTest {
    private val windowsHome = "C:" + "[\\\\/]+Users[\\\\/]"
    private val workspace = "C:" + "[\\\\/]+RE[\\\\/]"

    private val patterns = mapOf(
        "VIN" to Regex("\\b(?=[A-HJ-NPR-Z0-9]{17}\\b)(?=[A-Z0-9]*[A-Z])(?=[A-Z0-9]*[0-9])[A-HJ-NPR-Z0-9]{17}\\b"),
        "Wi-Fi or hardware address" to Regex("\\b[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}\\b"),
        "precise coordinates" to Regex("(?<![\\d.])-?\\d{1,3}\\.\\d{4,}\\s*,\\s*-?\\d{1,3}\\.\\d{4,}(?![\\d.])"),
        "path of a developer's computer" to Regex("$windowsHome|$workspace|/home/[a-z][a-z0-9_-]+/|/Users/[A-Za-z]"),
    )

    /** City centres and a made-up point, used as sample places by the tests. */
    private val samplePlaces = setOf("-23.5505, -46.6333", "-10.1234, -50.5678")

    private val binary = setOf("png", "ttf", "jar", "so", "webp", "ogg", "mp3", "keystore", "jks", "bin", "jpg", "jpeg", "gif", "ico", "zip", "gz", "tar")

    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    private fun trackedFiles(root: File): List<File>? = runCatching {
        val out = ProcessBuilder("git", "-C", root.path, "ls-files", "-z").redirectErrorStream(true).start()
        val text = out.inputStream.readBytes().toString(Charsets.UTF_8)
        check(out.waitFor() == 0)
        text.split('\u0000').filter { it.isNotEmpty() }.map { File(root, it) }
    }.getOrNull()

    @Test
    fun `nothing that identifies a rider, a bike or a machine is in the versioned files`() {
        val root = root()
        val files = trackedFiles(root)
        assumeTrue("git is not available here", files != null)
        val text = files!!.filter { it.isFile && it.extension.lowercase() !in binary && !it.path.replace('\\', '/').contains("/aa/proto/") }
        assertTrue("the tracked files were found", text.size > 200)
        val found = text.flatMap { f ->
            val content = runCatching { f.readText() }.getOrNull() ?: return@flatMap emptyList()
            patterns.flatMap { (name, regex) ->
                regex.findAll(content)
                    .filter { it.value !in samplePlaces }
                    .map { "${f.relativeTo(root).path.replace('\\', '/')}:${content.substring(0, it.range.first).count { c -> c == '\n' } + 1} ($name)" }
                    .toList()
            }
        }
        assertEquals("personal-looking text in the versioned files: $found", emptyList<String>(), found)
    }

    @Test
    fun `the guard itself catches what it is for`() {
        assertTrue(patterns.getValue("VIN").containsMatchIn("vin " + "1HGCM" + "82633A004352" + " end"))
        assertTrue(patterns.getValue("Wi-Fi or hardware address").containsMatchIn("bssid " + "4c:11:ae:" + "00:12:ff"))
        assertTrue(patterns.getValue("precise coordinates").containsMatchIn("at -23.56" + "1414, -46.65" + "5881"))
        assertTrue(patterns.getValue("path of a developer's computer").containsMatchIn("C:" + "\\Users\\someone\\x"))
        assertTrue(patterns.getValue("path of a developer's computer").containsMatchIn("/home/" + "someone/x"))
        assertTrue("two decimals are a place name, not a person", !patterns.getValue("precise coordinates").containsMatchIn("-23.55, -46.63"))
    }
}
