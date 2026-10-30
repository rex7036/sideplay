package io.github.jqssun.airplay

import android.app.Application
import android.content.Context
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AirPlayApp : Application() {

    override fun attachBaseContext(base: Context) {
        val languageCode = LanguageHelper.getCurrentLanguage(base)
        val context = LanguageHelper.applyLanguage(base, languageCode)
        super.attachBaseContext(context)
    }
}
