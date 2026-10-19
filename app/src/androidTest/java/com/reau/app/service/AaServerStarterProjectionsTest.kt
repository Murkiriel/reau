package com.reau.app.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.aa.AaLinkState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The projection turned off and on again in under 2 s. Each
 * projection has its own hooks ([AaServerStarter.hooks], made in LiveSides.forPhone); the old one's end, arriving after
 * the new one's start, must leave the new one's dial and server alone. The switch "Ligar e desligar o servidor" stays
 * off here (the default), so nothing walks Android Auto's menu.
 */
@RunWith(AndroidJUnit4::class)
class AaServerStarterProjectionsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        AaServerStarter.dialSoon = null
    }

    @Test
    fun theOldProjectionsEndLeavesTheNewOnesDial() {
        val old = AaServerStarter.hooks(context)
        val new = AaServerStarter.hooks(context)
        var dialled = ""
        old.started { dialled = "old" }
        old.linkChanged(AaLinkState.ACTIVE)
        new.started { dialled = "new" }
        old.stopped()
        AaServerStarter.dialSoon?.invoke()
        assertEquals("the new projection still dials soon", "new", dialled)
    }

    @Test
    fun theNewProjectionsOwnEndStillClearsItsDial() {
        val old = AaServerStarter.hooks(context)
        val new = AaServerStarter.hooks(context)
        old.started {}
        new.started {}
        old.stopped()
        new.stopped()
        assertNull(AaServerStarter.dialSoon)
    }
}
