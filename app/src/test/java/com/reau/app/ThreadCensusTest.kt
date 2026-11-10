package com.reau.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The census of the app's threads, read from the sources of `main`: every `Thread(`, `thread(`, `HandlerThread(` and executor
 * (`Executors.new...`, `ThreadPoolExecutor(`) is named, and is in [APPROVED] with the file, the name and why it is a thread of its own.
 * A new one fails here until it is listed; the point is not to forbid threads but to make a new concurrency model a decision someone
 * writes down (a thread without a name is `pool-N-thread-1` in an ANR dump and a stack trace). Where a name comes from the caller
 * (a parameter), the list holds the parameter's name and the callers name their threads.
 */
class ThreadCensusTest {
    private class Entry(val file: String, val name: String, val why: String)

    private val approved = listOf(
        Entry("ScreenTheme.kt", "sun-place", "learns the sun's place off the main thread"),
        Entry("REauApp.kt", "folders", "creates the app's folders at start"),
        Entry("REauApp.kt", "dash-readings", "loads the saved dash readings at start"),
        Entry("REauApp.kt", "dash-pairing", "warms the pairing key at start"),
        Entry("MainActivity.kt", "log-age", "trims the log by age after a setting change"),
        Entry("MainActivity.kt", "dash-name", "learns the dash's name, off the main thread"),
        Entry("MainActivity.kt", "delete-logs", "deletes the logs, off the main thread"),
        Entry("aa/AapTransport.kt", "AapTransport:Handler::Send", "Android Auto link, sends (HandlerThread the transport needs)"),
        Entry("aa/AapTransport.kt", "AapTransport:Handler::Poll", "Android Auto link, polls USB/Wi-Fi reads"),
        Entry("aa/VideoDecoder.kt", "AaVideoDecoder-Output", "drains the decoder's output, blocking"),
        Entry("aa/AaMicrophone.kt", "aa-mic", "blocking microphone reads"),
        Entry("aa/AaVideoPipe.kt", "aa-video", "feeds the decoder, blocking"),
        Entry("aa/AaReceiver.kt", "aa-link", "the Android Auto receive loop, blocking"),
        Entry("aa/AaReceiver.kt", "mark-freeze", "writes the freeze marks off the receive loop"),
        Entry("aa/AaReceiver.kt", "aa-stats", "the statistics line, on a timer"),
        Entry("aa/AaAudioPlayer.kt", "aa-audio-", "one pump per audio stream, blocking AudioTrack writes (the stream's name follows)"),
        Entry("web/ExecutorDelayer.kt", "web-delayer", "the web viewer's delays"),
        Entry("web/WebServer.kt", "web-accept", "the web viewer's accept loop, blocking"),
        Entry("web/WebServer.kt", "web-connection", "one per web connection, blocking"),
        Entry("web/WebViewer.kt", "web-viewer-writer", "writes to a viewer, blocking"),
        Entry("web/WebViewers.kt", "web-viewers-told", "tells the listeners of the viewer count outside the stream's locks"),
        Entry("stream/VideoLoop.kt", "video-loop", "the frame poller; timing-sensitive"),
        Entry("dash/DashWifiSuggestions.kt", "wifi-suggestion", "syncs the Wi-Fi suggestion off the main thread"),
        Entry("dash/video/EncoderOutputPump.kt", "DashEncoderOut", "drains the encoder's output, blocking"),
        Entry("dash/DashSession.kt", "DashGoodbye", "the goodbye packets, off the main thread (bounded wait)"),
        Entry("dash/DashReadings.kt", "dash-readings-save", "saves the readings off the caller"),
        Entry("service/ProjectionService.kt", "projection", "the single executor every projection event goes through"),
        Entry("service/BackgroundRelease.kt", "name", "named by the caller (projection-release)"),
        Entry("util/BoundedRelease.kt", "threadName", "named by the caller, default AaDecoderRelease"),
        Entry("video/DashCompositor.kt", "dash-compositor", "the GL thread; every GL call is on it"),
        Entry("data/RideDiagnostics.kt", "diag-writer", "the log's single writer, in submission order"),
        Entry("data/RideDiagnostics.kt", "diag-tidy", "the log folder's tidying"),
        Entry("data/LogcatPump.kt", "own-logcat", "reads the app's own logcat, blocking"),
        Entry("data/OwnLogcat.kt", "own-logcat-flush", "flushes the own-logcat file on a timer"),
        Entry("data/OwnLogcat.kt", "logcat-tidy", "the own-logcat folder's tidying"),
        Entry("util/DiagnosticsExport.kt", "share-logs", "zips the logs to share, off the main thread"),
    )

    private val main: File = listOf(File("src"), File("app/src")).first { File(it, "main").isDirectory }.let { File(it, "main/java/com/reau/app") }

    private class Site(val file: String, val line: Int, val kind: String, val name: String?)

    private val threadSite = Regex("""(?<![\w.])Thread\s*[({]|(?<![\w.])thread\(|kotlin\.concurrent\.thread\(|HandlerThread\(""")
    private val executorSite = Regex("""Executors\.new\w+|\w*ThreadPoolExecutor\(|asCoroutineDispatcher|newSingleThreadContext|newFixedThreadPoolContext|ForkJoinPool\(|(?<![\w.])Timer\(""")

    /** The start of another declaration: a `Thread(` after it is not this executor's factory. */
    private val nextDeclaration = Regex("""\n\s*(private |internal |override |@Volatile )*(val|var|fun)\s""")

