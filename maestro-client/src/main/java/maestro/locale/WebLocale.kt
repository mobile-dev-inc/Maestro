package maestro.locale

import maestro.Platform
import java.util.Locale

/**
 * Web device locale - fixed enum of supported locale combinations.
 * Currently only supports a limited set of locales.
 *
 * Use direct properties:
 * - WebLocale.EN_US.code
 * - WebLocale.EN_US.displayName
 * - WebLocale.fromString("en_US")
 */
enum class WebLocale(override val code: String) : DeviceLocale {
  // Standard locales (language_country format)
  EN_US("en_US");

  override val displayName: String
    get() {
      // Try to parse as standard locale
      try {
        val parts = code.split("_", "-")
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
      return code
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
        return parts[1]
      }
      return null
    }

  override val platform: Platform = Platform.WEB

  companion object {
    /**
     * Gets all locale codes as a set.
     */
    val allCodes: Set<String>
      get() = entries.map { it.code }.toSet()

    /**
     * Finds a locale by its string representation.
     *
     * @throws LocaleValidationWebException if not found
     */
    fun fromString(localeString: String): WebLocale {
      return entries.find { it.code == localeString }
        ?: throw LocaleValidationException("Failed to validate web browser locale: $localeString. Here is a full list of supported locales: \n\n ${allCodes.joinToString(", ")}")
    }

    /**
     * Validates if a locale string is valid for Web.
     */
    fun isValid(localeString: String): Boolean {
      return entries.any { it.code == localeString }
    }

    /**
     * Finds a locale code given language and country codes.
     *
     * @param languageCode Language code (e.g., "en")
     * @param countryCode Country code (e.g., "US")
     * @return Locale code if found (e.g., "en_US"), null otherwise
     */
    fun find(languageCode: String, countryCode: String): String? {
      val underscoreFormat = "${languageCode}_$countryCode"
      if (isValid(underscoreFormat)) {
        return underscoreFormat
      }
      return null
    }
  }
}
