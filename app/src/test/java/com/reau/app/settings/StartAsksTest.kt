package com.reau.app.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * After a clean install the dash's whole Wi-Fi name is gone with the old install, and REau learns it only from the
 * Wi-Fi list, which Android gives with the location permission. Ligar asks for it, with the notification, while the
 * name is not known.
 */
class StartAsksTest {
    private val notifications = "android.permission.POST_NOTIFICATIONS"
    private val location = listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION")

    private fun asks(sdk: Int = 34, card: Boolean = false, notificationsOk: Boolean = false, locationOk: Boolean = false, nameKnown: Boolean = false) =
        StartAsks.permissions(sdk, card, notificationsOk, locationOk, nameKnown)

    @Test
    fun `a clean install asks for the notification and for the location together`() {
        assertEquals(listOf(notifications) + location, asks())
    }

    @Test
    fun `the location is asked only while the dash's name is not known`() {
        assertEquals(listOf(notifications), asks(nameKnown = true))
        assertEquals(location, asks(notificationsOk = true))
        assertEquals(emptyList<String>(), asks(notificationsOk = true, locationOk = true))
    }

    @Test
    fun `the card mode needs no Wi-Fi list`() {
        assertEquals(listOf(notifications), asks(card = true))
    }

    @Test
    fun `before Android 13 there is no notification question, the location one stays`() {
        assertEquals(location, asks(sdk = 32))
    }
}
