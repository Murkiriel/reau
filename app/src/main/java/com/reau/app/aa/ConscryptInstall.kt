package com.reau.app.aa

import java.lang.reflect.InvocationTargetException
import java.security.Provider

/**
 * The part of [ConscryptInitializer] that can fail, with its dependencies handed in so a JVM test can make each one fail.
 *
 * Installing the bundled Conscrypt loads a native library (`libconscrypt_jni.so`). A library that cannot be loaded (a page size or a CPU
 * it was not built for) is an [Error], not an [Exception]: it must not end the process, it only means "no Conscrypt", and the
 * handshake then runs on the platform's TLS.
 */
internal object ConscryptInstall {
    sealed interface Result {
        /** The provider is registered; [position] is what [addProvider] answered (-1: it was already there). */
        data class Installed(val position: Int) : Result

        /** Why it was not: the real cause, with the reflection wrapper taken off. */
        data class Failed(val cause: Throwable) : Result
    }

    @Suppress("TooGenericExceptionCaught") // anything that goes wrong here only means "no Conscrypt"
    fun install(newProvider: () -> Provider, addProvider: (Provider) -> Int, isRegistered: () -> Boolean): Result = try {
        val position = addProvider(newProvider())
        if (position != -1 || isRegistered()) Result.Installed(position) else Result.Failed(IllegalStateException("the provider was not registered"))
    } catch (e: InvocationTargetException) {
        Result.Failed(e.cause ?: e)
    } catch (e: Exception) {
        Result.Failed(e)
    } catch (e: LinkageError) {
        Result.Failed(e)
    }
}
