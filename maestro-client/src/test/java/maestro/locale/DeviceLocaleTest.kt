package maestro.locale

import maestro.Platform
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import com.google.common.truth.Truth.assertThat

internal class DeviceLocaleTest {
    @Test
    internal fun `fromString when invalid locale format is received throws WrongLocaleFormat exception`() {
        val exception = assertThrows<LocaleValidationException> {
            DeviceLocale.fromString("someInvalidLocale", Platform.ANDROID)
        }
        assertThat(exception.message).contains("Failed to validate device locale")
        assertThat(exception.message).contains("someInvalidLocale is not a valid format")
        assertThat(exception.message).contains("Expected format: language_country, e.g., en_US")
    }

    @Test
    internal fun `fromString when not supported locale is received and platform is Web throws LocaleValidationException`() {
        val exception = assertThrows<LocaleValidationException> {
            DeviceLocale.fromString("de_DE", Platform.WEB)
        }
        assertThat(exception.message).contains("Failed to validate web browser locale")
        assertThat(exception.message).contains("de_DE")
        assertThat(exception.message).contains("Here is a full list of supported locales")
    }

    @Test
    internal fun `fromString when the combination is not valid and platform is Android throws LocaleValidationException`() {
        val exception = assertThrows<LocaleValidationException> {
            DeviceLocale.fromString("ar_US", Platform.ANDROID)
        }
        assertThat(exception.message).contains("Failed to validate Android device locale combination")
        assertThat(exception.message).contains("ar_US is not a valid locale combination")
        assertThat(exception.message).contains("Here is a full list of supported locales")
    }

    @Test
    internal fun `fromString when not supported locale is received and platform is iOS throws LocaleValidationException`() {
        val exception = assertThrows<LocaleValidationException> {
            DeviceLocale.fromString("de_IN", Platform.IOS)
        }
        assertThat(exception.message).contains("Failed to validate iOS device locale")
        assertThat(exception.message).contains("Here is a full list of supported locales")
    }

    @Test
    internal fun `fromString when not supported locale language is received and platform is Android throws LocaleValidationException`() {
        val exception = assertThrows<LocaleValidationException> {
            DeviceLocale.fromString("ee_IN", Platform.ANDROID)
        }
        assertThat(exception.message).contains("Failed to validate Android device language")
        assertThat(exception.message).contains("ee is not a supported Android language")
        assertThat(exception.message).contains("Here is a full list of supported languageCode")
    }

    @Test
    internal fun `fromString when not supported locale country is received and platform is Android throws LocaleValidationException`() {
        val exception = assertThrows<LocaleValidationException> {
            DeviceLocale.fromString("hi_EE", Platform.ANDROID)
        }
        assertThat(exception.message).contains("Failed to validate Android device country")
        assertThat(exception.message).contains("EE is not a supported Android country")
        assertThat(exception.message).contains("Here is a full list of supported countryCode")
    }

    @Test
    internal fun `fromString when supported locale is received returns correct language and country codes`() {
        val locale1 = DeviceLocale.fromString("de_DE", Platform.ANDROID)
        val locale2 = DeviceLocale.fromString("es_ES", Platform.IOS)

        assertThat(locale1.languageCode).isEqualTo("de")
        assertThat(locale1.countryCode).isEqualTo("DE")
        assertThat(locale2.languageCode).isEqualTo("es")
        assertThat(locale2.countryCode).isEqualTo("ES")
    }

    @Test
    internal fun `isValid returns true for valid locales`() {
        assertThat(DeviceLocale.isValid("en_US", Platform.ANDROID)).isTrue()
        assertThat(DeviceLocale.isValid("he-IL", Platform.ANDROID)).isTrue()
        assertThat(DeviceLocale.isValid("es_ES", Platform.IOS)).isTrue()
        assertThat(DeviceLocale.isValid("he-IL", Platform.IOS)).isTrue()
        assertThat(DeviceLocale.isValid("en_US", Platform.WEB)).isTrue()
    }

    @Test
    internal fun `isValid returns false for invalid locales`() {
        assertThat(DeviceLocale.isValid("de_DE", Platform.WEB)).isFalse()
        assertThat(DeviceLocale.isValid("he-IL", Platform.WEB)).isFalse()
        assertThat(DeviceLocale.isValid("invalid", Platform.ANDROID)).isFalse()
        assertThat(DeviceLocale.isValid("ar_US", Platform.ANDROID)).isFalse()
    }

    @Test
    internal fun `all returns list of supported locales for each platform`() {
        val androidLocales = DeviceLocale.all(Platform.ANDROID)
        val iosLocales = DeviceLocale.all(Platform.IOS)
        val webLocales = DeviceLocale.all(Platform.WEB)

        assertThat(androidLocales).isNotEmpty()
        assertThat(iosLocales).isNotEmpty()
        assertThat(webLocales).isNotEmpty()
    }

    @Test
    internal fun `allCodes returns set of supported locale codes for each platform`() {
        val androidCodes = DeviceLocale.allCodes(Platform.ANDROID)
        val iosCodes = DeviceLocale.allCodes(Platform.IOS)
        val webCodes = DeviceLocale.allCodes(Platform.WEB)

        assertThat(androidCodes).isNotEmpty()
        assertThat(iosCodes).isNotEmpty()
        assertThat(webCodes).isNotEmpty()
        
        assertThat(androidCodes).contains("en_US")
        assertThat(iosCodes).contains("es_ES")
    }

    @Test
    internal fun `find returns correct locale code when language and country match`() {
        val androidLocale = DeviceLocale.find("en", "US", Platform.ANDROID)
        val iosLocale = DeviceLocale.find("es", "ES", Platform.IOS)

        assertThat(androidLocale).isEqualTo("en_US")
        assertThat(iosLocale).isEqualTo("es_ES")
    }

    @Test
    internal fun `find returns null when locale not found`() {
        val result = DeviceLocale.find("xx", "YY", Platform.ANDROID)
        assertThat(result).isNull()
    }

    @Test
    internal fun `code property returns locale code`() {
        val locale = DeviceLocale.fromString("en_US", Platform.ANDROID)
        assertThat(locale.code).isEqualTo("en_US")
    }

    @Test
    internal fun `displayName property returns display name`() {
        val locale = DeviceLocale.fromString("en_US", Platform.ANDROID)
        assertThat(locale.displayName).isNotEmpty()
    }

    @Test
    internal fun `platform property returns correct platform`() {
        val androidLocale = DeviceLocale.fromString("en_US", Platform.ANDROID)
        val iosLocale = DeviceLocale.fromString("es_ES", Platform.IOS)

        assertThat(androidLocale.platform).isEqualTo(Platform.ANDROID)
        assertThat(iosLocale.platform).isEqualTo(Platform.IOS)
    }
}

