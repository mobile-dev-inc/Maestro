package maestro.cli.driver

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.io.path.exists

class DriverBuilderTest {

    @Test
    fun `test if driver is built successfully and written in directory`() {
        // given
        val builder = DriverBuilder()

        // when
        val buildProducts = builder.buildDriver(
            DriverBuildConfig(
                teamId = "25CQD4CKK3",
                derivedDataPath = "driver-iphoneos",
                sourceCodePath = "driver/ios"
            )
        )
        val xctestRunFile = buildProducts.toFile().walk().firstOrNull { it.extension == "xctestrun" }
        val appDir = buildProducts.resolve("Debug-iphoneos/maestro-driver-ios.app")
        val runnerDir = buildProducts.resolve("Debug-iphoneos/maestro-driver-iosUITests-Runner.app")


        // then
        assertThat(xctestRunFile?.exists()).isTrue()
        assertThat(appDir.exists()).isTrue()
        assertThat(runnerDir.exists()).isTrue()
    }
}