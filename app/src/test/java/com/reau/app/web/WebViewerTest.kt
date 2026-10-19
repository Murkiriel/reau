package com.reau.app.web

import java.io.OutputStream
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A browser that closes its connection before the viewer's writer thread starts: the socket can no longer give its output
 * stream. The writer ends the viewer; it must not die with an exception nobody handles (on Android, that ends the app).
 */
class WebViewerTest {
    @Test
    fun `a socket that cannot give its output stream ends the viewer and leaves no exception behind`() {
        val uncaught = AtomicReference<Throwable?>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.set(error) }
        try {
            val gone = CountDownLatch(1)
            val closedEarly = object : Socket() {
                override fun getOutputStream(): OutputStream = throw SocketException("Socket is closed")
            }
            WebViewer(closedEarly, queueFrames = 4) { gone.countDown() }.startWriter()
            assertTrue("the viewer was never told gone: the writer thread died first (${uncaught.get()})", gone.await(5, TimeUnit.SECONDS))
            assertNull(uncaught.get())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
