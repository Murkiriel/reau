package com.reau.app.dash

import com.reau.app.data.KeystoreBackedPrefsTest.FakePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A guarda das leituras do painel no telefone: um valor por codigo, no formato `hex|hora|vezes`. Uma linha ruim (de uma
 * versao antiga, ou estragada) e deixada de lado em vez de derrubar a tela "Dados da moto".
 */
class DashReadingsStoreTest {
    private val prefs = FakePrefs()
    private val store = DashReadingsStore(prefs)

    @Test
    fun `o que foi guardado volta igual`() {
        val readings = mapOf(
            "0C07" to DashReading("00F0", 1_700_000_000_000L, 12),
            "0B02" to DashReading("01020304", 5L, 1),
        )
        store.save(readings)
        assertEquals(readings, DashReadingsStore(prefs).load())
    }

    @Test
    fun `o formato guardado e hex, hora e vezes separados por barra`() {
        store.save(mapOf("0C07" to DashReading("00F0", 99L, 3)))
        assertEquals("00F0|99|3", prefs.getString("0C07", null))
    }

    @Test
    fun `sem nada guardado a leitura e vazia`() {
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `guardar de novo troca o valor do mesmo codigo`() {
        store.save(mapOf("0C07" to DashReading("00F0", 1L, 1)))
        store.save(mapOf("0C07" to DashReading("0100", 2L, 2)))
        assertEquals(mapOf("0C07" to DashReading("0100", 2L, 2)), store.load())
    }

    @Test
    fun `guardar um codigo nao apaga os outros que ja estavam la`() {
        store.save(mapOf("0C07" to DashReading("00F0", 1L, 1)))
        store.save(mapOf("0B02" to DashReading("AA", 2L, 1)))
        assertEquals(setOf("0C07", "0B02"), store.load().keys)
    }

    @Test
    fun `sem a contagem a leitura vale uma vez`() {
        prefs.edit().putString("0C07", "00F0|123").commit()
        assertEquals(DashReading("00F0", 123L, 1), store.load().getValue("0C07"))
    }

    @Test
    fun `uma contagem estragada vale uma vez, e o resto da linha e aproveitado`() {
        prefs.edit().putString("0C07", "00F0|123|muitas").commit()
        assertEquals(DashReading("00F0", 123L, 1), store.load().getValue("0C07"))
    }

    @Test
    fun `linhas sem hora, com hora estragada ou que nao sao texto ficam de fora`() {
        prefs.edit()
            .putString("SEMHORA", "00F0")
            .putString("HORARUIM", "00F0|ontem|2")
            .putString("VAZIA", "")
            .putInt("NUMERO", 7)
            .putString("0C07", "00F0|123|2")
            .commit()
        assertEquals(setOf("0C07"), store.load().keys)
    }

    @Test
    fun `esquecer a moto apaga tudo, inclusive o que so existia guardado`() {
        store.save(mapOf("0C07" to DashReading("00F0", 1L, 1), "0B02" to DashReading("AA", 2L, 1)))
        store.clear()
        assertTrue(store.load().isEmpty())
        assertFalse(prefs.contains("0C07"))
        assertTrue(prefs.map.isEmpty())
    }

    @Test
    fun `um valor vazio de hex e guardado e volta vazio`() {
        store.save(mapOf("0F04" to DashReading("", 10L, 1)))
        assertEquals(DashReading("", 10L, 1), store.load().getValue("0F04"))
    }
}
