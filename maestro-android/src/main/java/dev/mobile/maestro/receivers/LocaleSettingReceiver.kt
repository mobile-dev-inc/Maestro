package dev.mobile.maestro.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.mobile.maestro.handlers.LocaleSettingHandler
import org.apache.commons.lang3.LocaleUtils
import java.util.*
import java.lang.Exception

class LocaleSettingReceiver : BroadcastReceiver(), HasAction {

    override fun onReceive(context: Context, intent: Intent) {
        var language = intent.getStringExtra(LANG)
        var country = intent.getStringExtra(COUNTRY)

        if (language == null || country == null) {
            Log.w(
                TAG, "It is required to provide both language and country, for example: " +
                        "am broadcast -a dev.mobile.maestro --es lang ja --es country JP"
            )
            Log.i(TAG, "Set en-US by default.")
            language = "en"
            country = "US"
        }

        var locale = Locale(language, country)

        Log.i(TAG, "Obtained locale: $locale")

        try {
            Log.i(TAG, "getting string extra for device locale")
            val script = intent.getStringExtra(SCRIPT)
            if (script != null) {
                Log.i(TAG, "setting script with device locale")
                Locale.Builder().setLocale(locale).setScript(script).build().also { locale = it }
                Log.i(TAG, "script set for device locale")
            }

            if (!LocaleUtils.isAvailableLocale(locale)) {
                val approximateMatchesLc = matchLocales(language, country)

                if (approximateMatchesLc.isNotEmpty() && script.isNullOrBlank()) {
                    Log.i(
                        TAG,
                        "The locale $locale is not known. Selecting the closest known one ${approximateMatchesLc[0]} instead"
                    )
                    locale = approximateMatchesLc[0]
                } else {
                    val approximateMatchesL = matchLocales(language)
                    if (approximateMatchesL.isEmpty()) {
                        Log.e(
                            TAG,
                            "The locale $locale is not known. Only the following locales are available: ${LocaleUtils.availableLocaleList()}"
                        )
                    } else {
                        Log.e(
                            TAG,
                            "The locale $locale is not known. " +
                                    "The following locales are available for the $language language: $approximateMatchesL" +
                                    "The following locales are available altogether: ${LocaleUtils.availableLocaleList()}"
                        )
                    }
                    resultCode = RESULT_LOCALE_NOT_VALID
                    resultData = "Failed to set locale $locale, the locale is not valid"
                    return
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to validate device locale", e)
            resultCode = RESULT_LOCALE_VALIDATION_FAILED
            resultData = "Failed to set locale $locale: ${e.message}"
        }

        try {
            LocaleSettingHandler(context).setLocale(locale)
            Log.i(TAG, "Set locale: $locale")
            resultCode = RESULT_SUCCESS
            resultData = locale.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set locale", e)
            resultCode = RESULT_UPDATE_CONFIGURATION_FAILED
            resultData = "Failed to set locale $locale, exception during updating configuration occurred: $e"
        }
    }

    private fun matchLocales(language: String): List<Locale> {
        val matches = ArrayList<Locale>()
        for (locale in LocaleUtils.availableLocaleList()) {
            if (locale.language == language) {
                matches.add(locale)
            }
        }
        return matches
    }

    private fun matchLocales(language: String, country: String): List<Locale> {
        val matches = ArrayList<Locale>()
        for (locale in LocaleUtils.availableLocaleList()) {
            if (locale.language == language &&
                locale.country == country
            ) {
                matches.add(locale)
            }
        }
        return matches
    }

    override fun action(): String {
        return ACTION
    }

    companion object {
        private const val LANG = "lang"
        private const val COUNTRY = "country"
        private const val SCRIPT = "script"
        private const val ACTION = "dev.mobile.maestro.locale"
        private const val TAG = "Maestro"

        private const val RESULT_SUCCESS = 0
        private const val RESULT_LOCALE_NOT_VALID = 1
        private const val RESULT_UPDATE_CONFIGURATION_FAILED = 2
        private const val RESULT_LOCALE_VALIDATION_FAILED = 3
    }
}