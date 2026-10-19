package com.reau.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A guard on what ships: no API key, bearer token, JSON web token or private key may sit in the app's sources or resources.
 */
class SecretsGuardTest {
    private val markers = listOf("AIza", "Bearer ", "eyJ", "api_key", "apikey", "-----BEGIN", "client_secret")

    private fun mainFiles(): List<File> {
        val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        return root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "xml", "json", "properties", "txt", "gradle", "kts") }.toList()
    }

    @Test
    fun `no key, token or private key in the app's sources and resources`() {
        val files = mainFiles()
        assertTrue("the sources were found", files.size > 50)
        val found = files.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                // A line that only strips the PEM header off a key (SingleKeyKeyManager) holds no key.
                markers.firstOrNull { line.contains(it, ignoreCase = it == "api_key" || it == "apikey") && !(it == "-----BEGIN" && ".replace(" in line) }?.let { "${f.path}:${i + 1} ($it)" }
            }
        }
        assertEquals("secret-looking text in the app: $found", emptyList<String>(), found)
    }
}
