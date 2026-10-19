package com.reau.app.aa

import com.reau.app.aa.AndroidAutoStore.Route
import com.reau.app.ui.MainUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Para onde vao os botoes do Android Auto: as configuracoes dele, a loja ou, no limite, um aviso (nunca silencio). */
class AndroidAutoStoreTest {
    @Test
    fun `com o Android Auto instalado o botao abre as configuracoes, haja loja ou nao`() {
        assertEquals(Route.SETTINGS, AndroidAutoStore.route(installed = true, storeApp = true, storeWeb = true))
        assertEquals(Route.SETTINGS, AndroidAutoStore.route(installed = true, storeApp = false, storeWeb = false))
    }

    @Test
    fun `sem o Android Auto o botao leva a loja do celular`() {
        assertEquals(Route.STORE_APP, AndroidAutoStore.route(installed = false, storeApp = true, storeWeb = true))
        assertEquals(Route.STORE_APP, AndroidAutoStore.route(installed = false, storeApp = true, storeWeb = false))
    }

    @Test
    fun `sem o Android Auto e sem loja o botao cai na pagina da web`() {
        assertEquals(Route.STORE_WEB, AndroidAutoStore.route(installed = false, storeApp = false, storeWeb = true))
    }

    @Test
    fun `sem o Android Auto, sem loja e sem navegador sobra o aviso`() {
        assertEquals(Route.NOTICE, AndroidAutoStore.route(installed = false, storeApp = false, storeWeb = false))
    }

    @Test
    fun `a tela principal sabe se o Android Auto esta instalado pela versao lida`() {
        assertFalse(MainUiState().androidAutoInstalled)
        assertTrue(MainUiState(androidAutoVersion = "17.8.664004-release").androidAutoInstalled)
    }
}
