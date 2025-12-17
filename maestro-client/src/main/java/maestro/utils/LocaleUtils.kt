package maestro.utils

import maestro.Platform
import java.util.Locale

open class LocaleValidationException(message: String): Exception(message)

// IOS
class LocaleValidationIosException : LocaleValidationException("Failed to validate iOS device locale")

// Android
class LocaleValidationAndroidLanguageException(val language: String) : LocaleValidationException("Failed to validate Android device language: $language")
class LocaleValidationAndroidCountryException(val country: String) : LocaleValidationException("Failed to validate Android device country: $country")
class LocaleValidationAndroidLocaleCombinationException(val locale: String) : LocaleValidationException("Failed to validate Android device locale combination: $locale (not a valid Java Locale combination)")

// Web
class LocaleValidationWebException(val locale: String) : LocaleValidationException("Failed to validate web browser iOS: $locale")

// Common
class LocaleValidationWrongLocaleFormatException(val invalidLocale: String) : LocaleValidationException("Failed to validate device locale: $invalidLocale is not a valid locale")


const val defaultLocaleValue = "en_US"

/**
 * Type-safe enum representing Android-supported languages.
 * These are a subset of languages available in Java's Locale API.
 * Display names are dynamically retrieved from Java's Locale.getDisplayLanguage().
 */
enum class AndroidSupportedLanguage(val code: String) {
  ARABIC("ar"),
  BULGARIAN("bg"),
  CATALAN("ca"),
  CHINESE("zh"),
  CROATIAN("hr"),
  CZECH("cs"),
  DANISH("da"),
  DUTCH("nl"),
  ENGLISH("en"),
  FINNISH("fi"),
  FRENCH("fr"),
  GERMAN("de"),
  GREEK("el"),
  HEBREW("he"),
  HINDI("hi"),
  HUNGARIAN("hu"),
  INDONESIAN("id"),
  ITALIAN("it"),
  JAPANESE("ja"),
  KOREAN("ko"),
  LATVIAN("lv"),
  LITHUANIAN("lt"),
  NORWEGIAN_BOKMOL("nb"),
  POLISH("pl"),
  PORTUGUESE("pt"),
  ROMANIAN("ro"),
  RUSSIAN("ru"),
  SERBIAN("sr"),
  SLOVAK("sk"),
  SLOVENIAN("sl"),
  SPANISH("es"),
  SWEDISH("sv"),
  TAGALOG("tl"),
  THAI("th"),
  TURKISH("tr"),
  UKRAINIAN("uk"),
  VIETNAMESE("vi");

  /**
   * Gets the display name for this language using Java's Locale API.
   * Falls back to the enum name if Locale doesn't recognize the code.
   */
  fun getDisplayName(locale: Locale = Locale.US): String {
    return try {
      Locale(code).getDisplayLanguage(locale).takeIf { it.isNotBlank() }
        ?: code.uppercase()
    } catch (e: Exception) {
      code.uppercase()
    }
  }

  companion object {
    init {
      // Validate that all enum codes are valid ISO-639-1 language codes
      val validISOCodes = Locale.getISOLanguages().toSet()
      entries.forEach { language ->
        require(language.code in validISOCodes) {
          "Language code '${language.code}' in AndroidSupportedLanguage enum is not a valid ISO-639-1 code"
        }
      }
    }

    /**
     * Gets all language codes as a set.
     */
    fun getLanguageCodes(): Set<String> {
      return entries.map { it.code }.toSet()
    }

    /**
     * Finds a language by its code.
     */
    fun fromCode(code: String): AndroidSupportedLanguage? {
      return entries.find { it.code == code }
    }
  }
}


/**
 * Type-safe enum representing Android-supported countries.
 * These are a subset of countries available in Java's Locale API.
 * Display names are dynamically retrieved from Java's Locale.getDisplayCountry().
 */
