package com.reau.app.debug

import android.app.Activity
import android.os.Bundle
import com.reau.app.data.RideDiagnostics

/**
 * Debug builds only: crashes its process (`:crashtest`) on purpose, with a ride log open, so
 * CrashHandlerTest can see what a real crash leaves behind.
 */
class CrashTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RideDiagnostics.start("crash test")
        error("crash test: ${intent.getStringExtra("message")}")
    }
}
