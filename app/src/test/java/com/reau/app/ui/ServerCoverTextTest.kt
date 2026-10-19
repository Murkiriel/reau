package com.reau.app.ui

import com.reau.app.R
import com.reau.app.aa.ServerMenuRun
import com.reau.app.aa.ServerResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cover over Android Auto's menu used to say "Ligando o servidor…" while switching it off
 * too. The cover and the main screen's status say what the walk does: switching on, or switching off.
 */
class ServerCoverTextTest {
    @Test
    fun `the cover says switching on or switching off, by the walk`() {
        assertEquals(R.string.aa_server_cover, ScreenText.cover(ServerMenuRun.Mode.START))
        assertEquals(R.string.aa_server_cover_stopping, ScreenText.cover(ServerMenuRun.Mode.STOP))
    }

    @Test
    fun `a walk going on is told by its mode, and the main screen says it`() {
        assertEquals(ServerResult.WORKING, ServerResult.working(ServerMenuRun.Mode.START))
        assertEquals(ServerResult.STOPPING, ServerResult.working(ServerMenuRun.Mode.STOP))
        assertEquals(R.string.aa_server_working, ScreenText.server(ServerResult.WORKING))
        assertEquals(R.string.aa_server_stopping, ScreenText.server(ServerResult.STOPPING))
    }
}
