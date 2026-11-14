package com.reau.app.service

import android.content.pm.ServiceInfo
import com.reau.app.aa.AaLinkState
import com.reau.app.service.ProjectionService.Companion.ForegroundNeeds
import com.reau.app.service.ProjectionService.Companion.TapNote
import com.reau.app.settings.REauSettings
import com.reau.app.weather.RingData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the projection service decides on each command, with no Android service (the service itself is ProjectionEndToEndTest's, on a device). */
class ProjectionServiceRulesTest {
    @Test
    fun `a Desligar on a projection that is on begins a stop, and a Ligar after it, before it ends, waits behind it`() {
        assertEquals(TapNote.STOP_TAPPED, ProjectionService.tapNote(ProjectionService.ACTION_STOP, Phase.RUNNING, 0L))
        assertEquals(TapNote.STOP_TAPPED, ProjectionService.tapNote(ProjectionService.ACTION_STOP, Phase.PREPARING, 0L))
        assertEquals("nothing on: nothing to stop", TapNote.NONE, ProjectionService.tapNote(ProjectionService.ACTION_STOP, Phase.STOPPED, 0L))
        assertEquals(TapNote.LIGAR_DURING_STOP, ProjectionService.tapNote(ProjectionService.ACTION_START, Phase.RUNNING, 12_345L))
        assertEquals(TapNote.LIGAR_DURING_STOP, ProjectionService.tapNote(ProjectionService.ACTION_START, Phase.STOPPED, 12_345L))
        assertEquals("a Ligar with no stop going on", TapNote.NONE, ProjectionService.tapNote(ProjectionService.ACTION_START, Phase.STOPPED, 0L))
        assertEquals(TapNote.NONE, ProjectionService.tapNote(ProjectionService.ACTION_REFIT, Phase.RUNNING, 12_345L))
        assertEquals(TapNote.NONE, ProjectionService.tapNote(null, Phase.RUNNING, 12_345L))
    }

    @Test
    fun `Gemini asks for the microphone type only with the switch on and the permission to record`() {
        val on = REauSettings(gemini = true)
        assertTrue(ProjectionService.foregroundNeeds(on, recordAudioGranted = true, fineLocationGranted = false).microphone)
        assertFalse("no permission", ProjectionService.foregroundNeeds(on, recordAudioGranted = false, fineLocationGranted = true).microphone)
        assertFalse("switch off", ProjectionService.foregroundNeeds(REauSettings(gemini = false), true, true).microphone)
    }

    @Test
    fun `the location type is asked for with the precise permission, whatever the ring, the GPS card or the location switch`() {
        val ringOff = REauSettings(infoRing = false) // the default of the single app
        assertTrue("ring off (the default)", ProjectionService.foregroundNeeds(ringOff, recordAudioGranted = false, fineLocationGranted = true).location)
        assertTrue(
            "'Usar a localização' off",
            ProjectionService.foregroundNeeds(ringOff.copy(useLocation = false), true, fineLocationGranted = true).location,
        )
        val all = REauSettings(infoRing = true, useLocation = true, infoRingData = RingData.ALL)
        assertTrue(ProjectionService.foregroundNeeds(all, true, true).location)
        assertTrue(
            "GPS card unticked",
            ProjectionService.foregroundNeeds(all.copy(infoRingData = RingData.ALL and RingData.GPS.bit.inv()), true, true).location,
        )
        assertFalse("no permission", ProjectionService.foregroundNeeds(all, true, fineLocationGranted = false).location)
        assertFalse("no permission, ring off", ProjectionService.foregroundNeeds(ringOff, true, fineLocationGranted = false).location)
    }

    @Test
    fun `the location type does not make the GPS card read, that still needs the ring, the switch, the card and the permission`() {
        val all = REauSettings(infoRing = true, useLocation = true, infoRingData = RingData.ALL)
        assertTrue(ProjectionService.foregroundNeeds(all, true, true).gps)
        assertFalse("ring off", ProjectionService.foregroundNeeds(all.copy(infoRing = false), true, true).gps)
        assertFalse("'Usar a localização' off", ProjectionService.foregroundNeeds(all.copy(useLocation = false), true, true).gps)
        assertFalse("GPS card unticked", ProjectionService.foregroundNeeds(all.copy(infoRingData = RingData.ALL and RingData.GPS.bit.inv()), true, true).gps)
        assertFalse("no permission", ProjectionService.foregroundNeeds(all, true, fineLocationGranted = false).gps)
    }

    @Test
    fun `the two needs are independent`() {
        val both = REauSettings(gemini = true, infoRing = true, useLocation = true, infoRingData = RingData.ALL)
        val needs = ProjectionService.foregroundNeeds(both, recordAudioGranted = true, fineLocationGranted = true)
        assertTrue(needs.microphone && needs.location)
        val noMic = ProjectionService.foregroundNeeds(REauSettings(gemini = false, infoRing = false), true, true)
        assertFalse(noMic.microphone)
        assertTrue("the Wi-Fi list still needs the location type", noMic.location)
        val none = ProjectionService.foregroundNeeds(REauSettings(gemini = false, infoRing = false), false, false)
        assertFalse(none.microphone || none.location || none.gps)
    }

    @Test
    fun `the log says the types Android accepted and why the location one is there`() {
        val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val loc = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        val wifiOnly = ForegroundNeeds(microphone = false, location = true)
        assertEquals(
            listOf(
                "projection: foreground types accepted: connectedDevice|location (0x${Integer.toHexString(device or loc)})",
                "projection: foreground with the location, for the dash's Wi-Fi list",
            ),
            ProjectionService.foregroundNotes(device or loc, wifiOnly, fineLocationGranted = true),
        )
        val all = ForegroundNeeds(microphone = true, location = true, gps = true)
        assertEquals(
            listOf(
                "projection: foreground types accepted: connectedDevice|microphone|location (0x${Integer.toHexString(device or mic or loc)})",
                "projection: foreground with the microphone, for Gemini",
                "projection: foreground with the location, for the dash's Wi-Fi list and the info ring's GPS",
            ),
            ProjectionService.foregroundNotes(device or mic or loc, all, fineLocationGranted = true),
        )
    }

    @Test
    fun `the log warns that the Wi-Fi list will be empty behind another app when the location type is not there`() {
        val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val refused = ProjectionService.foregroundNotes(device, ForegroundNeeds(microphone = false, location = true), true)
        assertEquals("projection: foreground types accepted: connectedDevice (0x${Integer.toHexString(device)})", refused[0])
        assertEquals(
            "W: projection: the location type was not accepted: the Wi-Fi list will be empty while REau is behind another app",
            refused[1],
        )
        val noPermission = ProjectionService.foregroundNotes(device, ForegroundNeeds(microphone = false, location = false), false)
        assertEquals(
            "W: projection: no precise location permission: the Wi-Fi list will be empty while REau is behind another app",
            noPermission[1],
        )
        assertEquals("nothing else", 2, refused.size)
    }

    @Test
    fun `a start builds its sides only when the projection is stopped and the command says which kind`() {
        assertTrue(ProjectionService.buildsSides(sidesFor = false, phase = Phase.STOPPED))
        assertTrue(ProjectionService.buildsSides(sidesFor = true, phase = Phase.STOPPED))
        assertFalse("a start on a running one keeps its sides", ProjectionService.buildsSides(false, Phase.RUNNING))
        assertFalse(ProjectionService.buildsSides(false, Phase.PREPARING))
        assertFalse("a stop brings no kind of sides", ProjectionService.buildsSides(null, Phase.STOPPED))
    }

    @Test
    fun `a mark needs a projection that is on and a refit one that is running`() {
        assertTrue(ProjectionService.markApplies(Phase.RUNNING))
        assertTrue(ProjectionService.markApplies(Phase.PREPARING))
        assertFalse(ProjectionService.markApplies(Phase.STOPPED))
        assertTrue(ProjectionService.refitApplies(Phase.RUNNING))
        assertFalse("not while preparing", ProjectionService.refitApplies(Phase.PREPARING))
        assertFalse(ProjectionService.refitApplies(Phase.STOPPED))
    }

    @Test
    fun `only a drop of Android Auto's link counts for the summary`() {
        assertTrue(ProjectionService.isAaDrop(ProjectionEvent.AndroidAutoChanged(AaLinkState.DROPPED)))
        assertFalse(ProjectionService.isAaDrop(ProjectionEvent.AndroidAutoChanged(AaLinkState.ACTIVE)))
        assertFalse(ProjectionService.isAaDrop(ProjectionEvent.AndroidAutoChanged(AaLinkState.UNRESPONSIVE)))
        assertFalse(ProjectionService.isAaDrop(ProjectionEvent.Start))
        assertFalse(ProjectionService.isAaDrop(ProjectionEvent.Stop))
    }
}
