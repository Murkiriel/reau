package com.reau.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What keeps the release build reproducible: in an incremental Kotlin compilation the Compose compiler writes other constants (a `$stable` read
 * with `sget`, one `$changed` bit more), and the classes2.dex and classes3.dex of a release built over an old build differ from those of a clean
 * build of the same commit. app/build.gradle.kts declares the sources once more, as an input that is not incremental, of the release and
 * benchmark Kotlin tasks; this test holds that block in place and keeps the debug build, rebuilt all day, out of it.
 */
class ReleaseReproducibleBuildTest {
    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    private fun block(): String {
        val app = File(root(), "app/build.gradle.kts").readText()
        val start = app.indexOf("tasks.configureEach {")
        assertTrue("the tasks.configureEach block that forces a whole compilation of the release is missing", start >= 0)
        return app.substring(start, app.indexOf("android {", start))
    }

    @Test
    fun `the kotlin compilation of the release and of the benchmark build has the sources as a non incremental input`() {
        val block = block()
        assertTrue(block.contains("name.endsWith(\"ReleaseKotlin\")"))
        assertTrue(block.contains("name.endsWith(\"BenchmarkKotlin\")"))
        assertTrue(block.contains("inputs.files(fileTree(\"src\")"))
        assertTrue(block.contains("\"main/**/*.kt\"") && block.contains("\"main/**/*.java\""))
        assertTrue(block.contains("\"release/**/*.kt\"") && block.contains("\"release/**/*.java\""))
        assertTrue(block.contains(".withPropertyName(\"sourcesForAFullCompilation\")"))
        // @Incremental would make the input incremental again and undo the effect.
        assertFalse(block.contains("Incremental"))
    }

    @Test
    fun `the debug build stays incremental`() {
        assertFalse(block().contains("DebugKotlin"))
        val properties = File(root(), "gradle.properties").readText()
        assertFalse("kotlin.incremental=false would take the incremental compilation away from the debug build too", Regex("^\\s*kotlin\\.incremental\\s*=\\s*false", RegexOption.MULTILINE).containsMatchIn(properties))
    }
}
