package com.reau.app.data

import android.content.Context
import java.io.File

/**
 * Where the logs (`diag`) and the debug recordings (`video`, `sessions`) live. In the app's internal storage: other
 * apps cannot read it, and neither can a computer the phone is plugged into (the external `Android/data/com.reau.app`
 * folder it replaces could be copied over USB). They leave the phone only by "Compartilhar registros"; a debug APK can
 * still be read by `adb shell run-as com.reau.app`. What an older version left in the external folder is moved here
 * once, when the app starts ([migrate]).
 */
object AppFolders {
    val NAMES = listOf("diag", "video", "sessions")

    fun diag(context: Context): File = File(context.filesDir, "diag")

    fun recordings(context: Context, name: String): File = File(context.filesDir, name)

    /** Moves what an older version left in the external folder; how many files were moved. */
    fun migrate(context: Context): Int {
        val external = context.getExternalFilesDir(null) ?: return 0
        return NAMES.sumOf { move(File(external, it), File(context.filesDir, it)) }
    }

    /**
     * Moves every file of [from] into [to] (copied, then deleted: the two may be different volumes), and [from] goes; a
     * name already in [to] stays, the moved one taking "moved-" before its name. How many files were moved.
     */
    fun move(from: File, to: File): Int {
        val files = from.listFiles()?.filter { it.isFile }.orEmpty()
        if (files.isEmpty()) {
            from.delete()
            return 0
        }
        to.mkdirs()
        var moved = 0
        for (f in files) {
            val target = File(to, f.name).let { if (it.exists()) File(to, "moved-${f.name}") else it }
            val ok = runCatching { f.copyTo(target, overwrite = true) }.isSuccess
            if (ok && f.delete()) moved++
        }
        from.delete()
        return moved
    }
}
