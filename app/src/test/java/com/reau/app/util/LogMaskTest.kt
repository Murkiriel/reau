package com.reau.app.util

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The masking of what "Compartilhar" exports: the same rules as `scripts/registro/mascarar.py` (the expected texts below are what that script gives
 * for the same lines), written in Kotlin. Only invented names are used; the MACs and the dash's name are put together while the test runs, so no
 * real-looking one is written in the source.
 */
class LogMaskTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun mac(vararg pairs: String) = pairs.joinToString(":")
    private val macA = mac("aa", "bb", "cc", "dd", "ee", "ff")
    private val macB = mac("12", "34", "56", "78", "9A", "bc")
    private val dash = "RE_" + "AB12" + "_" + "345678"

    // -- the dash's name -------------------------------------------------------------------------------------------------

    @Test
    fun `the dash's Wi-Fi name is hidden, and so is any name that starts the way a dash's does`() {
        assertEquals("connected to RE_****_****** ok", LogMask.mask("connected to $dash ok"))
        assertEquals("odd RE_****_****** and RE_****_******", LogMask.mask("odd RE_XYZW_12 and RE_ABCDEFG1"))
        assertEquals("ssid=\"RE_****_******\"", LogMask.mask("ssid=\"$dash\""))
        assertEquals("twice: RE_****_****** RE_****_******", LogMask.mask("twice: $dash $dash"))
    }

    @Test
    fun `the paired dash's own name is hidden wherever it stands even when it does not start the way a dash's does`() {
        val own = "MotoDeTeste"
        assertEquals("<rede> joined, (<rede>) and MotoDeTesteX stay", LogMask.mask("$own joined, ($own) and ${own}X stay", listOf(own)))
        assertEquals("a name shorter than 3 characters is not hidden loose", "ab says ab", LogMask.mask("ab says ab", listOf("ab")))
    }

    // -- the MACs --------------------------------------------------------------------------------------------------------

    @Test
    fun `every MAC address is hidden, in lower and upper case`() {
        assertEquals(
            "bssid ${LogMask.MASK_MAC} and ${LogMask.MASK_MAC} here",
            LogMask.mask("bssid $macA and ${macA.uppercase()} here"),
        )
        assertEquals("${LogMask.MASK_MAC}/${LogMask.MASK_MAC}", LogMask.mask("$macA/$macB"))
        assertEquals("a clock is not a MAC", "timestamp 12:34:56.789 nothing here", LogMask.mask("timestamp 12:34:56.789 nothing here"))
        assertEquals("five pairs are not a MAC", "aa:bb:cc:dd:ee", LogMask.mask("aa:bb:cc:dd:ee"))
    }

    // -- the other networks' names ----------------------------------------------------------------------------------------

    @Test
    fun `the name after a keyword is hidden, quoted or not`() {
        assertEquals("SSID: \"<rede>\" level -50", LogMask.mask("SSID: \"CasaDoZe\" level -50"))
        assertEquals("ssid=\"<rede>\" x", LogMask.mask("ssid=\"Escritorio Teste\" x"))
        assertEquals("configKey=\"<rede>\"WPA_PSK", LogMask.mask("configKey=\"Cafe Central\"WPA_PSK"))
        assertEquals("WifiInfo SSID: <rede>, BSSID: ${LogMask.MASK_MAC}, RSSI: -40\n", LogMask.mask("WifiInfo SSID: Minha Rede, BSSID: $macA, RSSI: -40\n"))
        assertEquals("ssid=<rede> bssid=${LogMask.MASK_MAC}\n", LogMask.mask("ssid=Vizinha bssid=$macA\n"))
        assertEquals("a name of two characters after the keyword", "ssid=<rede> x", LogMask.mask("ssid=ab x"))
        assertEquals("SSID: <rede>", LogMask.mask("SSID: Net"))
    }

    @Test
    fun `what is already hidden or says nothing is left as it is`() {
        for (same in listOf("SSID: <unknown ssid>", "SSID: \"<rede>\"", "SSID: \"\"", "nothing about networks here", "")) {
            assertEquals(same, LogMask.mask(same))
        }
    }

    @Test
    fun `the keyword is found whatever its case`() {
        assertEquals("Ssid: \"<rede>\"", LogMask.mask("Ssid: \"CasaDoZe\""))
        assertEquals("CONFIGKEY=\"<rede>\"", LogMask.mask("CONFIGKEY=\"CasaDoZe\""))
    }

    @Test
    fun `the names a text says after a keyword, with the dash's, the masks and the empty ones left out`() {
        assertEquals(setOf("CasaDoZe", "Vizinha", "Cafe"), LogMask.networkNames("SSID: \"CasaDoZe\" ssid=Vizinha, configKey=\"Cafe\"WPA"))
        assertEquals(emptySet<String>(), LogMask.networkNames("ssid=\"$dash\" SSID: \"<rede>\" SSID: \"\" SSID: <unknown ssid> ssid=none ssid=<hidden>"))
        assertEquals("a name that is only digits is no network name", emptySet<String>(), LogMask.networkNames("SSID: \"12345\""))
        assertEquals(emptySet<String>(), LogMask.networkNames("no keyword at all"))
    }

    @Test
    fun `a name learned from one line is hidden where another line says it alone, as a whole word only`() {
        val names = LogMask.networkNames("SSID: \"CasaDoZe\"\nssid=Vizinha\n")
        assertEquals(setOf("CasaDoZe", "Vizinha"), names)
        assertEquals(
            "SSID: \"<rede>\"\nlinha solta com <rede> e CasaDoZeX e (<rede>)\nssid=<rede>\nVizinhaa <rede>.\n",
            LogMask.mask("SSID: \"CasaDoZe\"\nlinha solta com CasaDoZe e CasaDoZeX e (CasaDoZe)\nssid=Vizinha\nVizinhaa Vizinha.\n", names).let {
                // line by line, as the export does it
                it
            },
        )
    }

    // -- the files and the zip ---------------------------------------------------------------------------------------------

    @Test
    fun `a file is masked line by line, the newlines kept, and the last line without one too`() {
        val file = tmp.newFile("ride-1.log").apply { writeText("12:00:01 joined $dash at $macA\nSSID: \"CasaDoZe\"\r\n12:00:02 CasaDoZe again\nlast $macB") }
        val names = LogMask.networkNames(file)
        assertEquals(setOf("CasaDoZe"), names)
        val out = ByteArrayOutputStream()
        LogMask.write(file, out, names)
        assertEquals(
            "12:00:01 joined RE_****_****** at ${LogMask.MASK_MAC}\nSSID: \"<rede>\"\r\n12:00:02 <rede> again\nlast ${LogMask.MASK_MAC}",
            out.toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `accented text and a line longer than the read buffer go through whole`() {
        val long = "x".repeat(20_000)
        val file = tmp.newFile("ride-2.log").apply { writeText("ação – você ✓ $dash\n$long $macA $long\n") }
        val out = ByteArrayOutputStream()
        LogMask.write(file, out, emptySet())
        assertEquals("ação – você ✓ RE_****_******\n$long ${LogMask.MASK_MAC} $long\n", out.toString(Charsets.UTF_8))
    }

    private fun entry(zip: File, name: String): String = ZipFile(zip).use { zf -> zf.getInputStream(zf.getEntry(name)).readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `the exported zip has the logs masked, a network named in one log hidden in the others, and the recordings untouched`() {
        val diag = tmp.newFolder("diag")
        File(diag, "ride-1.log").writeText("joined $dash bssid $macA\nSSID: \"CasaDoZe\" level -50\n")
        File(diag, "ride-2.log").writeText("later, CasaDoZe was seen again; ${macB.uppercase()}\n")
        File(diag, "crash-1.txt").writeText("REau crash\nthread: main\nat Foo.bar(Foo.kt:1) $dash\n")
        val video = tmp.newFolder("video").apply { File(this, "stream.h264").writeText("raw $dash $macA binary stands as it is") }
        val dest = File(tmp.root, "out.zip")

        val count = DiagnosticsExport.zipDirs(listOf("" to diag, "video" to video), dest, header = "REau diagnostics report\n")

        assertEquals(5, count)
        assertEquals("REau diagnostics report\n", entry(dest, DiagnosticsExport.HEADER_ENTRY))
        assertEquals("joined RE_****_****** bssid ${LogMask.MASK_MAC}\nSSID: \"<rede>\" level -50\n", entry(dest, "ride-1.log"))
        assertEquals("later, <rede> was seen again; ${LogMask.MASK_MAC}\n", entry(dest, "ride-2.log"))
        assertEquals("REau crash\nthread: main\nat Foo.bar(Foo.kt:1) RE_****_******\n", entry(dest, "crash-1.txt"))
        assertEquals("raw $dash $macA binary stands as it is", entry(dest, "video/stream.h264"))
    }

    @Test
    fun `the export hides the paired dash's name given by the caller, and masking can be turned off for a raw copy`() {
        val diag = tmp.newFolder("diag2")
        File(diag, "ride-1.log").writeText("the dash MotoDeTeste answered\n")
        val masked = File(tmp.root, "masked.zip")
        DiagnosticsExport.zipDirs(listOf("" to diag), masked, maskNames = listOf("MotoDeTeste"))
        assertEquals("the dash <rede> answered\n", entry(masked, "ride-1.log"))
        val raw = File(tmp.root, "raw.zip")
        DiagnosticsExport.zipDirs(listOf("" to diag), raw, mask = false, maskNames = listOf("MotoDeTeste"))
        assertEquals("the dash MotoDeTeste answered\n", entry(raw, "ride-1.log"))
    }

    @Test
    fun `export, the path the share button takes, writes a masked zip and keeps the names of the caller`() {
        val diag = tmp.newFolder("diag3")
        File(diag, "ride-1.log").writeText("MotoDeTeste $dash $macA SSID: \"CasaDoZe\"\n")
        val out = tmp.newFolder("shared")
        val zip = DiagnosticsExport.export(listOf("" to diag), out, freeBytes = { Long.MAX_VALUE }, extras = DiagnosticsExport.ExportExtras(maskNames = listOf("MotoDeTeste")))!!
        val text = entry(zip, "ride-1.log")
        assertEquals("<rede> RE_****_****** ${LogMask.MASK_MAC} SSID: \"<rede>\"\n", text)
        for (secret in listOf("MotoDeTeste", dash, macA, "CasaDoZe")) assertFalse(secret, text.contains(secret))
    }

    @Test
    fun `the share button's path asks the paired dash's name and the export masks by default`() {
        val source = File("src/main/java/com/reau/app/util/DiagnosticsExport.kt").readText()
        assertTrue(source.contains("DashPairing.open(context).ssid"))
        assertTrue(source.contains("ExportExtras(reportHeader(app, read.getOrNull(), readable = read.isSuccess), listOf(dashName))"))
        assertTrue("masking is on unless a caller turns it off", source.contains("mask: Boolean = true"))
        assertTrue("the zip of the share goes through zipDirs with the names", source.contains("zipDirs(sources, part, header, maskNames = extras.maskNames)"))
    }
}
