package com.reau.app.service

import com.reau.app.service.BatteryHeatWatch.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Plan 22, item 4: the rider is told once when the battery reaches 44 C (the ride of 01/11/2026 held 40 to 42 C for 90 minutes with the
 * Android thermal status at `none`), and again only after it fell under 41 C and went over again.
 */
class BatteryHeatWatchTest {
    private fun BatteryHeatWatch.run(vararg temps: Float?) = temps.map { offer(it) }

    @Test
    fun `at 44 the warning comes once`() {
        val watch = BatteryHeatWatch()
        assertEquals(listOf(Verdict.NONE, Verdict.NONE, Verdict.WARN), watch.run(40f, 43.9f, 44f))
    }

    @Test
    fun `the thresholds are 44 to warn and 41 to rearm`() {
        assertEquals(44f, BatteryHeatWatch.HOT_C)
        assertEquals(41f, BatteryHeatWatch.COOL_C)
    }

    @Test
    fun `staying hot says nothing more, however long`() {
        val watch = BatteryHeatWatch()
        watch.offer(44.5f)
        assertEquals(List(100) { Verdict.NONE }, watch.run(*FloatArray(100) { 45f + it % 3 }.toTypedArray()))
    }

    @Test
    fun `a dip that stays above 41 does not rearm it`() {
        val watch = BatteryHeatWatch()
        assertEquals(Verdict.WARN, watch.offer(44f))
        assertEquals(listOf(Verdict.NONE, Verdict.NONE, Verdict.NONE), watch.run(43f, 41f, 44.2f)) // 41.0 is not under 41
    }

    @Test
    fun `falling under 41 takes the notice away and the next pass over 44 warns again`() {
        val watch = BatteryHeatWatch()
        assertEquals(listOf(Verdict.WARN, Verdict.NONE, Verdict.COOLED, Verdict.NONE, Verdict.WARN), watch.run(44f, 42f, 40.9f, 43f, 44f))
    }

    @Test
    fun `a battery that does not say its temperature changes nothing`() {
        val watch = BatteryHeatWatch()
        assertEquals(listOf(Verdict.NONE, Verdict.WARN, Verdict.NONE, Verdict.NONE), watch.run(null, 45f, null, 45f))
        assertEquals(Verdict.COOLED, watch.offer(30f))
    }

    @Test
    fun `a cool phone never warns`() {
        val watch = BatteryHeatWatch()
        assertTrue(watch.run(30f, 36f, 42f, 43.99f, 38f).all { it == Verdict.NONE })
    }

    @Test
    fun `each projection has its own watch, armed`() {
        val first = BatteryHeatWatch()
        first.offer(46f)
        assertEquals(Verdict.WARN, BatteryHeatWatch().offer(46f))
    }

    // ── the wiring and the words ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the service looks at the battery with a watch of its own per projection, warns in the alert channel and clears at the end`() {
        val service = File("src/main/java/com/reau/app/service/ProjectionService.kt").readText()
        val look = service.substringAfter("private fun lookAtTheDevice()").substringBefore("private fun warnIfHot")
        assertTrue(look.contains("val heat = BatteryHeatWatch()"))
        assertTrue(look.contains("warnIfHot(heat, state)"))
        assertTrue(service.substringAfter("private fun warnIfHot").contains("ProjectionNotification.showPhoneHot("))
        assertTrue(service.substringAfter("override fun release()").substringBefore("locks.release()").contains("ProjectionNotification.clearPhoneHot("))
        val notification = File("src/main/java/com/reau/app/service/ProjectionNotification.kt").readText()
        assertTrue(notification.substringAfter("fun showPhoneHot").substringBefore("fun clearPhoneHot").contains("Notification.Builder(context, ALERT_CHANNEL_ID)"))
    }

    private fun strings(dir: String) = File("src/main/res/$dir/strings.xml").readText()

    @Test
    fun `the words are in the three languages, with the temperature, and say what to do`() {
        val en = strings("values")
        val pt = strings("values-pt")
        val es = strings("values-es")
        assertTrue(Regex("""name="notif_text_phone_hot">[^<]*hot[^<]*%1\${'$'}d[^<]*sun[^<]*pocket[^<]*charger""").containsMatchIn(en))
        assertTrue(Regex("""name="notif_text_phone_hot">[^<]*quente[^<]*%1\${'$'}d[^<]*sol[^<]*bolso[^<]*carregador""").containsMatchIn(pt))
        assertTrue(Regex("""name="notif_text_phone_hot">[^<]*caliente[^<]*%1\${'$'}d[^<]*sol[^<]*bolsillo[^<]*cargador""").containsMatchIn(es))
        for (text in listOf(en, pt, es)) assertTrue(Regex("""name="notif_title_phone_hot">""").containsMatchIn(text))
    }
}
