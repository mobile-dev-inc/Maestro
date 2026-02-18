package maestro.locale

import maestro.Platform
import java.util.Locale

/**
 * Sealed interface for device locales to provide common behavior when needed.
 * This allows platform-agnostic code to work with locales while keeping
 * Android, iOS, and Web types separate for platform-specific code.
 */
sealed interface DeviceLocale {
  // Gets the locale code representation (e.g., "en_US", "zh-Hans").
  val code: String

  // Gets the display name for this locale.
  val displayName: String

  // Gets the language code for this locale (e.g., "en", "fr", "zh").
  val languageCode: String

  // Gets the country code for this locale (e.g., "US", "FR", "CN").
  // Returns null for locales that don't have a country code (e.g., iOS-specific formats like "zh-Hans").
  val countryCode: String?

  // Gets the platform this locale is for.
  val platform: Platform

  companion object {
    /**
     * Creates a DeviceLocale from a locale string and platform.
     * This is useful for platform-agnostic code that needs to work with locales.
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
     * @return Locale code if found (e.g., "en_US" or "en-US"), null otherwise
     */
    fun find(languageCode: String, countryCode: String, platform: Platform): String? {
      return when (platform) {
        Platform.ANDROID -> AndroidLocale.find(languageCode, countryCode)
        Platform.IOS -> IosLocale.find(languageCode, countryCode)
        Platform.WEB -> WebLocale.find(languageCode, countryCode)
      }
    }

    /**
     * Generates a display name for a locale code using Java's Locale API.
     * Parses locale codes in both underscore (en_US) and hyphen (en-US) formats.
     * Falls back to returning the original code if parsing fails.
     *
     * @param localeCode The locale code to generate a display name for
     * @return A human-readable display name (e.g., "en_US" -> "English (United States)"), or the original code if parsing fails
     */
    internal fun getDisplayNameFromCode(localeCode: String): String {
      return try {
        val parts = localeCode.split("_", "-")
        if (parts.size == 2) {
          val javaLocale = Locale(parts[0], parts[1])
          val displayName = javaLocale.getDisplayName(Locale.US)
          if (displayName.isNotBlank()) {
            return displayName
          }
        }
      } catch (e: Exception) {
        // Fall through to return locale string
      }
      localeCode
    }
  }
}
