package com.reau.app

import android.app.LocaleManager
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import androidx.test.runner.AndroidJUnitRunner
import java.io.File

/**
 * The test runner: what REau keeps in its preferences (the settings, the dash's name and prefix, the rider's choices)
 * is put back as it was when the run ends, passed or failed. Run on a phone, a test that failed before its own tearDown
 * left the dash's prefix as the emulator's "Andr" and the test plan's settings in place of the rider's, and REau no
 * longer found the bike. A copy is kept beside the preferences during the run; one left by a run that died is put back
 * first.
 *
 * The tests read the app's texts in Portuguese (the app has English as its base now, and the emulator speaks English):
 * for the run the app's own language is Portuguese (Android 13 on, where the system holds it), and the language it had is
 * put back at the end the same way as the preferences are.
 */
class KeepAppStateRunner : AndroidJUnitRunner() {
    private lateinit var prefs: File
    private lateinit var kept: File
    private lateinit var keptLocales: File

    override fun onStart() {
        val data = targetContext.dataDir
        prefs = File(data, "shared_prefs")
        kept = File(data, "shared_prefs.kept-by-tests")
        keptLocales = File(data, "app-locales.kept-by-tests")
        speakPortuguese()
        if (kept.isDirectory) putBack()
        // A cleared app has no preferences folder yet.
        prefs.mkdirs()
        prefs.copyRecursively(kept, overwrite = true)
        // The terms of use are a gate in front of the whole app; the tests that open it go past it (the copy above puts the
        // rider's own answer back when the run ends). TermsScreenTest shows the gate with the screen itself.
        com.reau.app.settings.TermsStore.open(targetContext).accept()
        super.onStart()
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        // apply() writes on a thread of its own: a moment for the last ones, so they do not land on the copy put back.
        Thread.sleep(APPLY_SETTLE_MS)
        putBack()
        putBackLocales()
        super.finish(resultCode, results)
    }

    private fun speakPortuguese() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = targetContext.getSystemService(LocaleManager::class.java)
        // The language the app had stays in a file; one left by a run that died is the one to put back, not the Portuguese.
        if (!keptLocales.exists()) keptLocales.writeText(manager.applicationLocales.toLanguageTags())
        manager.applicationLocales = LocaleList.forLanguageTags("pt-BR")
    }

    private fun putBackLocales() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !keptLocales.exists()) return
        targetContext.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(keptLocales.readText())
        keptLocales.delete()
    }

    private fun putBack() {
        prefs.deleteRecursively()
        kept.copyRecursively(prefs, overwrite = true)
        kept.deleteRecursively()
    }

    private companion object {
        const val APPLY_SETTLE_MS = 500L
    }
}