enum class AndroidSupportedCountry(val code: String) {
  AUSTRALIA("AU"),
  AUSTRIA("AT"),
  BELGIUM("BE"),
  BRAZIL("BR"),
  BRITAIN("GB"),
  BULGARIA("BG"),
  CANADA("CA"),
  CROATIA("HR"),
  CZECH_REPUBLIC("CZ"),
  DENMARK("DK"),
  EGYPT("EG"),
  FINLAND("FI"),
  FRANCE("FR"),
  GERMANY("DE"),
  GREECE("GR"),
  HONG_KONG("HK"),
  HUNGARY("HU"),
  INDIA("IN"),
  INDONESIA("ID"),
  IRELAND("IE"),
  ISRAEL("IL"),
  ITALY("IT"),
  JAPAN("JP"),
  KOREA("KR"),
  LATVIA("LV"),
  LIECHTENSTEIN("LI"),
  LITHUANIA("LT"),
  MEXICO("MX"), // Note: Original list had "ES" for Mexico, but MX is the correct ISO code
  NETHERLANDS("NL"),
  NEW_ZEALAND("NZ"),
  NORWAY("NO"),
  PHILIPPINES("PH"),
  POLAND("PL"),
  PORTUGAL("PT"),
  PRC("CN"),
  ROMANIA("RO"),
  RUSSIA("RU"),
  SERBIA("RS"),
  SINGAPORE("SG"),
  SLOVAKIA("SK"),
  SLOVENIA("SI"),
  SPAIN("ES"),
  SWEDEN("SE"),
  SWITZERLAND("CH"),
  TAIWAN("TW"),
  THAILAND("TH"),
  TURKEY("TR"),
  UKRAINE("UA"),
  USA("US"),
  VIETNAM("VN"),
  ZIMBABWE("ZA");

  /**
   * Gets the display name for this country using Java's Locale API.
   * Falls back to the enum name if Locale doesn't recognize the code.
   */
  fun getDisplayName(locale: Locale = Locale.US): String {
    return try {
      Locale("", code).getDisplayCountry(locale).takeIf { it.isNotBlank() }
        ?: code
    } catch (e: Exception) {
      code
    }
  }

  companion object {
    init {
      // Validate that all enum codes are valid ISO-3166-1 country codes
      val validISOCodes = Locale.getISOCountries().toSet()
      entries.forEach { country ->
        require(country.code in validISOCodes) {
          "Country code '${country.code}' in AndroidSupportedCountry enum is not a valid ISO-3166-1 code"
        }
      }
    }

    /**
     * Gets all country codes as a set.
     */
    fun getCountryCodes(): Set<String> {
      return entries.map { it.code }.toSet()
    }

    /**
     * Finds a country by its code.
     */
    fun fromCode(code: String): AndroidSupportedCountry? {
      return entries.find { it.code == code }
    }
  }
}

/**
 * Type-safe enum representing iOS-supported locale combinations.
 * These are a subset of locale combinations available in Java's Locale API,
 * plus some iOS-specific locale formats (like zh-Hans, zh-Hant, es-419).
 * Display names are dynamically retrieved from Java's Locale.getDisplayName() where possible.
 */
enum class IosSupportedLocale(val localeString: String) {
  // Standard locales (language_country format)
  EN_AU("en_AU"),
  NL_BE("nl_BE"),
  FR_BE("fr_BE"),
  MS_BN("ms_BN"),
  EN_CA("en_CA"),
  FR_CA("fr_CA"),
  CS_CZ("cs_CZ"),
  FI_FI("fi_FI"),
  DE_DE("de_DE"),
  EL_GR("el_GR"),
  HU_HU("hu_HU"),
  HI_IN("hi_IN"),
  ID_ID("id_ID"),
  HE_IL("he_IL"),
  IT_IT("it_IT"),
  JA_JP("ja_JP"),
  MS_MY("ms_MY"),
  NL_NL("nl_NL"),
  EN_NZ("en_NZ"),
  NB_NO("nb_NO"),
  TL_PH("tl_PH"),
  PL_PL("pl_PL"),
  ZH_CN("zh_CN"),
  RO_RO("ro_RO"),
  RU_RU("ru_RU"),
  EN_SG("en_SG"),
  SK_SK("sk_SK"),
  KO_KR("ko_KR"),
  SV_SE("sv_SE"),
  ZH_TW("zh_TW"),
  TH_TH("th_TH"),
  TR_TR("tr_TR"),
  EN_GB("en_GB"),
  UK_UA("uk_UA"),
  ES_US("es_US"),
  EN_US("en_US"),
  VI_VN("vi_VN"),
  ES_ES("es_ES"),
  FR_FR("fr_FR"),

