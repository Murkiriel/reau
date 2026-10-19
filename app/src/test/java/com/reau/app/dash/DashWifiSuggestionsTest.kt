package com.reau.app.dash

import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A sugestao de Wi-Fi do painel so existe com a chave ligada e o nome inteiro conhecido. Aqui so os casos sem sugestao: montar uma
 * (WifiNetworkSuggestion.Builder) e coisa do Android, que a JVM dos testes nao tem.
 */
class DashWifiSuggestionsTest {
    @Test
    fun `com a chave desligada nao ha sugestao, mesmo com o nome inteiro e a senha`() {
        assertNull(DashWifiSuggestions.suggestionFor(enabled = false, ssid = "RE_AB12_345678", password = "12345678"))
    }

    @Test
    fun `sem o nome inteiro do painel nao ha sugestao, so o prefixo de fabrica nao basta`() {
        assertNull(DashWifiSuggestions.suggestionFor(enabled = true, ssid = "", password = "12345678"))
        assertNull(DashWifiSuggestions.suggestionFor(enabled = true, ssid = DashPairing.DEFAULT_PREFIX, password = "12345678"))
        assertNull(DashWifiSuggestions.suggestionFor(enabled = true, ssid = "R", password = ""))
    }
}
