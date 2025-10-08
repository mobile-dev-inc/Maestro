package maestro.orchestra.workspace

import maestro.orchestra.WorkspaceConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.google.common.truth.Truth.assertThat

class WorkspaceConfigProviderTest {

    @BeforeEach
    fun setUp() {
        WorkspaceConfigProvider.workspaceConfig = null
    }

    @AfterEach
    fun tearDown() {
        WorkspaceConfigProvider.workspaceConfig = null
    }

    @Test
    fun `WorkspaceConfigProvider should store and retrieve workspace config`() {
        val workspaceConfig = WorkspaceConfig(
            paths = mapOf(
                "@proj" to "libs/screens",
                "!components" to "src/components"
            )
        )

        WorkspaceConfigProvider.workspaceConfig = workspaceConfig

        assertThat(WorkspaceConfigProvider.workspaceConfig).isEqualTo(workspaceConfig)
    }

    @Test
    fun `WorkspaceConfigProvider should return null when not set`() {
        assertThat(WorkspaceConfigProvider.workspaceConfig).isNull()
    }

    @Test
    fun `WorkspaceConfigProvider should allow updating workspace config`() {
        val initialConfig = WorkspaceConfig(paths = mapOf("@proj" to "libs/screens"))
        val updatedConfig = WorkspaceConfig(paths = mapOf("!components" to "src/components"))

        WorkspaceConfigProvider.workspaceConfig = initialConfig
        assertThat(WorkspaceConfigProvider.workspaceConfig).isEqualTo(initialConfig)

        WorkspaceConfigProvider.workspaceConfig = updatedConfig
        assertThat(WorkspaceConfigProvider.workspaceConfig).isEqualTo(updatedConfig)
    }

    @Test
    fun `PathResolver should use WorkspaceConfigProvider when available`() {
        val workspaceConfig = WorkspaceConfig(
            paths = mapOf("@proj" to "libs/screens")
        )
        WorkspaceConfigProvider.workspaceConfig = workspaceConfig

        val result = PathResolver.resolveAliases("@proj/login.yaml", workspaceConfig)
        assertThat(result).isEqualTo("libs/screens/login.yaml")
    }
}
