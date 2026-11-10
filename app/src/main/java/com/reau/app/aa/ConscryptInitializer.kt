// Copied from OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/ConscryptInitializer.kt, commit 0abbe2a.
// Changes: package; the provider is added at the END of the list, not at position 1 (see initialize); the install is
// ConscryptInstall, which also survives a native library that does not load; the log says the library's version.
// Ported from headunit-revived (AGPLv3): ssl/ConscryptInitializer.kt
package com.reau.app.aa

import java.security.Security

object ConscryptInitializer {
    @Volatile private var initialized = false
    @Volatile private var conscryptAvailable = false

    @Synchronized
    fun initialize(): Boolean {
        if (initialized) return conscryptAvailable
        initialized = true

        // At the end of the list: AapSslContext asks for it by name, and the two trips to the internet (the update check and the
        // weather) keep the platform's TLS. At position 1 every HTTPS connection of the process used the bundled Conscrypt.
        val result = ConscryptInstall.install(
            newProvider = {
                val conscrypt = Class.forName("org.conscrypt.Conscrypt")
                conscrypt.getMethod("newProvider").invoke(null) as java.security.Provider
            },
            addProvider = Security::addProvider,
            isRegistered = { Security.getProvider("Conscrypt") != null },
        )
        when (result) {
            is ConscryptInstall.Result.Installed -> {
                conscryptAvailable = true
                AaLog.i("Conscrypt ${version() ?: "(version unknown)"} installed as security provider (position: ${result.position})")
            }
            is ConscryptInstall.Result.Failed -> {
                conscryptAvailable = false
                val cause = result.cause
                if (cause is ClassNotFoundException) AaLog.e("Conscrypt library not found", cause)
                else AaLog.e("Failed to initialize Conscrypt, the platform's TLS is used: ${cause.javaClass.simpleName}", cause)
            }
        }
        return conscryptAvailable
    }

    /** The bundled library's version, from its own `Conscrypt.version()` (read by reflection, as the rest of it is); null if it cannot be read. */
    internal fun version(): String? = runCatching {
        val v = Class.forName("org.conscrypt.Conscrypt").getMethod("version").invoke(null)
        val (major, minor, patch) = listOf("major", "minor", "patch").map { v.javaClass.getMethod(it).invoke(v) }
        "$major.$minor.$patch"
    }.getOrNull()

    fun isAvailable(): Boolean = conscryptAvailable
    fun getProviderName(): String? = if (conscryptAvailable) "Conscrypt" else null
}