  // Hyphenated locales (language-country format)
  PT_BR("pt-BR"),
  ZH_HK("zh-HK"),
  EN_IN("en-IN"),
  EN_IE("en-IE"),
  ES_MX("es-MX"),
  EN_ZA("en-ZA"),

  // iOS-specific locale formats (not standard ISO locale combinations)
  ZH_HANS("zh-Hans"),
  ZH_HANT("zh-Hant"),
  ES_419("es-419");

  /**
   * Gets the display name for this locale using Java's Locale API where possible.
   * For iOS-specific formats (zh-Hans, zh-Hant, es-419), returns a custom display name.
   */
  fun getDisplayName(locale: Locale = Locale.US): String {
    return when (this) {
      ZH_HANS -> "Chinese (Simplified)"
      ZH_HANT -> "Chinese (Traditional)"
      ES_419 -> "Spanish (Latin America)"
      else -> {
        // Try to parse as standard locale
        try {
          val parts = localeString.split("_", "-")
          if (parts.size == 2) {
            val javaLocale = Locale(parts[0], parts[1])
            val displayName = javaLocale.getDisplayName(locale)
            if (displayName.isNotBlank()) {
              return displayName
            }
          }
        } catch (e: Exception) {
          // Fall through to return locale string
        }
        localeString
      }
    }
  }

  companion object {
    init {
      // Validate that language and country codes are valid ISO codes
      // Note: We validate codes separately, not the exact combination, because iOS supports
      // some locale combinations that may not exist in Java's Locale.getAvailableLocales()
      val validISOLanguages = Locale.getISOLanguages().toSet()
      val validISOCountries = Locale.getISOCountries().toSet()

      entries.forEach { iosLocale ->
        // Skip validation for iOS-specific formats
        if (iosLocale !in listOf(ZH_HANS, ZH_HANT, ES_419)) {
          val parts = iosLocale.localeString.split("_", "-")
          if (parts.size == 2) {
            val language = parts[0]
            val country = parts[1]

            // Special case: "419" is a UN M.49 region code for Latin America, not ISO-3166-1
            if (country != "419") {
              require(language in validISOLanguages) {
                "Language code '$language' in locale '${iosLocale.localeString}' is not a valid ISO-639-1 code"
              }

              require(country in validISOCountries) {
                "Country code '$country' in locale '${iosLocale.localeString}' is not a valid ISO-3166-1 code"
              }
            } else {
              // For es-419, just validate the language
              require(language in validISOLanguages) {
                "Language code '$language' in locale '${iosLocale.localeString}' is not a valid ISO-639-1 code"
              }
            }
          }
        }
      }
    }

    /**
     * Gets all locale strings as a set.
     */
    fun getLocaleStrings(): Set<String> {
      return entries.map { it.localeString }.toSet()
    }

    /**
     * Finds a locale by its string representation.
     */
    fun fromLocaleString(localeString: String? = defaultLocaleValue): IosSupportedLocale? {
      return entries.find {
        it.localeString == localeString ||
                it.localeString.replace("_", "-") == localeString ||
                it.localeString.replace("-", "_") == localeString
      }
    }
  }
}



/**
 * Type-safe enum representing Web-supported locale combinations.
 * These are a subset of locale combinations available in Java's Locale API,
 * Display names are dynamically retrieved from Java's Locale.getDisplayName() where possible.
 */
enum class WebSupportedLocale(val localeString: String) {
  // Standard locales (language_country format)
  EN_US("en_US");

  /**
   * Gets the display name for this locale using Java's Locale API where possible.
   */
  fun getDisplayName(locale: Locale = Locale.US): String {
    // Try to parse as standard locale
    try {
      val parts = localeString.split("_", "-")
      if (parts.size == 2) {
        val javaLocale = Locale(parts[0], parts[1])
        val displayName = javaLocale.getDisplayName(locale)
        if (displayName.isNotBlank()) {
          return displayName
        }
      }
    } catch (e: Exception) {
      // Fall through to return locale string
    }
    return localeString
  }