    /** The source without comments (line and block), the lines kept where they were so a site's line number is true. */
    private fun code(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)) { m -> m.value.filter { it == '\n' } }
            .replace(Regex("(?m)(^|\\s)//.*$")) { m -> m.groupValues[1] }

    /** The text at depth 1 of the parentheses that open at [open] (nested brackets dropped, string literals kept). */
    private fun topLevel(text: String, open: Int): String {
        val sb = StringBuilder()
        var depth = 0
        var i = open
        while (i < text.length) {
            val c = text[i]
            if (c == '"') {
                val start = i++
                while (i < text.length && text[i] != '"') { if (text[i] == '\\') i++; i++ }
                if (depth == 1) sb.append(text, start, minOf(i + 1, text.length))
            } else if (c == '(' || c == '{' || c == '[') {
                depth++
            } else if (c == ')' || c == '}' || c == ']') {
                if (--depth == 0) break
            } else if (depth == 1) {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /**
     * The arguments of the call that opens at [open]: for `Thread(...)` the top-level text; for `Thread { ... }` (no arguments) the name given after the
     * block, in `.apply { name = "x" }`, written as an argument so [nameOf] reads it the same way.
     */
    private fun argsAt(text: String, open: Int): String {
        if (text[open] == '(') return topLevel(text, open)
        var depth = 0
        var end = open
        while (end < text.length) {
            if (text[end] == '{') depth++ else if (text[end] == '}' && --depth == 0) break
            end++
        }
        val after = Regex("""^\s*\.apply\s*\{[^}]*?\bname\s*=\s*"([^"]*)"""").find(text.substring(minOf(end + 1, text.length)))
        return after?.let { "name = \"${it.groupValues[1]}\"" } ?: ""
    }

    /** What the thread is called, or null when the call gives no name. */
    private fun nameOf(args: String, fileText: String): String? {
        val raw = Regex("""\bname\s*=\s*("([^"]*)"|\w+)""").find(args)?.let { it.groupValues[2].ifEmpty { it.groupValues[1] } }
            ?: Regex(""""([^"]*)"""").findAll(args).lastOrNull()?.groupValues?.get(1)
            ?: args.takeIf { it.count { c -> c == ',' } >= 1 }?.let { Regex("""\w+""").findAll(it).lastOrNull()?.value }
            ?: return null
        val resolved = Regex("""val\s+${Regex.escape(raw)}\s*=\s*"([^"]*)"""").find(fileText)?.groupValues?.get(1) ?: raw
        return resolved.substringBefore('$')
    }

    private fun sites(): List<Site> = main.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.sortedBy { it.path }
        .flatMap { f ->
            val rel = f.relativeTo(main).path
            val text = code(f.readText())
            fun lineAt(i: Int) = text.substring(0, i).count { it == '\n' } + 1
            val threads = threadSite.findAll(text).map { m ->
                Site(rel, lineAt(m.range.first), "thread", nameOf(argsAt(text, m.range.last), text)) to m.range.first
            }.toList()
            val executors = executorSite.findAll(text).map { m ->
                // An executor is named through its factory: a Thread( site right inside its call.
                val factory = threads.firstOrNull { (_, at) ->
                    at in m.range.first..m.range.first + FACTORY_REACH && !nextDeclaration.containsMatchIn(text.substring(m.range.first, at))
                }
                Site(rel, lineAt(m.range.first), "executor", if (factory == null) null else "factory")
            }.toList()
            threads.map { it.first } + executors
        }.toList()

    @Test
    fun `every thread and executor of the app has a name`() {
        val unnamed = sites().filter { it.name == null }
        assertTrue(
            "Created without a name (it would be pool-N-thread-1 in an ANR dump): " +
                unnamed.joinToString { "${it.file}:${it.line} (${it.kind})" } +
                ". Give it a name (a ThreadFactory { Thread(it, \"name\") } for an executor) and list it in ThreadCensusTest.",
            unnamed.isEmpty(),
        )
    }

    @Test
    fun `every named thread is in the approved list, and the list holds nothing that is gone`() {
        val found = sites().filter { it.kind == "thread" && it.name != null }.map { it.file to it.name!! }
        val allowed = approved.map { it.file to it.name }
        val extra = found.toMutableList().apply { allowed.forEach { remove(it) } }
        val gone = allowed.toMutableList().apply { found.forEach { remove(it) } }
        assertTrue(
            "A thread, executor or HandlerThread is created that ThreadCensusTest does not know: " +
                extra.joinToString { "${it.first} \"${it.second}\"" } +
                ". Reuse an existing model if it fits (the service's `projection` executor, Dispatchers.IO); if the new thread is needed, " +
                "add an Entry(file, name, why it is a thread of its own) to the approved list of ThreadCensusTest.",
            extra.isEmpty(),
        )
        assertTrue(
            "ThreadCensusTest lists threads that are no longer created: " + gone.joinToString { "${it.first} \"${it.second}\"" } +
                ". Remove their entries from the approved list.",
            gone.isEmpty(),
        )
    }

    @Test
    fun `the census sees the threads it is meant to see`() {
        // Guards the scan itself: if the sources moved and it found nothing, the two tests above would pass for the wrong reason.
        assertTrue("found only ${sites().size} sites", sites().size >= 30)
    }

    private companion object {
        /** How far after `Executors.newX` its factory's `Thread(` may start. */
        const val FACTORY_REACH = 400
    }
}
