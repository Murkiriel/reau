package com.reau.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No picture, video or subtitle sits in docs/ without a page (or the style sheet, or the script) naming it: on 13/11/2026 twelve of them
 * (the screenshots of the removed accessibility service and the video of the old Completa edition) were found with no reference anywhere and
 * moved out of the repository. The ones that are left are all used; a new file that nothing uses fails here instead of piling up.
 */
class SiteOrphansTest {
    private val root = listOf(File(".."), File(".")).first { File(it, "gradle/wrapper").isDirectory }
    private val docs = File(root, "docs")

    @Test
    fun `every picture, video and subtitle of the site is named by a page, the style sheet or a script`() {
        val readers = docs.walkTopDown().filter { it.isFile && it.extension in setOf("html", "css", "js", "xml", "txt") }.toList()
        val text = readers.joinToString("\n") { it.readText() }
        val media = listOf(File(docs, "assets/img"), File(docs, "videos")).flatMap { it.listFiles { f -> f.isFile }!!.toList() }
        assertTrue("there are files to check", media.size > 20 && readers.size > 10)
        val orphans = media.map { it.name }.filter { it !in text }.sorted()
        assertTrue("no page names: $orphans (use them or move them out of docs/, nothing is deleted)", orphans.isEmpty())
    }
}
