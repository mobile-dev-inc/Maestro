package maestro.cli.session

import com.google.common.truth.Truth.assertThat
import maestro.cli.CliError
import maestro.device.Platform
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AndroidUserOptionTest {

    @Test
    fun `no android user is accepted on any platform`() {
        Platform.entries.forEach { platform ->
            assertThat(validateAndroidUser(null, platform)).isNull()
        }
    }

    @Test
    fun `android user is accepted on android`() {
        assertThat(validateAndroidUser(10, Platform.ANDROID)).isEqualTo(10)
    }

    @Test
    fun `negative android user is rejected`() {
        val error = assertThrows<CliError> { validateAndroidUser(-1, Platform.ANDROID) }
        assertThat(error.message).isEqualTo("--android-user must be 0 or greater")
    }

    @Test
    fun `android user is rejected on ios`() {
        val error = assertThrows<CliError> { validateAndroidUser(10, Platform.IOS) }
        assertThat(error.message).isEqualTo("--android-user is only supported on Android devices")
    }
}
