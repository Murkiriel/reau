package com.reau.app.aa

/**
 * The joystick page: every control of the dash's
 * joystick, in the media mode and in the map mode, once and held, with the action the rider picks. Only what the rider
 * changed is kept ([encode]); the rest are the defaults: in the media mode, what one tap did before the page; in the map
 * mode, the sides move the focus and the rest does nothing. The calls act above the page ([JoystickMap]).
 *
 * No "twice": a double tap does not work well on the dash's joystick; a "twice" choice saved before
 * is left out when read. "Held" is kept but never fires: as far as is known the dash sends one code per press,
 * with no release.
 */
data class JoystickBindings(private val chosen: Map<Slot, Action> = emptyMap()) {
    /** The dash's two modes, told by the codes it sends: track and volume in the media mode, zoom in the map mode. */
    enum class Mode { MEDIA, MAP }

    enum class Control { LEFT, RIGHT, UP, DOWN, CENTER }

    enum class Gesture { ONCE, HOLD }

    /**
     * What a control can do: keys and wheel steps for Android Auto, or the phone's call. [command] is what Android
     * Auto (or the phone) gets; the assistant only where Gemini's microphone was given.
     */
    enum class Action(private val make: (assistant: Boolean) -> AaCommand?) {
        NOTHING({ null }),
        WHEEL_PREVIOUS({ AaCommand.Scroll(-1) }),
        WHEEL_NEXT({ AaCommand.Scroll(1) }),
        CONFIRM({ AaCommand.Key(ServiceDiscoveryResponse.KEY_ENTER) }),
        BACK({ AaCommand.Key(ServiceDiscoveryResponse.KEY_BACK) }),
        HOME({ AaCommand.Key(ServiceDiscoveryResponse.KEY_HOME) }),
        PLAY_PAUSE({ AaCommand.Key(ServiceDiscoveryResponse.KEY_MEDIA_PLAY_PAUSE) }),
        NEXT_SONG({ AaCommand.Key(ServiceDiscoveryResponse.KEY_MEDIA_NEXT) }),
        PREVIOUS_SONG({ AaCommand.Key(ServiceDiscoveryResponse.KEY_MEDIA_PREVIOUS) }),
        ASSISTANT({ assistant -> if (assistant) AaCommand.Key(ServiceDiscoveryResponse.KEY_ASSISTANT) else null }),
        ;

        fun command(assistant: Boolean): AaCommand? = make(assistant)
    }

    data class Slot(val mode: Mode, val control: Control, val gesture: Gesture) {
        /** As written in the settings and the ride log: `media.right.twice`. */
        val key: String get() = "${mode.name.lowercase()}.${control.name.lowercase()}.${gesture.name.lowercase()}"
    }

    operator fun get(slot: Slot): Action = if (isLocked(slot)) Action.NOTHING else chosen[slot] ?: defaultOf(slot)

    /** This page with [slot] set to [action]; set back to its default, or locked, it is no longer kept as a choice. */
    fun with(slot: Slot, action: Action): JoystickBindings = when {
        isLocked(slot) -> JoystickBindings(chosen - slot)
        action == defaultOf(slot) -> JoystickBindings(chosen - slot)
        else -> JoystickBindings(chosen + (slot to action))
    }

    /** The choices only, in the page's order: `media.center.once=HOME;map.right.once=NEXT_SONG`. */
    fun encode(): String = SLOTS.filter { it in chosen }.joinToString(";") { "${it.key}=${chosen.getValue(it).name}" }

    companion object {
        /** Ten in each mode: five controls, once and held. */
        val SLOTS: List<Slot> = Mode.entries.flatMap { mode ->
            Control.entries.flatMap { control -> Gesture.entries.map { Slot(mode, control, it) } }
        }

        val DEFAULT = JoystickBindings()

        private val DEFAULTS = mapOf(
            Slot(Mode.MEDIA, Control.LEFT, Gesture.ONCE) to Action.WHEEL_PREVIOUS,
            Slot(Mode.MEDIA, Control.RIGHT, Gesture.ONCE) to Action.WHEEL_NEXT,
            Slot(Mode.MEDIA, Control.DOWN, Gesture.ONCE) to Action.HOME,
            Slot(Mode.MEDIA, Control.CENTER, Gesture.ONCE) to Action.CONFIRM,
            // In the dash's map mode only the sides move the focus.
            Slot(Mode.MAP, Control.LEFT, Gesture.ONCE) to Action.WHEEL_PREVIOUS,
            Slot(Mode.MAP, Control.RIGHT, Gesture.ONCE) to Action.WHEEL_NEXT,
        )

        fun defaultOf(slot: Slot): Action = DEFAULTS[slot] ?: Action.NOTHING

        /**
         * The controls the dash keeps for itself in its map mode: up is the dash's
         * own back and the centre puts it in the media mode. They take no action, whatever was saved for them.
         */
        val LOCKED: Set<Pair<Mode, Control>> = setOf(Mode.MAP to Control.UP, Mode.MAP to Control.CENTER)

        fun isLocked(slot: Slot): Boolean = slot.mode to slot.control in LOCKED

        private val BY_KEY = SLOTS.associateBy { it.key }

        /**
         * [text] without its map-mode choices: those saved
         * before the one-tap joystick put Gemini on every map line, and the map mode's sides move the focus now.
         */
        fun withoutMapChoices(text: String): String = text.split(';').filter { it.isNotBlank() && !it.startsWith("map.") }.joinToString(";")

        /** The choices from [encode]'s text; what cannot be read is left out, the rest is kept. */
        fun decode(text: String): JoystickBindings {
            val chosen = text.split(';').mapNotNull { entry ->
                val (key, name) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                val slot = BY_KEY[key] ?: return@mapNotNull null
                val action = Action.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
                slot to action
            }
            return chosen.fold(DEFAULT) { page, (slot, action) -> page.with(slot, action) }
        }
    }
}