  companion object {
    init {
      // Validate that language and country codes are valid ISO codes
      val validISOLanguages = Locale.getISOLanguages().toSet()
      val validISOCountries = Locale.getISOCountries().toSet()

      entries.forEach { webLocale ->
        val parts = webLocale.localeString.split("_", "-")
        if (parts.size == 2) {
          val language = parts[0]
          val country = parts[1]

          // Special case: "419" is a UN M.49 region code for Latin America, not ISO-3166-1
          if (country != "419") {
            require(language in validISOLanguages) {
              "Language code '$language' in locale '${webLocale.localeString}' is not a valid ISO-639-1 code"
            }

            require(country in validISOCountries) {
              "Country code '$country' in locale '${webLocale.localeString}' is not a valid ISO-3166-1 code"
            }
          } else {
            // For es-419, just validate the language
            require(language in validISOLanguages) {
              "Language code '$language' in locale '${webLocale.localeString}' is not a valid ISO-639-1 code"
            }
          }
        }
      }
    }

    /**
     * Gets all locale strings as a set.
     */
    fun getLocaleStrings(): Set<String> {
      return entries.map { it.localeString }.toSet()
    }

    /**
     * Finds a locale by its string representation.
     */
    fun fromLocaleString(localeString: String? = defaultLocaleValue): WebSupportedLocale? {
      return entries.find {
        it.localeString == localeString
      }
    }
  }
}

/**
 * Unified sealed class representing device locales for all platforms.
 * This provides a type-safe way to represent and validate locales across Android and iOS.
 */
sealed class LocaleUtils {
  /**
   * Gets the locale string representation (e.g., "en_US", "zh-Hans").
   */
  abstract val localeString: String

  /**
   * Gets the display name for this locale.
   */
  abstract fun getDisplayName(locale: Locale = Locale.US): String

  /**
   * Gets the language code for this locale (e.g., "en", "fr", "zh").
   */
  abstract fun getLanguageCode(): String

  /**
   * Gets the country code for this locale (e.g., "US", "FR", "CN").
   * Returns null for locales that don't have a country code (e.g., iOS-specific formats like "zh-Hans").
   */
  abstract fun getCountryCode(): String?

  /**
   * Gets the platform this locale is for.
   */
  abstract val platform: Platform
  /**
   * Android device locale - a dynamic combination of language and country.
   * Android supports all combinations of supported languages and countries.
   */
  data class Android(
    val language: AndroidSupportedLanguage,
    val country: AndroidSupportedCountry
  ) : LocaleUtils() {
    override val localeString: String
      get() = "${language.code}_${country.code}"

    override fun getDisplayName(locale: Locale): String {
      return try {
        Locale(language.code, country.code).getDisplayName(locale)
      } catch (e: Exception) {
        "${language.getDisplayName(locale)} (${country.getDisplayName(locale)})"
      }
    }

    override fun getLanguageCode(): String {
      return language.code
    }

    override fun getCountryCode(): String {
      return country.code
    }

    override val platform: Platform = Platform.ANDROID

    companion object {
      /**
       * Cached set of available Java Locale combinations (language_country format).
       * Computed once and reused for efficient O(1) lookups.
       */
      private val availableJavaLocaleCombinations: Set<String> = Locale.getAvailableLocales()
        .map { "${it.language}_${it.country}" }
        .toSet()

      /**
       * Creates an Android DeviceLocale from a locale string (e.g., "en_US").
       * Validates that both language and country codes are supported.
       *
       * @throws LocaleValidationException if the locale string is invalid or unsupported
       */
      fun fromLocaleString(localeString: String? = defaultLocaleValue): Android {
        val parts = localeString?.split("_", "-")
        if (parts?.size != 2) {
          throw LocaleValidationWrongLocaleFormatException(
            "$localeString (expected format: language_country, e.g., en_US)"
          )
        }

        val languageCode = parts[0]
        val countryCode = parts[1]

        val language = AndroidSupportedLanguage.fromCode(languageCode)
          ?: throw LocaleValidationAndroidLanguageException(
            "$languageCode (not a supported Android language)"
          )

        val country = AndroidSupportedCountry.fromCode(countryCode)
          ?: throw LocaleValidationAndroidCountryException(
            "$countryCode (not a supported Android country)"
          )

        // Validate that the language-country combination exists in Java Locale
        if ("${languageCode}_${countryCode}" !in availableJavaLocaleCombinations) {
          throw LocaleValidationAndroidLocaleCombinationException(
            "$localeString (language-country combination is not valid)"
          )
        }

        return Android(language, country)
      }

      /**
       * Validates if a locale string represents a valid Android locale combination.
       */
      fun isValidLocaleString(localeString: String): Boolean {
        return try {
          fromLocaleString(localeString)
          true
        } catch (e: LocaleValidationException) {
          false
        }
      }

      /**
       * Generates all valid Android locale combinations dynamically.
       * This creates all combinations of supported languages and countries.
       */
      fun getAllCombinations(): List<Android> {
        return AndroidSupportedLanguage.entries.flatMap { language ->
          AndroidSupportedCountry.entries.map { country ->
            Android(language, country)
          }
        }
      }

      /**
       * Gets all locale strings as a set.
       */
      fun getAllLocaleStrings(): Set<String> {
        return getAllCombinations().map { it.localeString }.toSet()
      }

      /**
       * Finds a locale string given language and country codes for Android.
       * 
       * @param languages Language code (e.g., "en", "fr", "zh")
       * @param country Country code (e.g., "US", "FR", "CN")
       * @return Locale string if found (e.g., "en_US"), null otherwise
       */
      fun findLocale(languages: String, country: String): String? {
        return if (isValidLocaleString("${languages}_${country}")) {
          "${languages}_${country}"
        } else {
          null
        }
      }
    }
  }

