package com.reau.app

import android.content.BroadcastReceiver
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.reau.app.dash.DashFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search for the dash's Wi-Fi name that runs while REau is on screen, driven by its lifecycle with no device (Android's Wi-Fi is
 * [DashSearch.Scans], recorded here): what each [DashFinder.Step] asks for, that the lists of networks are listened to once and only while
 * the name is being searched for, and that a list that arrives after the name became known is not read.
 */
class DashSearchTest {
    /** The scans of Android, kept: which receivers were put on, how many were taken off, how many scans were asked for. */
    private class RecordingScans : DashSearch.Scans {
        val registered = mutableListOf<BroadcastReceiver>()
        var unregistered = 0
        var scans = 0

        override fun listen(receiver: BroadcastReceiver) {
            registered += receiver
        }

        override fun unlisten(receiver: BroadcastReceiver) {
            unregistered++
        }

        override fun scan() {
            scans++
        }
    }

    private val owner = object : LifecycleOwner {
        override val lifecycle: Lifecycle get() = error("DashSearch does not read the owner")
    }

    private class Rig(var step: DashFinder.Step) {
        val scans = RecordingScans()
        var asked = 0
        var learned = 0
        val search = DashSearch(scans, step = { step }, ask = { asked++ }, learn = { learned++ })

        /** A new list of networks arrives, to every receiver that is listening. */
        fun listArrives() = scans.registered.forEach { it.onReceive(android.content.ContextWrapper(null), Intent()) }
    }

    @Test
    fun `with the name known nothing is asked and nothing listened to`() {
        val rig = Rig(DashFinder.Step.KNOWN)
        rig.search.onResume(owner)
        assertEquals(0, rig.asked)
        assertTrue(rig.scans.registered.isEmpty())
        assertEquals(0, rig.scans.scans)
    }

    @Test
    fun `when the name cannot be searched for nothing is asked either`() {
        val rig = Rig(DashFinder.Step.CANNOT)
        rig.search.onResume(owner)
        assertEquals(0, rig.asked)
        assertTrue(rig.scans.registered.isEmpty())
    }

    @Test
    fun `without the Wi-Fi list allowed Android is asked once and no list is listened to`() {
        val rig = Rig(DashFinder.Step.ASK)
        rig.search.onResume(owner)
        assertEquals(1, rig.asked)
        assertTrue(rig.scans.registered.isEmpty())
        assertEquals(0, rig.scans.scans)
    }

    @Test
    fun `searching listens to the lists once, however many times the screen comes back`() {
        val rig = Rig(DashFinder.Step.SEARCH)
        rig.search.onResume(owner)
        rig.search.onResume(owner)
        assertEquals(1, rig.scans.registered.size)
        assertEquals("the search does not ask for the location", 0, rig.asked)
    }

    @Test
    fun `each time the screen comes on while searching a scan is asked for`() {
        val rig = Rig(DashFinder.Step.SEARCH)
        rig.search.onResume(owner)
        rig.search.onResume(owner)
        assertEquals(2, rig.scans.scans)
    }

    @Test
    fun `every list that arrives while searching is read`() {
        val rig = Rig(DashFinder.Step.SEARCH)
        rig.search.onResume(owner)
        rig.listArrives()
        rig.listArrives()
        assertEquals(2, rig.learned)
        assertEquals(0, rig.scans.unregistered)
    }

    @Test
    fun `a list that arrives after the name became known is not read and ends the listening`() {
        val rig = Rig(DashFinder.Step.SEARCH)
        rig.search.onResume(owner)
        rig.step = DashFinder.Step.KNOWN
        rig.listArrives()
        assertEquals(0, rig.learned)
        assertEquals(1, rig.scans.unregistered)
        rig.search.onPause(owner)
        assertEquals("already stopped: not taken off twice", 1, rig.scans.unregistered)
    }

    @Test
    fun `leaving the screen stops the listening, once, and coming back starts it again`() {
        val rig = Rig(DashFinder.Step.SEARCH)
        rig.search.onResume(owner)
        rig.search.onPause(owner)
        rig.search.onPause(owner)
        assertEquals(1, rig.scans.unregistered)
        rig.search.onResume(owner)
        assertEquals(2, rig.scans.registered.size)
    }

    @Test
    fun `leaving the screen with nothing listened to takes nothing off`() {
        val rig = Rig(DashFinder.Step.ASK)
        rig.search.onResume(owner)
        rig.search.onPause(owner)
        assertEquals(0, rig.scans.unregistered)
    }

    @Test
    fun `coming back with the name known after a search ends the listening`() {
        val rig = Rig(DashFinder.Step.SEARCH)
        rig.search.onResume(owner)
        rig.step = DashFinder.Step.KNOWN
        rig.search.onResume(owner)
        assertEquals(1, rig.scans.unregistered)
    }
}
