package maestro.orchestra

import com.google.common.truth.Truth.assertThat
import maestro.MaestroException
import maestro.orchestra.filter.LaunchArguments.toSanitizedLaunchArguments
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LaunchArgumentsTest {

    @Test
    fun `sanitize the launchArguments without spaces`() {
        // given
        val launchArguments = listOf("isCartPresent", "cartValue=2", "cartColor = orange", "cartCategory= sales")

        // when
        val sanitizedLaunchArguments = launchArguments.toSanitizedLaunchArguments("com.example.appId")

        // then
        assertThat(sanitizedLaunchArguments).containsExactly(
            "isCartPresent",
            "cartValue=2",
            "cartColor=orange",
            "cartCategory=sales"
        )
    }

    @Test
    fun `raises an exception when there are more than 1 pair`() {
        // given
        val launchArguments = listOf("argumentA=argumentB=argumentAValue")

        // when, then
        assertThrows<MaestroException.UnableToLaunchApp> {
            launchArguments.toSanitizedLaunchArguments("com.example.appId")
        }
    }
}