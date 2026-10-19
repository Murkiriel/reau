// Copied from OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/AapMessageHandler.kt, commit 0abbe2a.
// Changes: package only.
// Ported from headunit-revived (AGPLv3): aap/AapMessageHandler.kt
package com.reau.app.aa

internal interface AapMessageHandler {
    @Throws(HandleException::class)
    fun handle(message: AapMessage)

    class HandleException internal constructor(cause: Throwable) : Exception(cause)
}
