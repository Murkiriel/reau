package com.reau.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * What the build files promise and nothing else tests: the dependency check by PGP signature keeps its sha256 entries and trusts only keys that are in the exported
 * keyring, the screens read the state flows with the lifecycle, and the tools that only run on a device (the macrobenchmark module, the coverage) are there but
 * never in the release.
 */
class BuildSetupTest {
    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    private fun text(path: String) = File(root(), path).readText()

    private val metadata by lazy {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(root(), "gradle/verification-metadata.xml"))
        doc.documentElement
    }

    private fun Element.children(name: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == name }

    private fun Element.child(name: String): Element? = children(name).firstOrNull()

    @Test
    fun `the dependency check verifies the metadata and the signatures`() {
        val configuration = metadata.child("configuration")!!
        assertEquals("true", configuration.child("verify-metadata")!!.textContent)
        assertEquals("true", configuration.child("verify-signatures")!!.textContent)
        // The keys come from the keyring in the repository only: no build asks a key server (CONTRIBUTING, "A new dependency and the PGP check").
        assertEquals("false", configuration.child("key-servers")!!.getAttribute("enabled"))
    }

    @Test
    fun `every trusted key is in the exported keyring`() {
        val keyring = text("gradle/verification-keyring.keys")
        assertTrue("the keyring has no key block", keyring.contains("-----BEGIN PGP PUBLIC KEY BLOCK-----"))
        // The signing key may be a subkey: the keyring lists the long id of the main key (pub) and of each subkey (sub).
        val listed = Regex("^(?:pub|sub)\\s+([0-9A-F]{16})", RegexOption.MULTILINE).findAll(keyring).map { it.groupValues[1] }.toSet()
        val trusted = metadata.child("configuration")!!.child("trusted-keys")!!.children("trusted-key").map { it.getAttribute("id") }
        assertTrue("no trusted key", trusted.size > 20)
        for (id in trusted) assertTrue("trusted key $id is not in verification-keyring.keys", id.takeLast(16) in listed)
    }

    @Test
    fun `no ignored key hides an artifact that has no sha256`() {
        val ignoredKeys = metadata.child("configuration")!!.child("ignored-keys")?.children("ignored-key").orEmpty()
        for (key in ignoredKeys) assertTrue("an ignored key with no reason: ${key.getAttribute("id")}", key.getAttribute("reason").isNotBlank())
        for (component in metadata.child("components")!!.children("component")) {
            for (artifact in component.children("artifact")) {
                // Gradle writes an artifact's ignored keys inside <ignored-keys>.
                val ignored = artifact.children("ignored-keys").flatMap { it.children("ignored-key") }
                if (ignored.isEmpty()) continue
                assertTrue(
                    "${artifact.getAttribute("name")} has an ignored key and no sha256, so nothing checks it",
                    artifact.children("sha256").isNotEmpty(),
                )
            }
        }
    }

    @Test
    fun `every artifact is checked by a sha256 or by a signature of a trusted key`() {
        val components = metadata.child("components")!!.children("component")
        val artifacts = components.sumOf { it.children("artifact").size }
        val withSha = components.sumOf { c -> c.children("artifact").count { it.children("sha256").isNotEmpty() } }
        assertTrue("fewer than 900 artifacts: the sha256 entries were dropped", artifacts > 900 && withSha > 900)
    }

    @Test
    fun `the lifecycle of the compose screens is the lifecycle already pinned`() {
        val catalog = text("gradle/libs.versions.toml")
        assertTrue(catalog.contains("lifecycleRuntimeKtx = \"2.6.1\""))
        assertTrue(
            catalog.contains(
                "androidx-lifecycle-runtime-compose = { group = \"androidx.lifecycle\", name = \"lifecycle-runtime-compose\", version.ref = \"lifecycleRuntimeKtx\" }",
            ),
        )
        assertTrue(text("app/build.gradle.kts").contains("implementation(libs.androidx.lifecycle.runtime.compose)"))
    }

    @Test
    fun `the screens of the main code read the state flows with the lifecycle`() {
        val plain = Regex("\\bcollectAsState\\(")
        val offenders = File(root(), "app/src/main/java").walkTopDown().filter { it.extension == "kt" && plain.containsMatchIn(it.readText()) }.toList()
        assertTrue("collectAsState without the lifecycle in: ${offenders.map { it.name }}", offenders.isEmpty())
        assertTrue(text("app/src/main/java/com/reau/app/MainActivity.kt").contains("ProjectionService.projection.collectAsStateWithLifecycle()"))
        // The one that is imported is the lifecycle's, not another function of the same name.
        val call = Regex("\\bcollectAsStateWithLifecycle\\(")
        val import = "import androidx.lifecycle.compose.collectAsStateWithLifecycle"
        val wrong = File(root(), "app/src/main/java").walkTopDown().filter { it.extension == "kt" }
            .filter { f -> f.readText().let { call.containsMatchIn(it) && !it.contains(import) } }.toList()
        assertTrue("collectAsStateWithLifecycle is not the lifecycle's in: ${wrong.map { it.name }}", wrong.isEmpty())
    }

    @Test
    fun `the coverage is set for the debug variant and leaves the generated classes out, the protobuf ones too`() {
        val app = text("app/build.gradle.kts")
        assertTrue(app.contains("alias(libs.plugins.kover)"))
        assertTrue(app.contains("classes(\"*.R\", \"*.R\\\$*\", \"*.BuildConfig\", \"*ComposableSingletons*\", \"com.reau.app.aa.proto.*\")"))
        assertTrue(text("gradle/libs.versions.toml").contains("kover = { id = \"org.jetbrains.kotlinx.kover\", version.ref = \"kover\" }"))
    }

    @Test
    fun `the macrobenchmark module measures the app's start and writes a profile, and the release does not carry it`() {
        val module = text("macrobenchmark/build.gradle.kts")
        assertTrue(module.contains("targetProjectPath = \":app\""))
        assertTrue(module.contains("alias(libs.plugins.android.test)"))
        assertTrue(text("settings.gradle.kts").contains("include(\":macrobenchmark\")"))
        val startup = text("macrobenchmark/src/main/java/com/reau/app/macrobenchmark/StartupBenchmark.kt")
        assertTrue(startup.contains("StartupTimingMetric()"))
        assertTrue(startup.contains("StartupMode.COLD"))
        assertTrue(startup.contains("CompilationMode.None()"))
        assertTrue(startup.contains("BaselineProfileMode.Require"))
        assertTrue(startup.contains("TARGET_PACKAGE = \"com.reau.app\""))
        assertTrue(text("macrobenchmark/src/main/java/com/reau/app/macrobenchmark/BaselineProfileGenerator.kt").contains("BaselineProfileRule()"))
        // Only the app's "benchmark" build type depends on it; nothing of the benchmark is in the main code or in the release's.
        val app = text("app/build.gradle.kts")
        assertFalse(app.contains("benchmark-macro"))
        assertFalse(text("app/src/main/AndroidManifest.xml").contains("com.reau.app.macrobenchmark"))
        assertTrue(app.contains("create(\"benchmark\")"))
        assertTrue(app.contains("matchingFallbacks += listOf(\"release\")"))
    }

    @Test
    fun `the devices Gradle manages are declared and leave the virtual devices that exist alone`() {
        val app = text("app/build.gradle.kts")
        assertTrue(app.contains("localDevices.create(\"pixel6Api34\")"))
        assertTrue(app.contains("systemImageSource = \"aosp-atd\""))
        assertTrue(text("macrobenchmark/build.gradle.kts").contains("localDevices.create(\"pixel6Api34\")"))
        assertFalse(app.contains("reau_test"))
    }

    @Test
    fun `R8 stays off and the target SDK did not move`() {
        val app = text("app/build.gradle.kts")
        assertTrue(Regex("release \\{\\s+optimization \\{\\s+enable = false").containsMatchIn(app))
        assertTrue(app.contains("targetSdk = 36"))
    }
}
