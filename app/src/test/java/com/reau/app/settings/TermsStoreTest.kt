package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A versao dos termos que o piloto aceitou, guardada no telefone: zero ate ele aceitar, e uma versao nova pede o aceite de novo. */
class TermsStoreTest {
    private val prefs = FakePrefs()

    @Test
    fun `antes de aceitar a versao e zero e os termos pedem aceite`() {
        val store = TermsStore(prefs)
        assertEquals(0, store.accepted())
        assertTrue(Terms.needsAcceptance(store.accepted()))
    }

    @Test
    fun `aceitar guarda a versao atual e o aceite deixa de ser pedido`() {
        val store = TermsStore(prefs)
        store.accept()
        assertEquals(Terms.VERSION, store.accepted())
        assertFalse(Terms.needsAcceptance(store.accepted()))
    }

    @Test
    fun `o aceite sobrevive a um novo objeto sobre as mesmas preferencias`() {
        TermsStore(prefs).accept()
        assertEquals(Terms.VERSION, TermsStore(prefs).accepted())
    }

    @Test
    fun `aceitar uma versao diz qual foi, e uma versao nova pede aceite de novo`() {
        val store = TermsStore(prefs)
        store.accept(version = Terms.VERSION - 1)
        assertEquals(Terms.VERSION - 1, store.accepted())
        assertTrue(Terms.needsAcceptance(store.accepted()))
    }

    @Test
    fun `uma versao mais nova que a atual (de um app mais novo) tambem vale como aceita`() {
        val store = TermsStore(prefs)
        store.accept(version = Terms.VERSION + 1)
        assertFalse(Terms.needsAcceptance(store.accepted()))
    }

    @Test
    fun `um aceite novo troca o antigo, sem acumular`() {
        val store = TermsStore(prefs)
        store.accept(version = 1)
        store.accept(version = 3)
        assertEquals(3, store.accepted())
        assertEquals(1, prefs.map.size)
    }
}
