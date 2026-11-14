package com.reau.app.util

import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.Reader

/**
 * Hides, in a log that is about to leave the phone, what ties it to one bike and to one person's networks: the same three things, and the same
 * rules, as `scripts/registro/mascarar.py` (the script that masks the copy of a collection made for sharing), written again here so the zip
 * that "Compartilhar" exports is masked the same way (the owner, 11/11/2026).
 *
 * - The dash's Wi-Fi name, `RE_<4 letters or digits>_<6 digits>`, and any other name that starts the way a dash's does, become [MASK].
 * - Every MAC address (six pairs of hex digits split by colons) becomes [MASK_MAC].
 * - The names of the Wi-Fi networks that are not the dash's (home, work): the name that follows `SSID`, `ssid=`, `configKey=` or `SSID:`, quoted or
 *   not, becomes [MASK_NETWORK]. A name found that way is also hidden everywhere else in the same export (where a line says it without the
 *   keyword), and [extraNames] (the paired dash's own, which may not start with `RE_`) are hidden wherever they stand. A name of fewer than
 *   [MINIMUM_NAME] characters is only hidden after a keyword.
 *
 * It errs on the side of hiding: a name that is also an ordinary word is hidden everywhere too. Only the text logs go through it; the
 * recordings are binary and are not read.
 */
object LogMask {
    const val MASK = "RE_****_******"
    const val MASK_MAC = "**:**:**:**:**:**"
    const val MASK_NETWORK = "<rede>"
    const val MINIMUM_NAME = 3

    private val DASH = Regex("""\bRE_[A-Za-z0-9]{4}_\d{6}\b""")
    private val OTHER = Regex("""\bRE_[A-Za-z0-9_]{4,}""")
    private val MAC = Regex("""\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b""")

    // `SSID: "Name"`, `ssid="Name"`, `configKey="Name"WPA_PSK`, `SSID 'Name'`: the quoted name after the keyword.
    private val QUOTED = Regex("""(?i)\b(ssid|configKey)(\s*[=:]?\s*)(["'])([^"'\n]*)\3""")

    // `SSID: Name, BSSID: …` (dumpsys): unquoted, up to the comma or the end of the line; `ssid=Name bssid=…`: one token.
    private val TO_COMMA = Regex("""(?i)\bSSID(\s*:[ \t]*)(?![ \t]*["'<*])([^,\n]+?)(?=,|\s*$)""")
    private val ONE_TOKEN = Regex("""(?i)\bssid(=)(?!["'<*])([^\s,"']+)""")
    private val NOT_A_NAME = setOf("none", "null", "<unknown ssid>", "0x", "<hidden>")

    private fun isTheDashs(name: String) = name.startsWith("RE_") || name.trim('*', '_').isEmpty()

    /** Whether [line] could hold anything to hide on its own: the cheap look that spares the regexes for most lines. */
    private fun hasKeyword(line: String) = line.contains("ssid", ignoreCase = true) || line.contains("configkey", ignoreCase = true)

    /** The Wi-Fi names that are not the dash's, as [text] says them after a keyword (`SSID: "Casa"` gives `Casa`). */
    fun networkNames(text: String): Set<String> {
        if (!hasKeyword(text)) return emptySet()
        val found = mutableSetOf<String>()
        QUOTED.findAll(text).forEach { found += it.groupValues[4] }
        for (regex in listOf(TO_COMMA, ONE_TOKEN)) regex.findAll(text).forEach { found += it.groupValues[2].trim() }
        return found.filterTo(mutableSetOf()) { n ->
            n.isNotEmpty() && !isTheDashs(n) && n != MASK_NETWORK && !n.all { it.isDigit() } && n.lowercase() !in NOT_A_NAME
        }
    }

    /** [text] with the dash's Wi-Fi names, every MAC and the other networks' names replaced; [names]: more names to hide wherever they stand. */
    fun mask(text: String, names: Collection<String> = emptySet()): String {
        var out = text
        if (out.contains("RE_")) out = OTHER.replace(DASH.replace(out, MASK), MASK)
        if (out.contains(':')) out = MAC.replace(out, MASK_MAC)
        if (hasKeyword(out)) {
            out = QUOTED.replace(out) { m ->
                val name = m.groupValues[4]
                if (isTheDashs(name) || name.isEmpty() || name == MASK_NETWORK) {
                    m.value
                } else {
                    "${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3]}$MASK_NETWORK${m.groupValues[3]}"
                }
            }
            out = TO_COMMA.replace(out) { m -> if (isTheDashs(m.groupValues[2].trim())) m.value else "SSID${m.groupValues[1]}$MASK_NETWORK" }
            out = ONE_TOKEN.replace(out) { m -> if (isTheDashs(m.groupValues[2])) m.value else "ssid=$MASK_NETWORK" }
        }
        for (name in names.filter { it.length >= MINIMUM_NAME }.sortedByDescending { it.length }) {
            if (out.contains(name)) out = Regex("""(?<![\p{L}\p{N}_])${Regex.escape(name)}(?![\p{L}\p{N}_])""").replace(out, MASK_NETWORK)
        }
        return out
    }

    /** The networks' names that the lines of [file] say after a keyword. */
    fun networkNames(file: File): Set<String> {
        val found = mutableSetOf<String>()
        file.inputStream().use { forEachLine(InputStreamReader(it, Charsets.UTF_8)) { line -> found += networkNames(line) } }
        return found
    }

    /** [file] written to [out] masked, line by line (a log reaches tens of MB); [out] is left open. */
    fun write(file: File, out: OutputStream, names: Collection<String>) {
        file.inputStream().use { input ->
            forEachLine(InputStreamReader(input, Charsets.UTF_8)) { line -> out.write(mask(line, names).toByteArray(Charsets.UTF_8)) }
        }
    }

    private const val BUFFER = 8192

    /** The lines of [reader], each with its newline (the last one without, if the text does not end with one). */
    private fun forEachLine(reader: Reader, each: (String) -> Unit) {
        val buffer = CharArray(BUFFER)
        val line = StringBuilder()
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            var start = 0
            for (i in 0 until read) {
                if (buffer[i] == '\n') {
                    line.append(buffer, start, i + 1 - start)
                    each(line.toString())
                    line.setLength(0)
                    start = i + 1
                }
            }
            line.append(buffer, start, read - start)
        }
        if (line.isNotEmpty()) each(line.toString())
    }
}