  /**
   * iOS device locale - uses the predefined iOS supported locale enum.
   */
  data class Ios(
    val locale: IosSupportedLocale
  ) : LocaleUtils() {
    override val localeString: String
      get() = locale.localeString

    override fun getDisplayName(locale: Locale): String {
      return this.locale.getDisplayName(locale)
    }

    override fun getLanguageCode(): String {
      val parts = localeString.split("_", "-")
      return parts[0]
    }

    override fun getCountryCode(): String? {
      val parts = localeString.split("_", "-")
      if (parts.size == 2) {
        val country = parts[1]
        // iOS-specific formats like "zh-Hans", "zh-Hant", "es-419" don't have standard country codes
        if (country == "Hans" || country == "Hant" || country == "419") {
          return null
        }
        return country
      }
      return null
    }

    override val platform: Platform = Platform.IOS

    companion object {
      /**
       * Creates an iOS DeviceLocale from a locale string (e.g., "en_US", "zh-Hans").
       * Validates that the locale is supported on iOS.
       *
       * @throws LocaleValidationException if the locale string is invalid or unsupported
       */
      fun fromLocaleString(localeString: String? = defaultLocaleValue): Ios {
        val iosLocale = IosSupportedLocale.fromLocaleString(localeString)
          ?: throw LocaleValidationIosException()
        return Ios(iosLocale)
      }

      /**
       * Validates if a locale string represents a valid iOS locale.
       */
      fun isValidLocaleString(localeString: String): Boolean {
        return IosSupportedLocale.fromLocaleString(localeString) != null
      }

      /**
       * Gets all iOS supported locales.
       */
      fun getAllLocales(): List<Ios> {
        return IosSupportedLocale.entries.map { Ios(it) }
      }

      /**
       * Gets all locale strings as a set.
       */
      fun getAllLocaleStrings(): Set<String> {
        return IosSupportedLocale.getLocaleStrings()
      }

      /**
       * Finds a locale string given language and country codes for iOS.
       * Tries both underscore and hyphen formats.
       * 
       * @param languages Language code (e.g., "en", "fr", "zh")
       * @param country Country code (e.g., "US", "FR", "CN")
       * @return Locale string if found (e.g., "en_US" or "en-US"), null otherwise
       */
      fun findLocale(languages: String, country: String): String? {
        // Try underscore format first
        val underscoreFormat = "${languages}_$country"
        if (isValidLocaleString(underscoreFormat)) {
          return underscoreFormat
        }

        // Try hyphen format
        val hyphenFormat = "$languages-$country"
        if (isValidLocaleString(hyphenFormat)) {
          return hyphenFormat
        }

        return null
      }
    }
  }

