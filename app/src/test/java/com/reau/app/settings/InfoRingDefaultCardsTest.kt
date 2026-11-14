package com.reau.app.settings

import com.reau.app.data.KeystoreBackedPrefsTest
import com.reau.app.weather.RingData
import com.reau.app.weather.RingKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The info ring starts with three cards, the rain, the wind and the thunder (the owner's decision, 12/11/2026). The others stay in the code and are
 * one tick each away (the checkboxes of "O que o anel diz"). A phone that kept "every card" (the old default) goes to the new default
 * once; any narrower choice, and everything saved afterwards, is left as the rider made it.
 */
class InfoRingDefaultCardsTest {
    private fun prefs(vararg saved: Pair<String, Int>, other: Boolean = true) = KeystoreBackedPrefsTest.FakePrefs().also { p ->
        p.edit().apply {
            // Something else saved before, so the store is not new.
            if (other) putInt("density", 200)
            for ((key, value) in saved) putInt(key, value)
        }.apply()
    }

    @Test
    fun `a new setting says the rain, the wind and the thunder and nothing else`() {
        assertEquals(RingData.RAIN.bit or RingData.WIND.bit or RingData.STORM.bit, RingData.DEFAULT)
        assertEquals(7, RingData.DEFAULT)
        assertEquals(RingData.DEFAULT, REauSettings().infoRingData)
        for (data in RingData.entries) {
            assertEquals("$data at first", data == RingData.RAIN || data == RingData.WIND || data == RingData.STORM, RingData.shown(REauSettings().infoRingData, data))
        }
    }

    @Test
    fun `every page of the rain, of the wind and of the thunder is on at first and every other page is off`() {
        val on = RingKind.entries.filter { RingData.shown(RingData.DEFAULT, RingData.of(it)) }.toSet()
        assertEquals(
            setOf(RingKind.RAIN_SOON, RingKind.RAIN_NOW, RingKind.WIND_DANGER, RingKind.WIND_ALERT, RingKind.WIND_ATTENTION, RingKind.THUNDER), on,
        )
        for (kind in listOf(RingKind.UV, RingKind.AIR, RingKind.SUN, RingKind.SKY, RingKind.TEMPS, RingKind.BATTERY, RingKind.LINK, RingKind.GPS)) {
            assertFalse("$kind is off at first", kind in on)
        }
    }

    @Test
    fun `the other cards are not gone, every kind can still be ticked and the default is held to the bits there are`() {
        assertEquals(2047, RingData.ALL)
        assertTrue(RingData.DEFAULT and RingData.ALL.inv() == 0)
        val every = REauSettings(infoRingData = RingData.ALL).sanitized()
        assertEquals(RingData.ALL, every.infoRingData)
        for (data in RingData.entries) assertTrue(RingData.shown(every.infoRingData, data))
    }

    @Test
    fun `a store with nothing saved reads the new default`() {
        assertEquals(RingData.DEFAULT, SettingsStore(KeystoreBackedPrefsTest.FakePrefs()).load().infoRingData)
    }

    @Test
    fun `a phone that kept every card, the old default, goes to the new default once`() {
        for (old in listOf(RingData.ALL, RingData.BEFORE_PHONE_CARDS)) {
            val p = prefs("info_ring_data" to old)
            assertEquals("kept $old", RingData.DEFAULT, SettingsStore(p).load().infoRingData)
            assertEquals("and it is written, not only read", RingData.DEFAULT, p.getInt("info_ring_data", -1))
            assertTrue("and the move is marked as made, so it is never made again", p.getBoolean("info_ring_cards_20261112", false))
        }
    }

    @Test
    fun `a narrower choice of the rider is left as it was`() {
        for (own in listOf(0, RingData.UV.bit, RingData.AIR.bit or RingData.SUN.bit, 0b1010, RingData.ALL and RingData.GPS.bit.inv())) {
            assertEquals("choice $own", own, SettingsStore(prefs("info_ring_data" to own)).load().infoRingData)
        }
    }

    @Test
    fun `a phone with settings saved but no choice for the cards gets the new default`() {
        assertEquals(RingData.DEFAULT, SettingsStore(prefs()).load().infoRingData)
    }

    @Test
    fun `the move is made once, so a rider who ticks every card again keeps them`() {
        val p = prefs("info_ring_data" to RingData.ALL)
        val first = SettingsStore(p).load()
        assertEquals(RingData.DEFAULT, first.infoRingData)
        // The rider ticks every card; saving carries the mark, and the next start does not take them back.
        SettingsStore(p).save(first.copy(infoRingData = RingData.ALL))
        assertEquals(RingData.ALL, SettingsStore(p).load().infoRingData)
        assertEquals(RingData.ALL, SettingsStore(p).load().infoRingData)
    }

    @Test
    fun `a phone that started with the new default and saved keeps a later every card too`() {
        val p = KeystoreBackedPrefsTest.FakePrefs()
        val store = SettingsStore(p)
        store.save(store.load().copy(infoRingData = RingData.ALL))
        assertEquals(RingData.ALL, SettingsStore(p).load().infoRingData)
    }

    @Test
    fun `the choice of the cards is saved and read as it is`() {
        val p = KeystoreBackedPrefsTest.FakePrefs()
        SettingsStore(p).save(REauSettings(infoRingData = RingData.RAIN.bit or RingData.UV.bit))
        assertEquals(RingData.RAIN.bit or RingData.UV.bit, SettingsStore(p).load().infoRingData)
    }
}
