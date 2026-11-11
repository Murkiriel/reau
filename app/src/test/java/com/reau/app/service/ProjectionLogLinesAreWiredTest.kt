package com.reau.app.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the new lines of the 11/11/2026 list are fed from. These are of the text of the source: the places are inside the live sides, the service, the
 * socket's close and the Android receivers, which need a Context, a MediaCodec or the emulator (the same class of test as the other "...IsWired" ones);
 * what each piece does is held by the behaviour tests beside them (RideSummaryTest, ExitSequenceTest, WifiEventsTest, ChannelTextTest...).
 */
class ProjectionLogLinesAreWiredTest {
    private fun source(path: String) = File("src/main/java/com/reau/app/$path").readText()

    @Test
    fun `the summary is fed the unknown buttons and the two maneuvers of the guidance`() {
        val live = source("service/LiveSides.kt")
        assertTrue("a button the page has no meaning for is counted", "if (!JoystickMap.knowsCode(event)) com.reau.app.data.ProjectionSummary.current.unknownButton(event.code)" in live)
        assertTrue("the maneuver now", "summary.maneuver(0, guidance.maneuverType, guidance.rerouting || AaManeuvers.knowsType(guidance.maneuverType))" in live)
        assertTrue("the one after", "summary.maneuver(1, guidance.nextManeuverType, AaManeuvers.knowsType(guidance.nextManeuverType))" in live)
    }

    @Test
    fun `the summary line is given the time without the picture of the image gap clock`() {
        val service = source("service/ProjectionService.kt")
        assertTrue(service, "noImageMs = com.reau.app.dash.ImageGap.clock.totalMs(com.reau.app.util.MonotonicClock.nowMs())" in service)
    }

    @Test
    fun `the exit sequence is told after the socket closes, and the dash's answers are noted while disconnecting`() {
        val session = source("dash/DashSession.kt")
        assertTrue("answers", "if (disconnecting) noteExitAnswer(pkt)" in session)
        val goodbye = session.substringAfter("val goodbye = Runnable {").substringBefore("// A guard: today every caller")
        assertTrue(goodbye, goodbye.indexOf("sock.close()") in 0 until goodbye.indexOf("exit.summary()"))
        assertTrue("the commands are sent through the one helper that tells them", Regex("""send\("""").findAll(goodbye).count() == 5)
    }

    @Test
    fun `the join of 400 ms for the goodbye on the main thread is as it was`() {
        val session = source("dash/DashSession.kt")
        assertTrue("GOODBYE_WAIT_MS = 400L" in session && ".join(GOODBYE_WAIT_MS)" in session)
    }

    @Test
    fun `the system events listen to the Wi-Fi's three broadcasts and its networks, and never read a name`() {
        val events = source("service/SystemEvents.kt")
        for (action in listOf("WIFI_STATE_CHANGED_ACTION", "SUPPLICANT_STATE_CHANGED_ACTION", "NETWORK_STATE_CHANGED_ACTION")) {
            assertEquals(action, 2, Regex("""WifiManager\.$action""").findAll(events).count()) // the filter and the receiver's branch
        }
        assertTrue("watchTheWifiNetworks(app)" in events)
        for (name in listOf("EXTRA_BSSID", "EXTRA_WIFI_INFO", "EXTRA_NETWORK_INFO.toString", ".ssid", ".SSID", "bssid", "connectionInfo")) {
            assertFalse("a name or an address is read: $name", name in events)
        }
    }

    @Test
    fun `the exported report carries the header with the experiments line, and the permissions did not grow`() {
        val export = source("util/DiagnosticsExport.kt")
        assertTrue(export, "export(sources, dir, header = reportHeader(app, read.getOrNull(), readable = read.isSuccess))" in export)
        val manifest = File("src/main/AndroidManifest.xml").readText()
        // No permission was added for the Wi-Fi events: the ones the app already holds.
        assertFalse("READ_LOGS" in manifest || "ACCESS_FINE_LOCATION" in source("service/WifiEvents.kt"))
    }

    @Test
    fun `both notification channels are made again when their text is stale, and the alert one is refreshed at each start without being created`() {
        val notification = source("service/ProjectionNotification.kt")
        assertEquals(2, Regex("""channelTextStale\(existing\.name, existing\.description, name, description\)""").findAll(notification).count())
        val refresh = notification.substringAfter("fun refreshAlertChannel").substringBefore("/**")
        assertTrue(refresh, "if (manager.getNotificationChannel(ALERT_CHANNEL_ID) != null) createAlertChannel(context)" in refresh)
        assertTrue("ProjectionNotification.refreshAlertChannel(this)" in source("service/ProjectionService.kt"))
    }

    @Test
    fun `the debug warnings fire the real notices, and the phone hot one fires above its threshold`() {
        val debug = File("src/debug/java/com/reau/app/debug/DebugTriggers.kt").readText()
        assertTrue("\"dash stuck\" -> ProjectionNotification.showDashStuck(context)" in debug)
        assertTrue("\"phone hot\" -> ProjectionNotification.showPhoneHot(context, HOT_TEMP_C)" in debug)
        assertTrue("\"no dash stop\" -> ProjectionNotification.showStoppedNoDash(context, 1)" in debug)
        assertTrue("the alert channel's page", "Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS" in debug && "ProjectionNotification.ALERT_CHANNEL_ID" in debug)
        assertTrue("the hot notice is above the threshold", com.reau.app.debug.DebugTriggers.HOT_TEMP_C >= BatteryHeatWatch.HOT_C)
    }
}
