package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine

class TlsGroupsTest {
    @Test
    fun theGroupsAreTheThreeThatConscrypt253Offered() {
        assertEquals(listOf("X25519", "P-256", "P-384"), TlsGroups.CLASSIC)
    }

    @Test
    fun anEngineWithNoSuchCallNeedsNothing() {
        val engine = SSLContext.getDefault().createSSLEngine()
        assertNull("the JDK's engine has no setNamedGroups", TlsGroups.setterOf(engine))
        assertEquals(TlsGroups.Outcome.NotNeeded, TlsGroups.restrict(null))
    }

    /** Like Conscrypt's: a class that is not public, with a public method. */
    private class HiddenEngine(private val seen: MutableList<List<String>>) : SSLEngineStub() {
        @Suppress("unused") // found by reflection
        fun setNamedGroups(groups: Array<String>) {
            seen += groups.toList()
        }
    }

    @Test
    fun theCallOfAClassThatIsNotPublicIsReachedByReflection() {
        val seen = mutableListOf<List<String>>()
        val setter = TlsGroups.setterOf(HiddenEngine(seen))
        assertNotNull(setter)
        assertEquals(TlsGroups.Outcome.Restricted(TlsGroups.CLASSIC), TlsGroups.restrict(setter))
        assertEquals(listOf(TlsGroups.CLASSIC), seen)
    }

    @Test
    fun aCallThatFailsIsAFailureWithItsRealCauseNotACrash() {
        val broken = IllegalArgumentException("No valid known group found")
        val outcome = TlsGroups.restrict({ throw java.lang.reflect.InvocationTargetException(broken) })
        assertEquals(TlsGroups.Outcome.Failed(broken), outcome)
        assertTrue(TlsGroups.restrict({ throw NoClassDefFoundError("x") }) is TlsGroups.Outcome.Failed)
    }

    @Test
    fun theLineSaysWhatHappenedInEachCase() {
        assertEquals("TLS groups set to X25519,P-256,P-384", TlsGroups.line(TlsGroups.Outcome.Restricted(TlsGroups.CLASSIC)))
        assertTrue(TlsGroups.line(TlsGroups.Outcome.NotNeeded).contains("classic"))
        assertTrue(TlsGroups.line(TlsGroups.Outcome.Failed(IllegalStateException())).contains("IllegalStateException"))
    }
}

/** An SSLEngine with nothing in it, for the tests that only look at one method of a subclass. */
open class SSLEngineStub : SSLEngine() {
    override fun getSupportedCipherSuites() = emptyArray<String>()
    override fun getEnabledCipherSuites() = emptyArray<String>()
    override fun setEnabledCipherSuites(suites: Array<String>?) = Unit
    override fun getSupportedProtocols() = emptyArray<String>()
    override fun getEnabledProtocols() = emptyArray<String>()
    override fun setEnabledProtocols(protocols: Array<String>?) = Unit
    override fun getSession(): javax.net.ssl.SSLSession? = null
    override fun beginHandshake() = Unit
    override fun getHandshakeStatus() = javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
    override fun setUseClientMode(mode: Boolean) = Unit
    override fun getUseClientMode() = true
    override fun setNeedClientAuth(need: Boolean) = Unit
    override fun getNeedClientAuth() = false
    override fun setWantClientAuth(want: Boolean) = Unit
    override fun getWantClientAuth() = false
    override fun setEnableSessionCreation(flag: Boolean) = Unit
    override fun getEnableSessionCreation() = true
    override fun closeInbound() = Unit
    override fun isInboundDone() = false
    override fun closeOutbound() = Unit
    override fun isOutboundDone() = false
    override fun getDelegatedTask(): Runnable? = null
    override fun wrap(srcs: Array<out java.nio.ByteBuffer>?, offset: Int, length: Int, dst: java.nio.ByteBuffer?) = throw UnsupportedOperationException()
    override fun unwrap(src: java.nio.ByteBuffer?, dsts: Array<out java.nio.ByteBuffer>?, offset: Int, length: Int) = throw UnsupportedOperationException()
}
