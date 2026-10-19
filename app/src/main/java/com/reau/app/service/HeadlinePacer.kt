package com.reau.app.service

/**
 * Hands the notification only the headlines worth showing. Android drops a notification update that comes too soon
 * after the last (about five a second per app), and the state can change several times in a row: so each new
 * headline waits [paceMs], a newer one replaces it while it waits, and only a headline different from the one on
 * show is posted. However fast the state flaps, the last word is what shows.
 *
 * [schedule] runs an action later and gives back how to cancel it. Not thread-safe: the service calls it from its
 * worker only.
 */
internal class HeadlinePacer(
    private val paceMs: Long,
    private val schedule: (Long, () -> Unit) -> () -> Unit,
    private val show: (Headline) -> Unit,
) {
    private var shown: Headline? = null
    private var cancelPending: (() -> Unit)? = null

    /** What is on show already, without this pacer: the notification the service started with. */
    fun alreadyShown(headline: Headline) {
        shown = headline
    }

    /**
     * Posts [headline] now, with no pace: for a state that must show while the worker is busy (the pace runs on it), such
     * as "Desligando" during a stop that blocks it for seconds. A newer one waiting is dropped.
     */
    fun showNow(headline: Headline) {
        cancelPending?.invoke()
        cancelPending = null
        if (headline != shown) {
            show(headline)
            shown = headline
        }
    }

    fun offer(headline: Headline) {
        cancelPending?.invoke()
        cancelPending = schedule(paceMs) {
            cancelPending = null
            if (headline != shown) {
                show(headline)
                shown = headline
            }
        }
    }
}
