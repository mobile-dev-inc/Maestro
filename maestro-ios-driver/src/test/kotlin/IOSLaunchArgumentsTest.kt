import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import util.IOSLaunchArguments.toIOSLaunchArguments

class IOSLaunchArgumentsTest {

    @Test
    fun `boolean params with one key are not touched`() {
        // given
        val launchArguments = mapOf("isCartScreen" to true)

        // when
        val iOSLaunchArguments = launchArguments.toIOSLaunchArguments()

        // then
        assertThat(iOSLaunchArguments).isEqualTo(listOf("isCartScreen", "true"))
    }

    @Test
    fun `key-value pair without prefixed '-' sign are transformed`() {
        // given
        val launchArguments = mapOf<String, Any>(
            "isCartScreen" to false,
            "cartValue" to 3
        )

        // when
        val iOSLaunchArguments = launchArguments.toIOSLaunchArguments()

        // then
        assertThat(iOSLaunchArguments).isEqualTo(listOf("isCartScreen", "false", "-cartValue", "3"))
    }

    @Test
    fun `key-value pair with prefixed '-' sign are not changed`() {
        // given
        val launchArguments = mapOf<String, Any>(
            "isCartScreen" to false,
            "cartValue" to 3,
            "-cartColor" to "Orange"
        )

        // when
        val iOSLaunchArguments = launchArguments.toIOSLaunchArguments()

        // then
        assertThat(iOSLaunchArguments).isEqualTo(
            listOf("isCartScreen", "false", "-cartValue", "3", "-cartColor", "Orange")
        )
    }

    @Test
    fun `url arguments are passed correctly`() {
        // given
        val launchArguments = mapOf<String, Any>(
            "-url" to "http://example.com"
        )

        // when
        val iOSLaunchArguments = launchArguments.toIOSLaunchArguments()

        // then
        assertThat(iOSLaunchArguments).isEqualTo(
            listOf("-url", "http://example.com")
        )
    }
}