package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.security.Provider

class ConscryptInstallTest {
    private class FakeProvider : Provider("Fake", 1.0, "test provider")

    private fun install(
        newProvider: () -> Provider = { FakeProvider() },
        addProvider: (Provider) -> Int = { 7 },
        isRegistered: () -> Boolean = { false },
    ) = ConscryptInstall.install(newProvider, addProvider, isRegistered)

    @Test
    fun aProviderThatIsAddedIsInstalledAtTheAnsweredPosition() {
        assertEquals(ConscryptInstall.Result.Installed(7), install())
    }

    @Test
    fun aProviderAlreadyThereCountsAsInstalled() {
        assertEquals(ConscryptInstall.Result.Installed(-1), install(addProvider = { -1 }, isRegistered = { true }))
    }

    @Test
    fun aProviderNotAddedAndNotThereIsAFailure() {
        assertTrue(install(addProvider = { -1 }, isRegistered = { false }) is ConscryptInstall.Result.Failed)
    }

    @Test
    fun aLibraryThatIsNotThereIsAFailureWithItsOwnCause() {
        val missing = ClassNotFoundException("org.conscrypt.Conscrypt")
        assertSame(missing, (install(newProvider = { throw missing }) as ConscryptInstall.Result.Failed).cause)
    }

    @Test
    fun aNativeLibraryThatDoesNotLoadIsAFailureNotACrash() {
        val broken = UnsatisfiedLinkError("dlopen failed: program alignment (4096) cannot be smaller than system page size (16384)")
        assertSame(broken, (install(newProvider = { throw broken }) as ConscryptInstall.Result.Failed).cause)
    }

    @Test
    fun aClassThatCannotBeInitialisedIsAFailureNotACrash() {
        val broken = NoClassDefFoundError("org/conscrypt/NativeCrypto")
        assertSame(broken, (install(addProvider = { throw broken }) as ConscryptInstall.Result.Failed).cause)
    }

    @Test
    fun theReflectionWrapperIsTakenOffToShowTheRealCause() {
        val inner = UnsatisfiedLinkError("dlopen failed")
        val result = install(newProvider = { throw InvocationTargetException(inner) }) as ConscryptInstall.Result.Failed
        assertSame(inner, result.cause)
    }
}
