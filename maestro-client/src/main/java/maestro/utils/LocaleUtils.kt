package maestro.utils

import maestro.Platform

open class LocaleValidationException(message: String): Exception(message)

class LocaleValidationIosException : LocaleValidationException("Failed to validate iOS device locale")
class LocaleValidationAndroidLanguageException(val language: String) : LocaleValidationException("Failed to validate Android device language")
class LocaleValidationAndroidCountryException(val country: String) : LocaleValidationException("Failed to validate Android device country")
class LocaleValidationNotSupportedPlatformException : LocaleValidationException("Failed to validate device locale - not supported platform provided")
class LocaleValidationWrongLocaleFormatException : LocaleValidationException("Failed to validate device locale - wrong locale format is used")

object LocaleUtils {
    val ANDROID_SUPPORTED_LANGUAGES = listOf(
        "ar" to "Arabic",
        "bg" to "Bulgarian",
        "ca" to "Catalan",
        "zh" to "Chinese",
        "hr" to "Croatian",
        "cs" to "Czech",
        "da" to "Danish",
        "nl" to "Dutch",
        "en" to "English",
        "fi" to "Finnish",
        "fr" to "French",
        "de" to "German",
        "el" to "Greek",
        "he" to "Hebrew",
        "hi" to "Hindi",
        "hu" to "Hungarian",
        "id" to "Indonesian",
        "it" to "Italian",
        "ja" to "Japanese",
        "ko" to "Korean",
        "lv" to "Latvian",
        "lt" to "Lithuanian",
        "nb" to "Norwegian-Bokmol",
        "pl" to "Polish",
        "pt" to "Portuguese",
        "ro" to "Romanian",
        "ru" to "Russian",
        "sr" to "Serbian",
        "sk" to "Slovak",
        "sl" to "Slovenian",
        "es" to "Spanish",
        "sv" to "Swedish",
        "tl" to "Tagalog",
        "th" to "Thai",
        "tr" to "Turkish",
        "uk" to "Ukrainian",
        "vi" to "Vietnamese"
    )

    val ANDROID_SUPPORTED_COUNTRIES = listOf(
        "AU" to "Australia",
        "AT" to "Austria",
        "BE" to "Belgium",
        "BR" to "Brazil",
        "GB" to "Britain",
        "BG" to "Bulgaria",
        "CA" to "Canada",
        "HR" to "Croatia",
        "CZ" to "Czech Republic",
        "DK" to "Denmark",
        "EG" to "Egypt",
        "FI" to "Finland",
        "FR" to "France",
        "DE" to "Germany",
        "GR" to "Greece",
        "HK" to "Hong-Kong",
        "HU" to "Hungary",
        "IN" to "India",
        "ID" to "Indonesia",
        "IE" to "Ireland",
        "IL" to "Israel",
        "IT" to "Italy",
        "JP" to "Japan",
        "KR" to "Korea",
        "LV" to "Latvia",
        "LI" to "Liechtenstein",
        "LT" to "Lithuania",
        "ES" to "Mexico",
        "NL" to "Netherlands",
        "NZ" to "New Zealand",
        "NO" to "Norway",
        "PH" to "Philippines",
        "PL" to "Poland",
        "PT" to "Portugal",
        "CN" to "PRC",
        "RO" to "Romania",
        "RU" to "Russia",
        "RS" to "Serbia",
        "SG" to "Singapore",
        "SK" to "Slovakia",
        "SI" to "Slovenia",
        "ES" to "Spain",
        "SE" to "Sweden",
        "CH" to "Switzerland",
        "TW" to "Taiwan",
        "TH" to "Thailand",
        "TR" to "Turkey",
        "UA" to "Ukraine",
        "US" to "US",
        "US" to "USA",
        "VN" to "Vietnam",
        "ZA" to "Zimbabwe"
    )

    val IOS_SUPPORTED_LOCALES = listOf(
        "en_AU" to "Australia",
        "nl_BE" to "Belgium (Dutch)",
        "fr_BE" to "Belgium (French)",
        "ms_BN" to "Brunei Darussalam",
        "en_CA" to "Canada (English)",
        "fr_CA" to "Canada (French)",
        "cs_CZ" to "Czech Republic",
        "fi_FI" to "Finland",
        "de_DE" to "Germany",
        "el_GR" to "Greece",
        "hu_HU" to "Hungary",
        "hi_IN" to "India",
        "id_ID" to "Indonesia",
        "he_IL" to "Israel",
        "it_IT" to "Italy",
        "ja_JP" to "Japan",
        "ms_MY" to "Malaysia",
        "nl_NL" to "Netherlands",
        "en_NZ" to "New Zealand",
        "nb_NO" to "Norway",
        "tl_PH" to "Philippines",
        "pl_PL" to "Poland",
        "zh_CN" to "PRC",
        "ro_RO" to "Romania",
        "ru_RU" to "Russia",
        "en_SG" to "Singapore",
        "sk_SK" to "Slovakia",
        "ko_KR" to "Korea",
        "sv_SE" to "Sweden",
        "zh_TW" to "Taiwan",
        "th_TH" to "Thailand",
        "tr_TR" to "Turkey",
        "en_GB" to "UK",
        "uk_UA" to "Ukraine",
        "es_US" to "USA (Spanish)",
        "en_US" to "USA (English)",
        "vi_VN" to "Vietnam",
        "pt-BR" to "Brazil",
        "zh-Hans" to "China (Simplified)",
        "zh-Hant" to "China (Traditional)",
        "zh-HK" to "Hong Kong",
        "en-IN" to "India (English)",
        "en-IE" to "Ireland",
        "es-419" to "Latin America",
        "es-MX" to "Mexico",
        "en-ZA" to "South Africa",
        "es_ES" to "Spain",
        "fr_FR" to "France",
    )

    fun findIOSLocale(language: String, country: String): String? {
        val searchedPair = "$language[_-]$country".toRegex()

        for (pair in IOS_SUPPORTED_LOCALES) {
            if (searchedPair.matches(pair.first)) {
                return pair.first
            }
        }

        return null
    }

    fun parseLocaleParams(deviceLocale: String, platform: Platform): Pair<String, String> {
        var parts = deviceLocale.split("_")

        if (parts.size == 2) {
            val language = parts[0]
            val country = parts[1]

            validateLocale(language, country, platform)

            return Pair(language, country)
        } else {
            parts = deviceLocale.split("-")

            if (parts.size == 2) {
                val language = parts[0]
                val country = parts[1]

                validateLocale(language, country, platform)

                return Pair(language, country)
            }

            throw LocaleValidationWrongLocaleFormatException()
        }
    }

    private fun validateLocale(language: String, country: String, platform: Platform) {
        when (platform) {
            Platform.IOS -> {
                if (findIOSLocale(language, country) == null) {
                    throw LocaleValidationIosException()
                }
            }
            Platform.ANDROID -> {
                if (!ANDROID_SUPPORTED_LANGUAGES.map { it.first }.contains(language)) {
                    throw LocaleValidationAndroidLanguageException(language)
                }

                if (!ANDROID_SUPPORTED_COUNTRIES.map { it.first }.contains(country)) {
                    throw LocaleValidationAndroidCountryException(country)
                }
            }
            else -> throw LocaleValidationNotSupportedPlatformException()
        }
    }
}