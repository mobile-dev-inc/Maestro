package maestro.locale

import maestro.Platform
import java.util.Locale

/**
 * Type-safe enum representing Android-supported languages.
 * These are a subset of languages available in Java's Locale API.
 */
enum class AndroidLanguage(val code: String) {
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

  // Gets the display name for this language using Java's Locale API.
  // Falls back to the enum name if Locale doesn't recognize the code.
  val displayName: String
    get() = try {
      Locale(code).getDisplayLanguage(Locale.US).takeIf { it.isNotBlank() }
        ?: code.uppercase()
    } catch (e: Exception) {
      code.uppercase()
    }

  companion object {
    init {
      // Validate that all enum codes are valid ISO-639-1 language codes
      val validISOCodes = Locale.getISOLanguages().toSet()
      entries.forEach { language ->
        require(language.code in validISOCodes) {
          "Language code '${language.code}' in AndroidLanguage enum is not a valid ISO-639-1 code"
        }
      }
    }

    // Gets all language codes as a set.
    val allCodes: Set<String>
      get() = entries.map { it.code }.toSet()

    // Finds a language by its code.
    fun fromCode(code: String): AndroidLanguage? {
      return entries.find { it.code == code }
    }
  }
}

/**
 * Type-safe enum representing Android-supported countries.
 * These are a subset of countries available in Java's Locale API.
 */
enum class AndroidCountry(val code: String) {
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
  MEXICO("MX"),
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

  // Gets the display name for this country using Java's Locale API.
  // Falls back to the enum name if Locale doesn't recognize the code.
  val displayName: String
    get() = try {
      Locale("", code).getDisplayCountry(Locale.US).takeIf { it.isNotBlank() }
        ?: code
    } catch (e: Exception) {
      code
    }

  companion object {
    init {
      // Validate that all enum codes are valid ISO-3166-1 country codes
      val validISOCodes = Locale.getISOCountries().toSet()
      entries.forEach { country ->
        require(country.code in validISOCodes) {
          "Country code '${country.code}' in AndroidCountry enum is not a valid ISO-3166-1 code"
        }
      }
    }

    // Gets all country codes as a set.
    val allCodes: Set<String>
      get() = entries.map { it.code }.toSet()

    // Finds a country by its code.
    fun fromCode(code: String): AndroidCountry? {
      return entries.find { it.code == code }
    }
  }
}

/**
 * Android device locale - a dynamic combination of language and country.
 * Android supports all combinations of supported languages and countries.
 */
data class AndroidLocale(
  val language: AndroidLanguage,
  val country: AndroidCountry
) : DeviceLocale {

  override val code: String
    get() = "${language.code}_${country.code}"

  override val displayName: String
    get() = try {
      Locale(language.code, country.code).getDisplayName(Locale.US)
    } catch (e: Exception) {
      "${language.displayName} (${country.displayName})"
    }

  override val languageCode: String
    get() = language.code

  override val countryCode: String
    get() = country.code

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
     * Creates an AndroidLocale from a locale string (e.g., "en_US").
     * Validates that both language and country codes are supported.
     * @throws LocaleValidationException if the locale string is invalid or unsupported
     */
    fun fromString(localeString: String): AndroidLocale {
      val parts = localeString.split("_", "-")
      if (parts.size != 2) {
        throw LocaleValidationException(
          "Failed to validate device locale, $localeString is not a valid format. Expected format: language_country, e.g., en_US."
        )
      }

      val languageCode = parts[0]
      val countryCode = parts[1]

      val language = AndroidLanguage.fromCode(languageCode)
        ?: throw LocaleValidationException(
          "Failed to validate Android device language, $languageCode is not a supported Android language. Here is a full list of supported languageCode: \n\n ${AndroidLanguage.allCodes.joinToString(", ")}"
        )

      val country = AndroidCountry.fromCode(countryCode)
        ?: throw LocaleValidationException(
          "Failed to validate Android device country, $countryCode is not a supported Android country. Here is a full list of supported countryCode: \n\n ${AndroidCountry.allCodes.joinToString(", ")}"
        )

      // Validate that the language-country combination exists in Java Locale
      if ("${languageCode}_${countryCode}" !in availableJavaLocaleCombinations) {
        throw LocaleValidationException(
          "Failed to validate Android device locale combination, $localeString is not a valid locale combination. Here is a full list of supported locales: \n\n ${allCodes.joinToString(", ")}"
        )
      }

      return AndroidLocale(language, country)
    }

    /**
     * Validates if a locale string represents a valid Android locale combination.
     */
    fun isValid(localeString: String): Boolean {
      return try {
        fromString(localeString)
        true
      } catch (e: LocaleValidationException) {
        false
      }
    }

    /**
     * Generates all valid Android locale combinations dynamically.
     * This creates all combinations of supported languages and countries
     * that are valid Java locale combinations.
     */
    val all: List<AndroidLocale>
      get() = AndroidLanguage.entries.flatMap { language ->
        AndroidCountry.entries.mapNotNull { country ->
          val localeKey = "${language.code}_${country.code}"
          // Only include combinations that are valid Java locales
          if (localeKey in availableJavaLocaleCombinations) {
            AndroidLocale(language, country)
          } else {
            null
          }
        }
      }

    /**
     * Gets all locale codes as a set.
     */
    val allCodes: Set<String>
      get() = all.map { it.code }.toSet()

    /**
     * Finds a locale code given language and country codes for Android.
     * @return Locale code if found (e.g., "en_US"), null otherwise
     */
    fun find(languageCode: String, countryCode: String): String? {
      return if (isValid("${languageCode}_${countryCode}")) {
        "${languageCode}_${countryCode}"
      } else {
        null
      }
    }
  }
}
