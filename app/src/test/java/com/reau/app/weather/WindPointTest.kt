package com.reau.app.weather

import org.junit.Assert.assertEquals
import org.junit.Test

/** The wind's direction as one of the sixteen points of the compass; the names are in the resources, one list for each language. */
class WindPointTest {
    @Test
    fun `north is the first point and each 22 and a half degrees the next`() {
        assertEquals(0, windPointIndex(0))
        assertEquals(1, windPointIndex(22))
        assertEquals(4, windPointIndex(90))
        assertEquals(8, windPointIndex(180))
        assertEquals(12, windPointIndex(270))
        assertEquals(15, windPointIndex(340))
    }

    @Test
    fun `a full turn and a negative angle come back to the same point`() {
        assertEquals(0, windPointIndex(360))
        assertEquals(0, windPointIndex(355))
        assertEquals(windPointIndex(300), windPointIndex(-60))
    }
}
