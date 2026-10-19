package com.reau.app.data

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The ride log's synchronous write: a crashing process has no time for the log's
 * background writer, so [RideDiagnostics.logAndWait] returns only once the line, and everything logged before it,
 * is in the file.
 */
class RideDiagnosticsWaitTest {
    @get:Rule val tmp = TemporaryFolder()

    @After
    fun tearDown() = RideDiagnostics.stop("test")

    @Test
    fun `logAndWait returns with the line and the ones before it already in the file`() {
        val file = tmp.newFile("ride-test.log")
        RideDiagnostics.testOnlySetFile(file)
        repeat(200) { RideDiagnostics.log("test", "line $it") }
        // 200 appends, each reopening the file, take longer than the default wait on a slow disk: the wait is a bound.
        RideDiagnostics.logAndWait("error", "the last words", timeoutMs = 20_000)
        val text = file.readText()
        assertTrue("all the earlier lines: ${text.lines().size}", text.contains("line 199"))
        assertTrue("and the last one: ${text.takeLast(200)}", text.contains("[error] the last words"))
    }
}
