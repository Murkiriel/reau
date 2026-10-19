package com.reau.app.aa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutoDialsInTest {
    @Test
    fun `17 3 and older dial in`() {
        assertTrue(AndroidAuto.dialsIn("17.3.662814-release"))
        assertTrue(AndroidAuto.dialsIn("17.0.1"))
        assertTrue(AndroidAuto.dialsIn("16.9.123456-release"))
        assertTrue(AndroidAuto.dialsIn("9.3.131347"))
    }

    @Test
    fun `17 4 and newer are dialled by REau`() {
        assertFalse(AndroidAuto.dialsIn("17.4.663000-release"))
        assertFalse(AndroidAuto.dialsIn("17.9.664004-release"))
        assertFalse(AndroidAuto.dialsIn("18.0.1"))
        assertFalse(AndroidAuto.dialsIn("17.10.1"))
    }

    @Test
    fun `a missing or unreadable version is taken as a newer one`() {
        assertFalse(AndroidAuto.dialsIn(null))
        assertFalse(AndroidAuto.dialsIn(""))
        assertFalse(AndroidAuto.dialsIn("stub"))
    }
}
