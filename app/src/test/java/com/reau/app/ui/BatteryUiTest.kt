package com.reau.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which battery cards the main screen shows. */
class BatteryUiTest {
    @Test
    fun `exempt and with battery saver off, nothing to say`() {
        assertEquals(emptyList<BatteryNotice>(), BatteryUi(saverOn = false, exempt = true).notices)
    }

    @Test
    fun `not exempt asks for the exemption`() {
        assertEquals(listOf(BatteryNotice.NOT_EXEMPT), BatteryUi(saverOn = false, exempt = false).notices)
    }

    @Test
    fun `battery saver on is said even when exempt`() {
        assertEquals(listOf(BatteryNotice.SAVER_ON), BatteryUi(saverOn = true, exempt = true).notices)
    }

    @Test
    fun `both, the exemption first`() {
        assertEquals(listOf(BatteryNotice.NOT_EXEMPT, BatteryNotice.SAVER_ON), BatteryUi(saverOn = true, exempt = false).notices)
    }
}
