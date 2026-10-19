// Copied from OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/NoCheckTrustManager.kt, commit 0abbe2a.
// Changes: package only.
// Ported from headunit-revived (AGPLv3): ssl/NoCheckTrustManager.kt
package com.reau.app.aa

import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class NoCheckTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // Accepts every certificate on purpose: the phone's AAP endpoint is self-signed.
    }
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // Accepts every certificate on purpose: the phone's AAP endpoint is self-signed.
    }
    override fun getAcceptedIssuers(): Array<X509Certificate>? = null
}
