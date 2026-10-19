package com.reau.app.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The texts of Android Auto's menu, matched by equality only. */
class ServerTextsTest {
    private val texts = ServerTexts.table()

    @Test
    fun `normalizing folds case, trims and joins the spaces`() {
        assertEquals("iniciar servidor da unidade principal", ServerTexts.normalize("  Iniciar   servidor da\tUnidade Principal "))
    }

    @Test
    fun `normalizing composes the accents, so a decomposed text still matches`() {
        // "ç" and "õ" written as a letter and a combining mark.
        assertTrue(texts.isMoreOptions("Mais opc\u0327o\u0303es"))
    }

    @Test
    fun `the server items match in Portuguese, English and Spanish`() {
        listOf("Iniciar servidor da unidade principal", "Start head unit server", "Iniciar servidor unidad princ.").forEach {
            assertTrue(it, texts.isStart(it))
        }
        listOf("Parar servidor da unid. princ.", "Stop head unit server", "Detener serv. unidad principal").forEach {
            assertTrue(it, texts.isStop(it))
        }
    }

    @Test
    fun `leaving developer mode is never the server's item`() {
        listOf("Sair do modo de desenvolvedor", "Quit developer mode", "Configurações do desenvolvedor").forEach {
            assertFalse(it, texts.isStart(it))
            assertFalse(it, texts.isStop(it))
            assertTrue(it, texts.isDeveloper(it))
        }
    }

    @Test
    fun `a text that only contains the item does not match`() {
        assertFalse(texts.isStart("Iniciar servidor da unidade principal agora"))
        assertFalse(texts.isStart("servidor da unidade principal"))
        assertFalse(texts.isMoreOptions("options"))
        assertFalse(texts.isStart(null))
    }

    @Test
    fun `Android Auto's own texts join the table, and are counted`() {
        val own = ServerTexts.load { name ->
            when (name) {
                ServerTexts.RES_START -> "Ligar servidor"
                ServerTexts.RES_MORE_OPTIONS -> "Opções"
                else -> null
            }
        }
        assertTrue(own.isStart("Ligar servidor"))
        assertTrue(own.isStart("Start head unit server"))
        assertTrue(own.isMoreOptions("Opções"))
        assertEquals(2, own.fromAndroidAuto)
        assertEquals(0, texts.fromAndroidAuto)
    }

    @Test
    fun `a blank text from Android Auto adds nothing`() {
        val own = ServerTexts.load { "  " }
        assertEquals(0, own.fromAndroidAuto)
        assertFalse(own.isStart("  "))
    }

    @Test
    fun `the menu with the start item reads as start`() {
        val menu = listOf("Ajuda e feedback", "Configurações do desenvolvedor", "Iniciar servidor da unidade principal", "Sair do modo de desenvolvedor")
        assertEquals(ServerTexts.Reading.START_SHOWN, texts.read(menu))
    }

    @Test
    fun `the menu with the stop item reads as stop`() {
        val menu = listOf("Ajuda e feedback", "Configurações do desenvolvedor", "Parar servidor da unid. princ.", "Sair do modo de desenvolvedor")
        assertEquals(ServerTexts.Reading.STOP_SHOWN, texts.read(menu))
    }

    @Test
    fun `developer items without the server's are a menu that changed`() {
        val menu = listOf("Ajuda e feedback", "Configurações do desenvolvedor", "Sair do modo de desenvolvedor")
        assertEquals(ServerTexts.Reading.UNKNOWN_DEVELOPER_MENU, texts.read(menu))
    }

    @Test
    fun `both server items at once is not trusted`() {
        val menu = listOf("Iniciar servidor da unidade principal", "Parar servidor da unid. princ.")
        assertEquals(ServerTexts.Reading.UNKNOWN_DEVELOPER_MENU, texts.read(menu))
    }

    @Test
    fun `only the help items mean developer mode off`() {
        assertEquals(ServerTexts.Reading.DEVELOPER_MODE_OFF, texts.read(listOf("Ajuda e feedback", "Enviar feedback")))
        assertEquals(ServerTexts.Reading.DEVELOPER_MODE_OFF, texts.read(listOf("Help & feedback")))
    }

    @Test
    fun `nothing known is unreadable`() {
        assertEquals(ServerTexts.Reading.UNREADABLE, texts.read(listOf("Bluetooth", "Versão")))
        assertEquals(ServerTexts.Reading.UNREADABLE, texts.read(emptyList()))
    }

    @Test
    fun `the notification's stop and text match`() {
        assertTrue(texts.isNotificationStop("Parar"))
        assertTrue(texts.isNotificationText("Servidor da unidade principal em execução"))
        assertFalse(texts.isNotificationStop("Parar servidor"))
    }
}
