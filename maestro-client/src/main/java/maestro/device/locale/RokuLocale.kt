package maestro.device.locale

import maestro.device.Platform

/**
 * Roku device locale - fixed enum of supported locale combinations.
 * Roku supports a wide range of locales but for now we start with en_US.
 */
enum class RokuLocale(override val code: String) : DeviceLocale {
  EN_US("en_US");

  override val displayName: String
    get() = DeviceLocale.getDisplayNameFromCode(code)

  override val languageCode: String
    get() {
      val parts = code.split("_", "-")
      return parts[0]
    }

  override val countryCode: String
    get() = code.split("_", "-")[1]

  override val platform: Platform = Platform.ROKU

  companion object {
    val allCodes: Set<String>
      get() = entries.map { it.code }.toSet()

    fun fromString(localeString: String): RokuLocale {
      return entries.find { it.code == localeString }
        ?: throw LocaleValidationException("Failed to validate Roku device locale: $localeString. Here is a full list of supported locales: \n\n ${allCodes.joinToString(", ")}")
    }

    fun isValid(localeString: String): Boolean {
      return entries.any { it.code == localeString }
    }

    fun find(languageCode: String, countryCode: String): String? {
      return entries.find { it.languageCode == languageCode && it.countryCode == countryCode }?.code
    }
  }
}
