package com.reau.app.web

/**
 * What the server says to the page in text messages: one small JSON, `{"state":"live","touch":true}`. The video
 * itself goes in binary messages. [State.LIVE]: Android Auto is on and the picture comes; [State.OFF]: Android Auto is off
 * and the page waits ("Android Auto desligado"). `touch` says whether the page may send touch: the page sends
 * nothing until it is told so, and the server ignores what it was not told to accept.
 */
object WebProtocol {
    enum class State(val wire: String) { LIVE("live"), OFF("off") }

    fun status(state: State, touch: Boolean): String = "{\"state\":\"${state.wire}\",\"touch\":$touch}"

    /** Sent once to a page that is on the phone itself: it gets the picture and no sound (the sound would go round through Android Auto). */
    const val SOUND_OFF = "{\"sound\":false}"
}
