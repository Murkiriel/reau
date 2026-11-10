package com.reau.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's baseline profile is in the build and names code that exists: a rule for a package that was renamed would compile nothing. */
class BaselineProfileTest {
    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    private val rules by lazy {
        File(root(), "app/src/main/baseline-prof.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    @Test
    fun `the profile has rules and every one is a flag, a class and a method pattern`() {
        assertTrue("no rules", rules.size >= 10)
        val shape = Regex("^[HSP]+L[A-Za-z0-9_/$*]+;?->[A-Za-z0-9_$*<>]+\\(\\*\\*\\)\\*\\*$")
        for (rule in rules) assertTrue("not a profile rule: $rule", shape.matches(rule))
    }

    @Test
    fun `every class or package a rule names is in the source`() {
        val sources = listOf("app/src/main/java").map { File(root(), it) }.filter { it.isDirectory }
        assertTrue("no source folder", sources.isNotEmpty())
        for (rule in rules) {
            val path = rule.substringAfter('L').substringBefore(';').substringBefore("->").removeSuffix("**").removeSuffix("/")
            val base = path.substringBefore('*')
            val found = sources.any { dir ->
                File(dir, base).isDirectory || File(dir, "$base.kt").isFile || File(dir, base.removeSuffix("Kt") + ".kt").isFile ||
                    File(dir, base.substringBeforeLast('/')).listFiles().orEmpty().any { it.name.startsWith(base.substringAfterLast('/')) }
            }
            assertTrue("a rule for code that is not there: $rule", found)
        }
    }

    @Test
    fun `the release can be profiled from the shell`() {
        val manifest = File(root(), "app/src/main/AndroidManifest.xml").readText()
        assertTrue(Regex("<profileable[^>]*android:shell=\"true\"").containsMatchIn(manifest))
    }
}
