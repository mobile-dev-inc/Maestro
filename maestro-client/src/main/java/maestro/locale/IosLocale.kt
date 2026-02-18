package maestro.locale

import maestro.Platform

/**
 * iOS device locale - fixed enum of supported locale combinations.
 */
enum class IosLocale(override val code: String) : DeviceLocale {
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

  override val displayName: String
    get() {
      return when (this) {
        ZH_HANS -> "Chinese (Simplified)"
        ZH_HANT -> "Chinese (Traditional)"
        ES_419 -> "Spanish (Latin America)"
        else -> DeviceLocale.getDisplayNameFromCode(code)
      }
    }

  override val languageCode: String
    get() {
      val parts = code.split("_", "-")
      return parts[0]
    }

  override val countryCode: String?
    get() {
      val parts = code.split("_", "-")
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
     * Gets all locale codes as a set.
     */
    val allCodes: Set<String>
      get() = entries.map { it.code }.toSet()

    /**
     * Finds a locale by its string representation.
     * Accepts both underscore and hyphen formats.
     *
     * @throws LocaleValidationException if not found
     */
    fun fromString(localeString: String): IosLocale {
      return entries.find {
        it.code == localeString ||
                it.code.replace("_", "-") == localeString ||
                it.code.replace("-", "_") == localeString
      } ?: throw LocaleValidationException("Failed to validate iOS device locale. Here is a full list of supported locales: \n\n ${allCodes.joinToString(", ")}")
    }

    /**
     * Validates if a locale string is valid for iOS.
     */
    fun isValid(localeString: String): Boolean {
      return entries.any {
        it.code == localeString ||
                it.code.replace("_", "-") == localeString ||
                it.code.replace("-", "_") == localeString
      }
    }

    /**
     * Finds a locale code given language and country codes.
     * Tries both underscore and hyphen formats.
     * @return Locale code if found (e.g., "en_US" or "en-US"), null otherwise
     */
    fun find(languageCode: String, countryCode: String): String? {
      // Try underscore format first
      val underscoreFormat = "${languageCode}_$countryCode"
      if (isValid(underscoreFormat)) {
        return underscoreFormat
      }

      // Try hyphen format
      val hyphenFormat = "$languageCode-$countryCode"
      if (isValid(hyphenFormat)) {
        return hyphenFormat
      }

      return null
    }
  }
}
