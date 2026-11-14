package com.reau.app.aa

import javax.net.ssl.SSLEngine

/**
 * Keeps the head unit's first TLS flight the size it always was.
 *
 * Conscrypt 2.6 offers a post-quantum key exchange group by default (X25519Kyber768Draft00, and from 2.7 X25519MLKEM768): the
 * ClientHello of the Android Auto handshake went from 237 to 1455 bytes (measured with the fake Android Auto, both editions, 30/10/2026).
 * Nobody tested Android Auto's own server with that, and the protocol has no use for it (the link is the phone's own loopback or its Wi-Fi to
 * a dash). So the groups are set to the three that 2.5.3 offered, [CLASSIC]: the only difference between the versions left is the library.
 *
 * The call is the engine's own `setNamedGroups` (Conscrypt 2.6.1 and later; it is not in 2.5.3, where nothing needs to be done), reached by reflection:
 * the engine class is not public and `SSLParameters.setNamedGroups` is not on Android before API 37.
 */
internal object TlsGroups {
    val CLASSIC = listOf("X25519", "P-256", "P-384")

    sealed interface Outcome {
        /** The groups are set to [groups]. */
        data class Restricted(val groups: List<String>) : Outcome

        /** This Conscrypt has no such call (2.5.3): its default groups are already the classic ones. */
        data object NotNeeded : Outcome

        /** The call exists and failed: the first flight keeps the library's default groups. */
        data class Failed(val cause: Throwable) : Outcome
    }

    /** What [engine] can be told: its `setNamedGroups`, or null if it has none. */
    fun setterOf(engine: SSLEngine): ((Array<String>) -> Unit)? {
        val method = try {
            engine.javaClass.getDeclaredMethod("setNamedGroups", Array<String>::class.java)
        } catch (_: NoSuchMethodException) {
            return null
        }
        method.isAccessible = true
        return { groups -> method.invoke(engine, groups) }
    }

    @Suppress("TooGenericExceptionCaught") // a failure here only costs the bigger ClientHello
    fun restrict(setter: ((Array<String>) -> Unit)?, groups: List<String> = CLASSIC): Outcome {
        if (setter == null) return Outcome.NotNeeded
        return try {
            setter(groups.toTypedArray())
            Outcome.Restricted(groups)
        } catch (e: Exception) {
            Outcome.Failed((e as? java.lang.reflect.InvocationTargetException)?.cause ?: e)
        } catch (e: LinkageError) {
            Outcome.Failed(e)
        }
    }

    fun line(outcome: Outcome): String = when (outcome) {
        is Outcome.Restricted -> "TLS groups set to ${outcome.groups.joinToString(",")}"
        Outcome.NotNeeded -> "TLS groups: this Conscrypt offers the classic ones by itself"
        is Outcome.Failed -> "TLS groups could not be set (${outcome.cause.javaClass.simpleName}): the library's defaults are offered"
    }
}
