// Copied from OpenCfMoto (AGPL-3.0): app/src/main/java/dev/zanderp/opencfmoto/aa/AaLog.kt, commit 0abbe2a.
// Changes: package, the logcat tag, and the comments that named OpenCfMoto's screens; String.format is given a Locale.
package com.reau.app.aa

import android.util.Log

/**
 * Logging shim for the ported Android Auto (AAP) receiver.
 *
 * Mirrors the small subset of headunit-revived's `AppLog` API used by the ported files
 * (printf-style i/d/w/v/e), so those files port with minimal edits. Every line is also
 * forwarded to [sink] — which the app points at its diagnostics log — prefixed with the `[AA]` stage tag.
 */
object AaLog {
    const val TAG = "REauAA"

    /** Flip to true for very chatty per-message logging during bring-up debugging. */
    @Volatile var LOG_VERBOSE = false

    /** Set by the app; routes AA logs into its diagnostics log. */
    @Volatile var sink: ((String) -> Unit)? = null

    /**
     * What the phone is doing as far as the app knows (charger, screen, doze), for the line that says a link ended;
     * set by the app.
     */
    @Volatile var phoneNow: (() -> String)? = null

    @Suppress("SpreadOperator") // String.format has no non-vararg form
    private fun fmt(msg: String, args: Array<out Any?>): String {
        if (args.isEmpty()) return msg
        return try {
            String.format(java.util.Locale.US, msg, *args)
        } catch (_: Exception) {
            msg + " " + args.joinToString(" ") { it.toString() }
        }
    }

    /**
     * With a sink (the ride log), only warnings, errors and the verbose mode go to logcat too; otherwise the rest
     * would be written twice (the app's own logcat file copies it), thousands of lines in a ride.
     */
    private fun emit(msg: String) {
        val s = sink
        val warningOrError = msg.startsWith("W: ") || msg.startsWith("E: ")
        if (s == null || LOG_VERBOSE || warningOrError) Log.i(TAG, msg)
        try { s?.invoke("[AA] $msg") } catch (_: Exception) {}
    }

    fun i(msg: String, vararg args: Any?) = emit(fmt(msg, args))
    fun d(msg: String, vararg args: Any?) { if (LOG_VERBOSE) emit(fmt(msg, args)) }
    fun v(msg: String, vararg args: Any?) { if (LOG_VERBOSE) emit(fmt(msg, args)) }
    fun w(msg: String, vararg args: Any?) = emit("W: " + fmt(msg, args))

    fun e(msg: String, vararg args: Any?) {
        // If a Throwable was passed as the trailing arg (AppLog.e("msg", exception) style),
        // append its message/stack rather than feeding it to String.format.
        val tr = args.lastOrNull() as? Throwable
        if (tr != null) {
            val rest = args.copyOfRange(0, args.size - 1)
            emit("E: " + fmt(msg, rest) + " :: " + Log.getStackTraceString(tr))
        } else {
            emit("E: " + fmt(msg, args))
        }
    }

    fun e(tr: Throwable) = emit("E: " + Log.getStackTraceString(tr))
}
