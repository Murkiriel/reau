package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Test

class TlsSummaryTest {
    @Test
    fun theLineSaysProtocolCipherProviderAndTheSizeOfTheFirstFlight() {
        assertEquals(
            "SSL handshake negotiated: protocol=TLSv1.2 cipher=TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256 provider=Conscrypt client-hello=312 B",
            TlsSummary.line("Conscrypt", "TLSv1.2", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", 312),
        )
    }

    @Test
    fun whatIsNotKnownIsAQuestionMarkNotANumberThatLooksReal() {
        assertEquals(
            "SSL handshake negotiated: protocol=? cipher=? provider=? client-hello=?",
            TlsSummary.line(null, null, null, 0),
        )
    }
}