  /**
   * Web device locale - uses the predefined Web supported locale enum.
   */
  data class Web(
    val locale: WebSupportedLocale
  ) : LocaleUtils() {
    override val localeString: String
      get() = locale.localeString

    override fun getDisplayName(locale: Locale): String {
      return this.locale.getDisplayName(locale)
    }

    override fun getLanguageCode(): String {
      val parts = localeString.split("_", "-")
      return parts[0]
    }

    override fun getCountryCode(): String? {
      val parts = localeString.split("_", "-")
      if (parts.size == 2) {
        val country = parts[1]
        return country
      }
      return null
    }

    override val platform: Platform = Platform.WEB

    companion object {
      /**
       * Creates an iOS DeviceLocale from a locale string (e.g., "en_US", "zh-Hans").
       * Validates that the locale is supported on iOS.
       *
       * @throws LocaleValidationException if the locale string is invalid or unsupported
       */
      fun fromLocaleString(localeString: String? = defaultLocaleValue): Web {
        val webLocale = WebSupportedLocale.fromLocaleString(localeString)
          ?: throw LocaleValidationWebException(localeString ?: "null")
        return Web(webLocale)
      }

      /**
       * Validates if a locale string represents a valid Web locale.
       */
      fun isValidLocaleString(localeString: String): Boolean {
        return WebSupportedLocale.fromLocaleString(localeString) != null
      }

      /**
       * Gets all Web supported locales.
       */
      fun getAllLocales(): List<Web> {
        return WebSupportedLocale.entries.map { Web(it) }
      }

      /**
       * Gets all locale strings as a set.
       */
      fun getAllLocaleStrings(): Set<String> {
        return WebSupportedLocale.getLocaleStrings()
      }

      /**
       * Finds a locale string given language and country codes for Web.
       *
       * @param languages Language code (e.g., "en", "fr", "zh")
       * @param country Country code (e.g., "US", "FR", "CN")
       * @return Locale string if found (e.g., "en_US"), null otherwise
       */
      fun findLocale(languages: String, country: String): String? {
        // Try underscore format first
        val underscoreFormat = "${languages}_$country"
        if (isValidLocaleString(underscoreFormat)) {
          return underscoreFormat
        }

        return null
      }
    }
  }

  companion object {
    /**
     * Creates a DeviceLocale from a locale string and platform.
     *
     * @throws LocaleValidationException if the locale string is invalid or unsupported
     */
    fun fromLocaleString(localeString: String? = defaultLocaleValue, platform: Platform): LocaleUtils {
      return when (platform) {
        Platform.ANDROID -> Android.fromLocaleString(localeString)
        Platform.IOS -> Ios.fromLocaleString(localeString)
        Platform.WEB -> Web.fromLocaleString(localeString)
      }
    }

    /**
     * Validates if a locale string is valid for the given platform.
     */
    fun isValidLocaleString(localeString: String, platform: Platform): Boolean {
      return try {
        fromLocaleString(localeString, platform)
        true
      } catch (e: LocaleValidationException) {
        false
      }
    }

    /**
     * Gets all supported locales for a platform.
     */
    fun getAllLocales(platform: Platform): List<LocaleUtils> {
      return when (platform) {
        Platform.ANDROID -> Android.getAllCombinations()
        Platform.IOS -> Ios.getAllLocales()
        Platform.WEB -> Web.getAllLocales()
      }
    }

    /**
     * Gets all supported locales string for a platform.
     */
    fun getAllLocalesString(platform: Platform): Set<String> {
      return when (platform) {
        Platform.ANDROID -> Android.getAllLocaleStrings()
        Platform.IOS -> Ios.getAllLocaleStrings()
        Platform.WEB -> Web.getAllLocaleStrings()
      }
    }

    /**
     * Finds a locale string given language and country codes for the specified platform.
     *
     * @param languages Language code (e.g., "en", "fr", "zh")
     * @param country Country code (e.g., "US", "FR", "CN")
     * @param platform Platform to search for locale (ANDROID, IOS, or WEB)
     * @return Locale string if found (e.g., "en_US" or "en-US"), null otherwise
     */
    fun findLocale(languages: String, country: String, platform: Platform): String? {
      return when (platform) {
        Platform.ANDROID -> Android.findLocale(languages, country)
        Platform.IOS -> Ios.findLocale(languages, country)
        Platform.WEB -> Web.findLocale(languages, country)
      }
    }
  }
}
