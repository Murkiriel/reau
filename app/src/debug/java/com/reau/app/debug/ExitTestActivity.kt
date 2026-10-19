package com.reau.app.debug

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Debug builds only: starts the app's `:exittest` process and leaves it in the background, so
 * ExitHistoryTest can end it (am kill, a signal, am force-stop) and see how the next projection tells the exit.
 */
class ExitTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}

/** Debug builds only: holds its broadcast for as long as asked, so Android ends `:exittest` with an ANR. */
class ExitTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Thread.sleep(intent.getLongExtra("holdMs", 0))
    }
}
