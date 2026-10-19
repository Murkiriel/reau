package com.reau.app.card

import android.content.Context
import androidx.core.net.toUri
import android.provider.OpenableColumns
import java.net.URLDecoder

/**
 * The short name the screens show for the picture the rider picked: its file's own name as the provider says it
 * ("cartela.png"), else the last part of its content URI.
 */
object CardName {
    // The last name the provider gave, by its URI: the screens ask at every recomposition.
    @Volatile private var last: Pair<String, String>? = null

    /** The picture's file name, with its extension, as its provider tells; [of] when it tells nothing. */
    fun shown(context: Context, uri: String): String = shown(uri) { displayName(context, it) }

    /** [shown] with the provider's answer from [lookup]; only a name it gave is kept for the next call. */
    internal fun shown(uri: String, lookup: (String) -> String?): String {
        val name = last?.takeIf { it.first == uri }?.second
            ?: runCatching { lookup(uri) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.also { last = uri to it }
        return name ?: of(uri)
    }

    fun of(uri: String): String {
        // The String charset form: the Charset one needs API 33.
        val decoded = runCatching { URLDecoder.decode(uri, "UTF-8") }.getOrDefault(uri)
        // Document URIs end in "<root>:<path>": a folder path ends in the file name, a gallery id is all there is.
        return decoded.substringAfterLast('/').substringAfterLast(':').ifEmpty { uri }
    }

    private fun displayName(context: Context, uri: String): String? =
        context.contentResolver.query(uri.toUri(), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
}
