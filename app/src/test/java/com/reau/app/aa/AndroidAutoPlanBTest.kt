package com.reau.app.aa

import android.content.ActivityNotFoundException
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When Android Auto's own settings screen does not open and the system's page of the app is opened instead (plan B), the ride log says so, with the
 * reason (item 10 of the 11/11/2026 list). What opens is as before; only the line is new.
 */
class AndroidAutoPlanBTest {
    private val own = Intent()
    private val details = Intent()

    private fun open(start: (Intent) -> Unit, said: MutableList<String>) =
        AndroidAuto.open(start, { said += it }, own = { own }, details = { details })

    @Test
    fun `when Android Auto's screen opens nothing is said and the plan B is not tried`() {
        val said = mutableListOf<String>()
        val started = mutableListOf<Intent>()
        assertEquals(AndroidAuto.Opened.ANDROID_AUTO_SETTINGS, open({ started += it }, said))
        assertEquals(listOf(own), started)
        assertEquals(emptyList<String>(), said)
    }

    @Test
    fun `when it is not found the page of the app opens and the line says why`() {
        val said = mutableListOf<String>()
        val opened = open({ if (it === own) throw ActivityNotFoundException("no") }, said)
        assertEquals(AndroidAuto.Opened.APP_DETAILS, opened)
        assertEquals(
            listOf("Android Auto's own settings screen did not open (ActivityNotFoundException): the system's page of the app opened instead (plan B)"),
            said,
        )
    }

    @Test
    fun `a refusal is told by its name`() {
        val said = mutableListOf<String>()
        open({ if (it === own) throw SecurityException("not exported") }, said)
        assertTrue(said.toString(), "SecurityException" in said.single())
    }

    @Test
    fun `when neither opens the line says both failed and the result is nothing`() {
        val said = mutableListOf<String>()
        val opened = open({ throw ActivityNotFoundException("nothing") }, said)
        assertEquals(AndroidAuto.Opened.NOTHING, opened)
        assertTrue(said.toString(), "did not open either (ActivityNotFoundException)" in said.single())
    }
}
