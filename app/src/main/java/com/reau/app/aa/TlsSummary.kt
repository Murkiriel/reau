package com.reau.app.aa

/**
 * The one line in the ride log that says what the TLS handshake with Android Auto came to: the protocol and cipher it
 * negotiated, the provider that did it and the size of the head unit's first flight (the ClientHello, which changes
 * with the Conscrypt version). It is what to compare before and after a change of the TLS library.
 */
internal object TlsSummary {
    fun line(provider: String?, protocol: String?, cipher: String?, clientHelloBytes: Int): String =
        "SSL handshake negotiated: protocol=${protocol ?: "?"} cipher=${cipher ?: "?"} provider=${provider ?: "?"} " +
            "client-hello=${if (clientHelloBytes > 0) "$clientHelloBytes B" else "?"}"
}
