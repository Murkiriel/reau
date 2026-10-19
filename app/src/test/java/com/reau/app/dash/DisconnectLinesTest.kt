package com.reau.app.dash

import com.reau.app.data.RideDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch

/**
 * The projection's stop and its release both end the dash session and the bike's Wi-Fi, so every
 * stop left `disconnect() — explicit` and `disconnect requested` twice in the log. Only a disconnect that has something
 * to let go is told.
 */
class DisconnectLinesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = CountDownLatch(1)

    @After
    fun tearDown() {
        gate.countDown()
        scope.cancel()
        RideDiagnostics.testOnlyReset()
    }

    private fun told(file: java.io.File) = file.readLines().count { "disconnect() — explicit, not a link drop" in it }

    @Test
    fun `a session that was never connected says nothing when it is disconnected`() {
        val file = tmp.newFile("ride-idle.log")
        RideDiagnostics.testOnlySetFile(file)
        val session = DashSession(scope, socketFactory = { throw java.net.BindException("unused") })
        session.disconnect()
        RideDiagnostics.logAndWait("ui", "end")
        assertEquals(file.readLines().toString(), 0, told(file))
    }

    @Test
    fun `a connected session says it once, and the second disconnect right after says nothing`() {
        val file = tmp.newFile("ride-twice.log")
        RideDiagnostics.testOnlySetFile(file)
        val session = DashSession(scope, socketFactory = {
            gate.await()
            throw java.net.BindException("held closed by the test")
        })
        session.connect("RE_TEST_1234")
        session.disconnect() // the projection's stop
        session.disconnect() // its release
        RideDiagnostics.logAndWait("ui", "end")
        assertEquals(file.readLines().toString(), 1, told(file))
    }

    @Test
    fun `the Wi-Fi lets go of something only with a want, a request or a state`() {
        val idle = WifiConnStatus.IDLE
        assertFalse(disconnectHasSomethingToLetGo(wantConnected = false, hasRequest = false, status = idle))
        assertTrue(disconnectHasSomethingToLetGo(wantConnected = true, hasRequest = false, status = idle))
        assertTrue(disconnectHasSomethingToLetGo(wantConnected = false, hasRequest = true, status = idle))
        for (status in WifiConnStatus.entries - idle) {
            assertTrue("$status", disconnectHasSomethingToLetGo(wantConnected = false, hasRequest = false, status = status))
        }
    }
}
