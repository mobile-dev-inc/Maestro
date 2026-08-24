package maestro.cli.util

import com.google.common.truth.Truth.assertThat
import maestro.device.SystemImageTag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SystemImageTagConverterTest {

    private val converter = SystemImageTagConverter()

    @Test
    fun `converts the SDK-canonical google_apis string`() {
        assertThat(converter.convert("google_apis")).isEqualTo(SystemImageTag.GOOGLE_APIS)
    }

    @Test
    fun `converts the SDK-canonical google_apis_playstore string`() {
        assertThat(converter.convert("google_apis_playstore"))
            .isEqualTo(SystemImageTag.GOOGLE_APIS_PLAYSTORE)
    }

    @Test
    fun `rejects an unknown tag and names the valid options`() {
        val error = assertThrows<IllegalArgumentException> { converter.convert("aosp_atd") }

        assertThat(error).hasMessageThat().contains("aosp_atd")
        assertThat(error).hasMessageThat().contains("google_apis")
        assertThat(error).hasMessageThat().contains("google_apis_playstore")
    }

    @Test
    fun `rejects the enum constant name - values are the SDK tag strings, not Kotlin names`() {
        // GOOGLE_APIS is the Kotlin constant; google_apis is the wire/SDK value. Only the
        // latter is a legal CLI value, so a typo'd uppercase form must fail loudly rather
        // than silently resolving.
        assertThrows<IllegalArgumentException> { converter.convert("GOOGLE_APIS") }
    }
}
