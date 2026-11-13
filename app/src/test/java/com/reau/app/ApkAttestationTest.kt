package com.reau.app

import com.reau.app.update.SigningCheck
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attestation of the APK that the GitHub workflow builds (13/11/2026): the action is pinned by the full commit, only the job that builds the
 * APK may ask for `id-token: write` and `attestations: write` (the rest of the workflow only reads the code), it runs on a push and not on a pull
 * request, and the workflow still publishes nothing. The site says nothing about the attestation (the Releases are signed on the maintainer's computer, so it would not cover them): the install page checks the APK by the fingerprint of the signature, in the three languages.
 */
class ApkAttestationTest {
    private val root = listOf(File(".."), File(".")).first { File(it, "gradle/wrapper").isDirectory }
    private val workflow = File(root, ".github/workflows/ci.yml").readText()

    /** The lines of the job [name] (from its header to the next job). */
    private fun job(name: String): String {
        val lines = workflow.lines()
        val start = lines.indexOf("  $name:")
        assertTrue("job $name", start >= 0)
        val end = (start + 1 until lines.size).firstOrNull { Regex("""^  [a-z][a-z-]*:\s*$""").matches(lines[it]) } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    @Test
    fun `every action of the workflow is pinned by a full commit, the attestation one included`() {
        val uses = Regex("""^\s*(?:-\s*)?uses:\s*(\S+)""", RegexOption.MULTILINE).findAll(workflow).map { it.groupValues[1] }.toList()
        assertTrue("the attestation action is used: $uses", uses.any { it.startsWith("actions/attest-build-provenance@") })
        for (action in uses) assertTrue("$action is not pinned by a 40-digit commit", Regex("""[\w./-]+@[0-9a-f]{40}""").matches(action))
        assertTrue("its version is in the comment", Regex("""attest-build-provenance@[0-9a-f]{40} # v\d+\.\d+\.\d+""").containsMatchIn(workflow))
    }

    @Test
    fun `only the job that builds the APK asks for the two permissions, and the rest of the workflow only reads`() {
        assertEquals("id-token: write once in the whole file", 1, Regex("""id-token:\s*write""").findAll(workflow).count())
        assertEquals("attestations: write once in the whole file", 1, Regex("""attestations:\s*write""").findAll(workflow).count())
        val audit = job("auditoria-apk")
        assertTrue(audit, Regex("""permissions:\s*\n\s+contents:\s*read\s*\n\s+id-token:\s*write\s*\n\s+attestations:\s*write""").containsMatchIn(audit))
        val top = workflow.substringBefore("\njobs:")
        assertTrue("the top permissions only read: $top", Regex("""(?m)^permissions:\s*\n  contents: read\s*\n""").containsMatchIn(top))
        assertFalse("no write at the top", Regex("""(?m)^permissions:\s*\n(  .*\n)*?  \S+: write""").containsMatchIn(top))
        assertFalse("no other write permission", Regex("""(contents|packages|actions|pull-requests|issues|deployments): write""").containsMatchIn(workflow))
    }

    @Test
    fun `the attestation is of the release APK, only on a push, after the audit`() {
        val audit = job("auditoria-apk")
        val step = audit.substringAfter("- name: Atestar a proveniência do APK")
        assertTrue("on a push only: $step", Regex("""^\s+if: github\.event_name == 'push'""", RegexOption.MULTILINE).containsMatchIn(step.substringBefore("\n\n")))
        assertTrue(step, "subject-path: app/build/outputs/apk/release/app-release.apk" in step)
        assertTrue("after the audit", audit.indexOf("scripts/apk_audit.py app/build/outputs/apk/release/app-release.apk") < audit.indexOf("Atestar a proveniência"))
    }

    @Test
    fun `the workflow still publishes nothing`() {
        for (forbidden in listOf("gh release", "action-gh-release", "create-release", "gh-pages", "upload-release-asset", "push-to-registry: true")) {
            assertFalse(forbidden, forbidden in workflow)
        }
    }

    @Test
    fun `the install page checks the APK by the signature's fingerprint in the three languages, and promises nothing about the attestation`() {
        val official = "63f5c3ba687699b0b4288a878bd8372bed2c9632222d7f673ea71073aa208970"
        assertEquals("the page says what the app compares with", official, SigningCheck.EXPECTED_SHA256)
        for (language in listOf("pt", "en", "es")) {
            val page = File(root, "docs/$language/install.html").readText()
            val section = page.substringAfter("id=\"conferir-apk\"").substringBefore("id=\"migracao\"")
            assertTrue("$language: the section", "id=\"conferir-apk\"" in page && section.isNotBlank())
            assertTrue("$language: the whole fingerprint of the project's key", "<code>$official</code>" in section)
            assertTrue("$language: the command for the computer", "apksigner verify --print-certs" in section)
            assertTrue("$language: where the app shows it", Regex("Check for updates|Verificar atualização|Buscar actualizaciones").containsMatchIn(section))
            assertFalse("$language: nothing about the attestation on the site", Regex("attestation|atestação|atestación").containsMatchIn(page))
            assertFalse("$language: no gh attestation command", "gh attestation" in page)
        }
    }
}
