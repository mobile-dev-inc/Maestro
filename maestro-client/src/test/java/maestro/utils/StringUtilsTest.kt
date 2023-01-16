package maestro.utils

import com.google.common.truth.Truth.assertThat
import maestro.utils.StringUtils.toRegexSafe
import org.junit.jupiter.api.Test

internal class StringUtilsTest {

    @Test
    internal fun `toRegexSafe should escape string if regex is invalid`() {
        // Given
        val input = "*Oświadczam, że zapoznałem się z treścią Regulaminu serwisu i akceptuję jego postanowienia."

        // When
        val regex = input.toRegexSafe()

        // Then
        assertThat(regex.matches(input)).isTrue()
    }

}