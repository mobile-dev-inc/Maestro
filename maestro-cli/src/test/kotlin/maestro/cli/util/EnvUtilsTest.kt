package maestro.cli.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables
import uk.org.webcompere.systemstubs.jupiter.SystemStub
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension
import java.nio.file.Paths

@ExtendWith(SystemStubsExtension::class)
class EnvUtilsTest {

    @SystemStub
    private val environmentVariables = EnvironmentVariables()

    @Test
    fun `xdgStateHome returns user home maestro when XDG_STATE_HOME is not set`() {
        val path = EnvUtils.xdgStateHome()

        assertThat(path).isEqualTo(Paths.get(System.getProperty("user.home"), ".maestro"))
    }

    @Test
    fun `xdgStateHome returns XDG_STATE_HOME maestro when XDG_STATE_HOME is set`() {
        environmentVariables.set("XDG_STATE_HOME", "/custom/state")

        val path = EnvUtils.xdgStateHome()

        assertThat(path).isEqualTo(Paths.get("/custom/state", "maestro"))
    }

}
