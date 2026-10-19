package com.reau.app.ui

import com.reau.app.ui.components.StatusKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** O estado da tela principal: o que o servidor de rede muda nele, a cor do icone do servidor e os padroes de uma tela recem-aberta. */
class UiStateTest {
    @Test
    fun `ligar o servidor de rede troca so a bandeira dele`() {
        val before = MainUiState(dashName = "RE_AB12_345678", permissionsMissing = 2)
        val after = before.withNetwork(true)
        assertTrue(after.server.networkOn)
        assertEquals(before.copy(server = before.server.copy(networkOn = true)), after)
        assertEquals("o resto do estado fica", "RE_AB12_345678", after.dashName)
        assertEquals(2, after.permissionsMissing)
        assertFalse("o original nao muda", before.server.networkOn)
    }

    @Test
    fun `desligar o servidor de rede volta a bandeira`() {
        assertFalse(MainUiState().withNetwork(true).withNetwork(false).server.networkOn)
    }

    @Test
    fun `o icone do servidor e verde ligado e quieto desligado`() {
        assertEquals(StatusKind.OK, serverKind(true))
        assertEquals(StatusKind.IDLE, serverKind(false))
    }

    @Test
    fun `uma tela recem-aberta nao mostra aviso nenhum de bateria nem servidor ligado`() {
        val state = MainUiState()
        assertTrue(state.battery.notices.isEmpty())
        assertFalse(state.server.networkOn)
        assertFalse(state.server.auto)
        assertFalse(state.weatherOn)
        assertFalse(state.vpnHoldsREau)
        assertEquals("", state.dashName)
        assertEquals(0, state.permissionsMissing)
    }
}
