package com.reau.app.aa

/**
 * For the tap to feel fast, not only be fast: whether the cover of a
 * walk through Android Auto's menu goes up at the rider's own Ligar or Desligar, before the walk itself begins (on
 * Ligar after Android Auto's first dial, on Desligar only after the session has ended, about 2 s later). Only when that
 * walk is sure to come: on Ligar, the server switched off by REau at the end of the last projection, and not seen up
 * since (a Ligar right after a Desligar finds it still on); on Desligar, the server seen up in this one and no
 * notification "Stop" kept (that one needs no screen). Null: no cover at the tap.
 */
internal object TapCover {
    fun mode(on: Boolean, switchOn: Boolean, lastResult: ServerResult, seenUp: Boolean, stopKept: Boolean): ServerMenuRun.Mode? = when {
        !switchOn -> null
        on -> ServerMenuRun.Mode.START.takeIf { lastResult == ServerResult.STOPPED && !seenUp }
        else -> ServerMenuRun.Mode.STOP.takeIf { seenUp && !stopKept }
    }
}
