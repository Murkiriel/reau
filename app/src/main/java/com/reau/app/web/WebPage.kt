package com.reau.app.web

import android.content.Context

/**
 * The page the server gives: `assets/web/index.html` with the JMuxer library (MIT, `jmuxer.LICENSE` beside it)
 * written inside it, so the browser makes one request. The page holds no secret: it asks for the PIN.
 */
object WebPage {
    /** Where the template wants the library. */
    const val LIBRARY_MARK = "<!--JMUXER-->"

    /** The template with the [library] in a script; a library that says `</script` cannot end it early. */
    fun compose(template: String, library: String): String =
        template.replace(LIBRARY_MARK, "<script>" + library.replace("</script", "<\\/script") + "</script>")

    /** The page as the server serves it, read from the app's assets. */
    fun load(context: Context): ByteArray {
        fun read(name: String) = context.assets.open("web/$name").use { it.readBytes().toString(Charsets.UTF_8) }
        return compose(read("index.html"), read("jmuxer.min.js")).toByteArray(Charsets.UTF_8)
    }
}
