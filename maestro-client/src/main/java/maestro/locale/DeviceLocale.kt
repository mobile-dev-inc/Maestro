package maestro.locale

import maestro.Platform

/**
 * Sealed interface for device locales to provide common behavior when needed.
 * This allows platform-agnostic code to work with locales while keeping
 * Android, iOS, and Web types separate for platform-specific code.
 *
 * Implementations:
 * - AndroidLocale (data class) - Dynamic combination of language + country
 * - IosLocale (enum) - Fixed set of supported iOS locales
 * - WebLocale (enum) - Fixed set of supported Web locales
 *
 * Use direct properties instead of getter methods:
 * - locale.code (not getCode())
 * - locale.displayName (not getDisplayName())
 * - locale.languageCode (not getLanguageCode())
 * - locale.countryCode (not getCountryCode())
 */
sealed interface DeviceLocale {
  /**
   * Gets the locale code representation (e.g., "en_US", "zh-Hans").
   */
  val code: String

  /**
   * Gets the display name for this locale.
   */
  val displayName: String

  /**
   * Gets the language code for this locale (e.g., "en", "fr", "zh").
   */
  val languageCode: String

  /**
   * Gets the country code for this locale (e.g., "US", "FR", "CN").
   * Returns null for locales that don't have a country code (e.g., iOS-specific formats like "zh-Hans").
   */
  val countryCode: String?

  /**
   * Gets the platform this locale is for.
   */
  val platform: Platform

  companion object {
    /**
     * Creates a DeviceLocale from a locale string and platform.
     * This is useful for platform-agnostic code that needs to work with locales.
     *
     * For platform-specific code, prefer using the specific locale types directly:
     * - AndroidLocale.fromString("en_US")
     * - IosLocale.fromString("en_US")
     * - WebLocale.fromString("en_US")
     *
     * @throws LocaleValidationException if the locale string is invalid or unsupported
     */
    fun fromString(localeString: String, platform: Platform): DeviceLocale {
      return when (platform) {
        Platform.ANDROID -> AndroidLocale.fromString(localeString)
        Platform.IOS -> IosLocale.fromString(localeString)
        Platform.WEB -> WebLocale.fromString(localeString)
      }
    }

    /**
     * Validates if a locale string is valid for the given platform.
     */
    fun isValid(localeString: String, platform: Platform): Boolean {
      return try {
        fromString(localeString, platform)
        true
      } catch (e: LocaleValidationException) {
        false
      }
    }

    /**
     * Gets all supported locales for a platform.
     */
    fun all(platform: Platform): List<DeviceLocale> {
      return when (platform) {
        Platform.ANDROID -> AndroidLocale.all
        Platform.IOS -> IosLocale.entries
        Platform.WEB -> WebLocale.entries
      }
    }

    /**
     * Gets all supported locale codes for a platform.
     */
    fun allCodes(platform: Platform): Set<String> {
      return when (platform) {
        Platform.ANDROID -> AndroidLocale.allCodes
        Platform.IOS -> IosLocale.allCodes
        Platform.WEB -> WebLocale.allCodes
      }
    }

    /**
     * Finds a locale code given language and country codes for the specified platform.
     *
     * @param languageCode Language code (e.g., "en", "fr", "zh")
     * @param countryCode Country code (e.g., "US", "FR", "CN")
     * @param platform Platform to search for locale (ANDROID, IOS, or WEB)
     * @return Locale code if found (e.g., "en_US" or "en-US"), null otherwise
     */
    fun find(languageCode: String, countryCode: String, platform: Platform): String? {
      return when (platform) {
        Platform.ANDROID -> AndroidLocale.find(languageCode, countryCode)
        Platform.IOS -> IosLocale.find(languageCode, countryCode)
        Platform.WEB -> WebLocale.find(languageCode, countryCode)
      }
    }
  }
}
