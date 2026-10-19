package com.reau.app.ui

import com.reau.app.aa.AaLinkState
import com.reau.app.aa.ServerPolicy
import com.reau.app.service.Headline
import com.reau.app.service.Phase
import com.reau.app.service.Projection
import com.reau.app.service.ProjectionNotification
import com.reau.app.ui.MainLook.Warning
import com.reau.app.ui.components.StatusKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Android Auto 17.3 or older asked to dial in and never did: what the rider is told (the Lite edition's state). */
class AndroidAutoNotCallingTest {
    private val projection = Projection(phase = Phase.RUNNING, androidAuto = AaLinkState.NOT_CALLING)

    @Test
    fun `it is the headline of the projection, in the colour of an error, with a warning of its own`() {
        assertEquals(Headline.ANDROID_AUTO_NOT_CALLING, projection.headline)
        assertEquals(StatusKind.ERROR, MainLook.androidAutoKind(projection))
        assertEquals(listOf(Warning.NOT_CALLING), MainLook.warnings(projection, BatteryUi()))
    }

    @Test
    fun `each place that words a headline words this one, and not as the server being off`() {
        assertNotEquals(ScreenText.headline(Headline.ANDROID_AUTO_SERVER_OFF), ScreenText.headline(Headline.ANDROID_AUTO_NOT_CALLING))
        assertNotEquals(ScreenText.androidAuto(AaLinkState.SERVER_OFF), ScreenText.androidAuto(AaLinkState.NOT_CALLING))
        assertNotEquals(
            ProjectionNotification.contentFor(Headline.ANDROID_AUTO_SERVER_OFF),
            ProjectionNotification.contentFor(Headline.ANDROID_AUTO_NOT_CALLING),
        )
        assertNotEquals(ProjectionNotification.colorFor(Headline.CONNECTING), ProjectionNotification.colorFor(Headline.ANDROID_AUTO_NOT_CALLING))
    }

    @Test
    fun `the head unit server's policy does nothing about it`() {
        val policy = ServerPolicy().apply { started() }
        assertEquals(ServerPolicy.Act.NOTHING, policy.state(AaLinkState.NOT_CALLING))
    }
}
