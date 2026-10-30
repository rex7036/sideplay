package io.github.jqssun.airplay

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * Language utility for managing app language settings
 */
object LanguageHelper {

    /**
     * Apply language setting to the given context
     * @param context Application or Activity context
     * @param languageCode Language code from Prefs (system/en/zh)
     * @return Context with updated configuration
     */
    fun applyLanguage(context: Context, languageCode: String): Context {
        val locale = when (languageCode) {
            Prefs.LANG_ENGLISH -> Locale("en")
            Prefs.LANG_CHINESE -> Locale("zh")
            else -> {
                // Follow system language
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    context.resources.configuration.locales[0]
                } else {
                    @Suppress("DEPRECATION")
                    context.resources.configuration.locale
                }
            }
        }

        return updateContextLocale(context, locale)
    }

    /**
     * Update context configuration with the given locale
     */
    private fun updateContextLocale(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            context
        }
    }

    /**
     * Get current language setting from preferences
     */
    fun getCurrentLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        return prefs.getString(Prefs.APP_LANGUAGE, Prefs.DEF_APP_LANGUAGE) ?: Prefs.DEF_APP_LANGUAGE
    }

    /**
     * Save language setting to preferences
     */
    fun setLanguage(context: Context, languageCode: String) {
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(Prefs.APP_LANGUAGE, languageCode).apply()
    }

    /**
     * Get display name for language code
     */
    fun getLanguageDisplayName(context: Context, languageCode: String): String {
        return when (languageCode) {
            Prefs.LANG_SYSTEM -> context.getString(R.string.language_system)
            Prefs.LANG_ENGLISH -> context.getString(R.string.language_english)
            Prefs.LANG_CHINESE -> context.getString(R.string.language_chinese)
            else -> context.getString(R.string.language_system)
        }
    }
}
