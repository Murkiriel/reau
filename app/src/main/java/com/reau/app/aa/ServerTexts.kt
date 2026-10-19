package com.reau.app.aa

import java.text.Normalizer
import java.util.Locale

/**
 * The texts of Android Auto's own screens that switching its head unit server on and off by accessibility looks for:
 * the ⋮ button's description,
 * the menu's "start" and "stop" items, the items that prove the developer menu is there, the help items of the menu
 * without it, and the notification's "Stop" button.
 *
 * Each set is what Android Auto itself says in the phone's language (read by resource name, [load]) plus a fixed
 * table in Portuguese, English and Spanish. A text matches only when it is equal to one of the set once normalized
 * ([normalize]), never by "contains": "Sair do modo de desenvolvedor" sits right under the server's item and must never
 * be taken for it.
 */
@Suppress("LongParameterList") // one set per kind of text Android Auto shows, and the count for the log
class ServerTexts(
    val start: Set<String>,
    val stop: Set<String>,
    val developer: Set<String>,
    val help: Set<String>,
    val moreOptions: Set<String>,
    val notificationStop: Set<String>,
    val notificationText: Set<String>,
    /** How many of [RESOURCES] Android Auto answered, for the log. */
    val fromAndroidAuto: Int = 0,
) {
    /** What the menu's items say, as [read] tells it. */
    enum class Reading {
        /** "Iniciar servidor da unidade principal": developer mode on, server stopped. */
        START_SHOWN,

        /** "Parar servidor da unid. princ.": Android Auto holds its server as running. */
        STOP_SHOWN,

        /** The developer items without the server's: the menu changed in a new version. */
        UNKNOWN_DEVELOPER_MENU,

        /** Only the help items: developer mode off (or the menu drawn half-way, before the settings loaded). */
        DEVELOPER_MODE_OFF,

        /** Nothing known. */
        UNREADABLE,
    }

    fun isStart(text: String?) = text != null && normalize(text) in start
    fun isStop(text: String?) = text != null && normalize(text) in stop
    fun isDeveloper(text: String?) = text != null && normalize(text) in developer
    fun isMoreOptions(description: String?) = description != null && normalize(description) in moreOptions
    fun isNotificationStop(title: String?) = title != null && normalize(title) in notificationStop
    fun isNotificationText(text: String?) = text != null && normalize(text) in notificationText

    /** What a menu showing [items] means. Both server items at once is not a menu Android Auto draws: nothing is touched. */
    fun read(items: List<String>): Reading {
        val starts = items.any(::isStart)
        val stops = items.any(::isStop)
        return when {
            starts && !stops -> Reading.START_SHOWN
            stops && !starts -> Reading.STOP_SHOWN
            starts || items.any(::isDeveloper) -> Reading.UNKNOWN_DEVELOPER_MENU
            items.any { it.isNotBlank() && normalize(it) in help } -> Reading.DEVELOPER_MODE_OFF
            else -> Reading.UNREADABLE
        }
    }

    companion object {
        // Android Auto's string resources, by name: the numbers change with each version, the names do not.
        const val RES_START = "menu_action_developer_head_unit_network_server"
        const val RES_STOP = "menu_action_developer_head_unit_network_server_stop"
        const val RES_DEVELOPER_SETTINGS = "menu_action_developer_settings"
        const val RES_QUIT_DEVELOPER = "menu_action_quit_developer_mode"
        const val RES_MORE_OPTIONS = "abc_action_menu_overflow_description"
        const val RES_NOTIFICATION_STOP = "developer_head_unit_server_notification_stop"
        const val RES_NOTIFICATION_TEXT = "developer_head_unit_server_notification_subtitle"

        val RESOURCES = listOf(
            RES_START, RES_STOP, RES_DEVELOPER_SETTINGS, RES_QUIT_DEVELOPER, RES_MORE_OPTIONS, RES_NOTIFICATION_STOP, RES_NOTIFICATION_TEXT,
        )

        // The fixed tables (Android Auto 17.7 and 17.9: pt-BR, pt-PT, en, es, es-419).
        val START_TABLE = listOf(
            "Iniciar servidor da unidade principal", "Iniciar serv. unid. principal", "Start head unit server",
            "Iniciar servidor de la unidad principal", "Iniciar servidor unidad princ.",
        )
        val STOP_TABLE = listOf(
            "Parar servidor da unid. princ.", "Parar serv. unidade principal", "Stop head unit server",
            "Parar servidor unidad principal", "Detener serv. unidad principal",
        )
        val DEVELOPER_TABLE = listOf(
            "Configurações do desenvolvedor", "Sair do modo de desenvolvedor", "Developer settings", "Quit developer mode",
            "Ajustes de desarrollador", "Configuración para desarrolladores", "Salir de modo de desarrollador",
            "Salir del modo de desarrollador",
        )
        val HELP_TABLE = listOf(
            "Ajuda e feedback", "Enviar feedback", "Help & feedback", "Send feedback", "Ayuda y comentarios", "Enviar comentarios",
        )
        val MORE_OPTIONS_TABLE = listOf("Mais opções", "More options", "Más opciones")
        val NOTIFICATION_STOP_TABLE = listOf("Parar", "Stop", "Detener")
        val NOTIFICATION_TEXT_TABLE = listOf(
            "Servidor da unidade principal em execução", "Head unit server running",
            "Servidor de la unidad principal en ejecución", "Servidor de unidad principal en ejecución",
        )

        private val SPACES = Regex("\\s+")

        /** Unicode NFC, lower case without a locale's quirks, spaces trimmed and runs of them made one. */
        fun normalize(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT).trim().replace(SPACES, " ")

        /** The fixed tables alone. */
        fun table(): ServerTexts = load { null }

        /**
         * The tables plus what Android Auto says, [read] giving a resource's text by name (null when the resource is
         * not there: a version that renamed it, or no Android Auto).
         */
        fun load(read: (String) -> String?): ServerTexts {
            val own = RESOURCES.associateWith { name -> read(name)?.takeIf { it.isNotBlank() } }
            fun set(table: List<String>, vararg names: String) =
                (table + names.mapNotNull { own[it] }).map(::normalize).toSet()
            return ServerTexts(
                start = set(START_TABLE, RES_START),
                stop = set(STOP_TABLE, RES_STOP),
                developer = set(DEVELOPER_TABLE, RES_DEVELOPER_SETTINGS, RES_QUIT_DEVELOPER),
                help = set(HELP_TABLE),
                moreOptions = set(MORE_OPTIONS_TABLE, RES_MORE_OPTIONS),
                notificationStop = set(NOTIFICATION_STOP_TABLE, RES_NOTIFICATION_STOP),
                notificationText = set(NOTIFICATION_TEXT_TABLE, RES_NOTIFICATION_TEXT),
                fromAndroidAuto = own.values.count { it != null },
            )
        }
    }
}
