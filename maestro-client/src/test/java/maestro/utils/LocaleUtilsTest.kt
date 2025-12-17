package maestro.utils

import maestro.Platform
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import com.google.common.truth.Truth.assertThat

internal class LocaleUtilsTest {
    @Test
    internal fun `parseLocaleParams when invalid locale is received throws WrongLocaleFormat exception`() {
        assertThrows<LocaleValidationWrongLocaleFormatException> {
            LocaleUtils.fromLocaleString("someInvalidLocale", Platform.ANDROID)
        }
    }

    @Test
    internal fun `parseLocaleParams when not supported locale is received and platform is Web throws LocaleValidationWebException exception`() {
        assertThrows<LocaleValidationWebException> {
            LocaleUtils.fromLocaleString("de_DE", Platform.WEB)
        }
    }

    @Test
    internal fun `parseLocaleParams when the combination is not valid and platform is Android throws LocaleValidationAndroidLocaleCombinationException exception`() {
        assertThrows<LocaleValidationAndroidLocaleCombinationException> {
          LocaleUtils.fromLocaleString("ar_US", Platform.ANDROID)
        }
    }

    @Test
    internal fun `parseLocaleParams when not supported locale is received and platform is iOS throws ValidationIos exception`() {
        assertThrows<LocaleValidationIosException> {
            LocaleUtils.fromLocaleString("de_IN", Platform.IOS)
        }
    }

    @Test
    internal fun `parseLocaleParams when not supported locale language is received and platform is Android throws ValidationAndroidLanguage exception`() {
        assertThrows<LocaleValidationAndroidLanguageException> {
            LocaleUtils.fromLocaleString("ee_IN", Platform.ANDROID)
        }
    }

    @Test
    internal fun `parseLocaleParams when not supported locale country is received and platform is Android throws ValidationAndroidLanguage exception`() {
        assertThrows<LocaleValidationAndroidCountryException> {
            LocaleUtils.fromLocaleString("hi_EE", Platform.ANDROID)
        }
    }

    @Test
    internal fun `parseLocaleParams when supported locale is received returns correct language and country codes`() {
        val locale1 = LocaleUtils.fromLocaleString("de_DE", Platform.ANDROID)
        val locale2 = LocaleUtils.fromLocaleString("es_ES", Platform.IOS)

        assertThat(locale1.getLanguageCode()).isEqualTo("de")
        assertThat(locale1.getCountryCode()).isEqualTo("DE")
        assertThat(locale2.getLanguageCode()).isEqualTo("es")
        assertThat(locale2.getCountryCode()).isEqualTo("ES")
    }
}
