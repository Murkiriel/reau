package com.reau.app.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

/** The language of the app's texts: the phone's own, or one of the two the app has. */
enum class AppLanguage(val tag: String) {
    AUTO(""),
    PT("pt-BR"),
    EN("en"),
}

/**
 * Where the rider's choice of language lives. From Android 13 the system keeps it (the app's own language of the system
 * settings and this choice are the same one, and the system recreates the screens); before that REau keeps it in its
 * preferences and puts the language on the context of its screens and its service ([wrap]).
 */
object AppLanguages {
    private const val PREFS = "reau_language"
    private const val KEY = "tag"

    /** The language a tag stands for: anything Portuguese is PT, anything English is EN, the rest (and none) AUTO. */
    fun fromTag(tag: String?): AppLanguage = when {
        tag.isNullOrBlank() -> AppLanguage.AUTO
        tag.startsWith("pt", ignoreCase = true) -> AppLanguage.PT
        tag.startsWith("en", ignoreCase = true) -> AppLanguage.EN
        else -> AppLanguage.AUTO
    }

    fun current(context: Context): AppLanguage = fromTag(savedTag(context))

    /** Applies [language] now: the screens are redrawn in it. */
    fun set(activity: Activity, language: AppLanguage) {
        if (current(activity) == language) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales = localeList(language)
        } else {
            prefs(activity).edit { putString(KEY, language.tag) }
            activity.recreate()
        }
    }

    /**
     * Before Android 13: the context with the chosen language, for the screens and the service ([attachBaseContext]). The
     * app is one APK with both languages in it, not a bundle that downloads languages, so the Play Core warning does not apply.
     */
    @android.annotation.SuppressLint("AppBundleLocaleChanges")
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val language = fromTag(prefs(base).getString(KEY, null))
        if (language == AppLanguage.AUTO) return base
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(language.tag))
        return base.createConfigurationContext(configuration)
    }

    internal fun localeList(language: AppLanguage): LocaleList =
        if (language == AppLanguage.AUTO) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language.tag)

    private fun savedTag(context: Context): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()
        } else {
            prefs(context).getString(KEY, null)
        }

    private fun prefs(context: Context) = (context.applicationContext ?: context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
