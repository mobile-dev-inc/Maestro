package maestro.utils

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import com.google.common.truth.Truth.assertThat
import kotlin.DeprecationLevel

@Deprecated(
    message = "LocaleUtilsTest is deprecated. LocaleUtils is deprecated and will be removed in a future version. Please use DeviceLocaleTest (maestro.locale.DeviceLocaleTest) instead.",
    level = DeprecationLevel.WARNING
)
internal class LocaleUtilsTest {
    @Test
    internal fun `parseLocaleParams when invalid locale is received throws WrongLocaleFormat exception`() {
        assertThrows<LocaleValidationWrongLocaleFormatException> {
            LocaleUtils.parseLocaleParams("someInvalidLocale", Platform.ANDROID)
        }
    }

    @Test
    internal fun `parseLocaleParams when not supported platform is received throws NotSupportedPlatform exception`() {
        assertThrows<LocaleValidationNotSupportedPlatformException> {
            LocaleUtils.parseLocaleParams("de_DE", Platform.WEB)
        }
    }

    @Test
    internal fun `parseLocaleParams when not supported locale is received and platform is iOS throws ValidationIos exception`() {
        assertThrows<LocaleValidationIosException> {
            LocaleUtils.parseLocaleParams("de_IN", Platform.IOS)
        }
    }

    @Test
    internal fun `parseLocaleParams when not supported locale language is received and platform is Android throws ValidationAndroidLanguage exception`() {
        assertThrows<LocaleValidationAndroidLanguageException> {
            LocaleUtils.parseLocaleParams("ee_IN", Platform.ANDROID)
        }
    }

    @Test
    internal fun `parseLocaleParams when not supported locale country is received and platform is Android throws ValidationAndroidLanguage exception`() {
        assertThrows<LocaleValidationAndroidCountryException> {
            LocaleUtils.parseLocaleParams("hi_EE", Platform.ANDROID)
        }
    }

    @Test
    internal fun `parseLocaleParams when supported locale is received returns correct language and country codes`() {
        val (language1, country1) = LocaleUtils.parseLocaleParams("de_DE", Platform.ANDROID)
        val (language2, country2) = LocaleUtils.parseLocaleParams("es_ES", Platform.IOS)

        assertThat(language1).isEqualTo("de")
        assertThat(country1).isEqualTo("DE")
        assertThat(language2).isEqualTo("es")
        assertThat(country2).isEqualTo("ES")
    }
}
