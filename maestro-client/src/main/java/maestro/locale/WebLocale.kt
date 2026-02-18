package maestro.locale
import maestro.device.Platform

/**
 * Web device locale - fixed enum of supported locale combinations.
 */
enum class WebLocale(override val code: String) : DeviceLocale {
  EN_US("en_US");

  override val displayName: String
    get() = DeviceLocale.getDisplayNameFromCode(code)

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
     * @throws LocaleValidationException if not found
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
