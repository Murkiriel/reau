package com.reau.app.service

import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.reau.app.aa.ServerMenuRun
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The cover over the walk through Android Auto's menu, as the redesign draws it: the text of the
 * walk, the three steps with where it is, and Cancelar.
 */
@RunWith(AndroidJUnit4::class)
class ServerCoverViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun stepIcon(view: View, index: Int): ImageView = (view.findViewWithTag<LinearLayout>("cover_step_$index").getChildAt(0) as ImageView)

    private fun stepText(view: View, index: Int): String = (view.findViewWithTag<LinearLayout>("cover_step_$index").getChildAt(1) as TextView).text.toString()

    @Test
    fun theStepsSayWhereTheWalkIs() {
        lateinit var cover: ServerCoverView
        onMain { cover = ServerCoverView(context, ServerMenuRun.Mode.START) {} }
        assertEquals(listOf("agora", "falta", "falta"), (0..2).map { stepIcon(cover, it).tag })
        onMain { cover.setStep(2) }
        assertEquals(listOf("feito", "feito", "agora"), (0..2).map { stepIcon(cover, it).tag })
        onMain { cover.setStep(3) }
        assertEquals(listOf("feito", "feito", "feito"), (0..2).map { stepIcon(cover, it).tag })
    }

    @Test
    fun startingAndStoppingNameTheirLastStepAndTheTextIsTheWalks() {
        lateinit var start: ServerCoverView
        lateinit var stop: ServerCoverView
        onMain {
            start = ServerCoverView(context, ServerMenuRun.Mode.START) {}.apply { setText(com.reau.app.R.string.aa_server_cover) }
            stop = ServerCoverView(context, ServerMenuRun.Mode.STOP) {}
        }
        assertEquals("Iniciar servidor da unidade principal", stepText(start, 2))
        assertEquals("Parar servidor da unidade principal", stepText(stop, 2))
        assertEquals(context.getString(com.reau.app.R.string.aa_server_cover), start.findViewWithTag<TextView>("cover_text").text.toString())
    }

    /**
     * With Android's animations off, the ring does not turn, and it stood at the same
     * quarter whatever the steps said. It fills with them now: a third, two thirds, all; green when every step is done.
     */
    @Test
    fun theRingFillsWithTheSteps() {
        lateinit var cover: ServerCoverView
        onMain { cover = ServerCoverView(context, ServerMenuRun.Mode.START) {} }
        val sweeps = (0..3).map { step ->
            var sweep = 0f
            onMain { cover.setStep(step); sweep = cover.ringSweep }
            sweep
        }
        assertEquals(listOf(120f, 240f, 360f, 360f), sweeps)
        assertEquals("green once every step is done", true, cover.ringDone)
        onMain { cover.setStep(1) }
        assertEquals(false, cover.ringDone)
    }

    @Test
    fun cancelarCallsBack() {
        var cancelled = 0
        lateinit var cover: ServerCoverView
        onMain { cover = ServerCoverView(context, ServerMenuRun.Mode.START) { cancelled++ } }
        onMain { cover.findViewWithTag<View>("cover_cancel").performClick() }
        assertEquals(1, cancelled)
    }
}
