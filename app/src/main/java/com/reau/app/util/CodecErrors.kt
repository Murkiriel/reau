package com.reau.app.util

import android.media.MediaCodec

/**
 * What a failed codec says, for the ride log. A [MediaCodec.CodecException] carries more than its message: the error code, whether
 * the failure is recoverable (reset the codec) or transient (try again later; the codec's resources were taken for a moment) and a
 * diagnostic string from the codec's own driver. The log used to keep the message only; a vendor driver that fails on one phone in a
 * hundred is told apart by exactly these. Every one is from API 23, under the app's minimum (29), so none needs a check.
 *
 * [describe] works on any throwable (the state errors a codec throws are plain `IllegalStateException`s, and the encoder's pump wraps
 * the codec's exception in one: the cause chain is looked through), and the formatting is pure, so the JVM tests cover it.
 */
object CodecErrors {
    /** The fields of a [MediaCodec.CodecException]. */
    data class Detail(val errorCode: Int, val recoverable: Boolean, val transient: Boolean, val diagnosticInfo: String?)

    /** `IllegalStateException: message [codec=c2.qti.avc.encoder errorCode=1100(insufficient resource) recoverable=false transient=true diagnostic=…]`. */
    fun describe(error: Throwable, codecName: String? = null): String =
        describe(error.javaClass.simpleName, error.message, detailOf(error), codecName)

    internal fun describe(type: String, message: String?, detail: Detail?, codecName: String?): String {
        val parts = buildList {
            if (codecName != null) add("codec=$codecName")
            if (detail != null) {
                add("errorCode=${detail.errorCode}${codeName(detail.errorCode)}")
                add("recoverable=${detail.recoverable}")
                add("transient=${detail.transient}")
                add("diagnostic=${detail.diagnosticInfo?.trim()?.take(MAX_DIAGNOSTIC)?.ifEmpty { null } ?: "-"}")
            }
        }
        val head = if (message.isNullOrEmpty()) type else "$type: $message"
        return if (parts.isEmpty()) head else "$head [${parts.joinToString(" ")}]"
    }

    /** The codec's exception in [error] or its causes (a few levels: the pump wraps it once), or null when it is none. */
    fun detailOf(error: Throwable?): Detail? {
        var current = error
        var depth = 0
        while (current != null && depth < MAX_CAUSES) {
            if (current is MediaCodec.CodecException) return Detail(current.errorCode, current.isRecoverable, current.isTransient, current.diagnosticInfo)
            current = current.cause
            depth++
        }
        return null
    }

    /** The two named codes of [MediaCodec.CodecException]; the others are the driver's own (a negative one is a native status). */
    private fun codeName(code: Int): String = when (code) {
        ERROR_INSUFFICIENT_RESOURCE -> "(insufficient resource)"
        ERROR_RECLAIMED -> "(reclaimed)"
        else -> ""
    }

    private const val ERROR_INSUFFICIENT_RESOURCE = MediaCodec.CodecException.ERROR_INSUFFICIENT_RESOURCE
    private const val ERROR_RECLAIMED = MediaCodec.CodecException.ERROR_RECLAIMED
    private const val MAX_CAUSES = 4
    private const val MAX_DIAGNOSTIC = 160
}
