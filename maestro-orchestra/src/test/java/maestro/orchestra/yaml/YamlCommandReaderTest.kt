package maestro.orchestra.yaml

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.BackPressCommand
import maestro.orchestra.Command
import maestro.orchestra.InvalidInitFlowFile
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.MaestroInitFlow
import maestro.orchestra.ScrollCommand
import maestro.orchestra.SyntaxError
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Paths
import kotlin.io.path.isSameFileAs

@Suppress("TestFunctionName")
internal class YamlCommandReaderTest {

    @JvmField
    @Rule
    val name: TestName = TestName()

    @Test
    fun T001_empty() = expectException<SyntaxError> { e ->
        assertThat(e.message).contains("Flow files must contain a config section and a commands section")
    }

    @Test
    fun T002_launchApp() = expectCommands(
        ApplyConfigurationCommand(MaestroConfig()),
        LaunchAppCommand(
            appId = "com.example.app"
        ),
    )

    @Test
    fun T003_launchApp_withClearState() = expectCommands(
        ApplyConfigurationCommand(MaestroConfig()),
        LaunchAppCommand(
            appId = "com.example.app",
            clearState = true,
        ),
    )

    @Test
    fun T004_config_empty() = expectException<SyntaxError> { e ->
        assertThat(e.message).contains("Flow files must contain a config section and a commands section")
    }

    @Test
    fun T005_config_noAppId() = expectException<SyntaxError> { e ->
        assertThat(e.message).contains("appId due to missing (therefore NULL) value for creator parameter appId which is a non-nullable type")
    }

    @Test
    fun T006_emptyCommands() = expectException<SyntaxError> { e ->
        assertThat(e.message).contains("Flow files must contain a config section and a commands section")
    }

    @Test
    fun T007_initFlow() = expectCommands(
        ApplyConfigurationCommand(MaestroConfig(
            initFlow = MaestroInitFlow(
                appId = "com.example.app",
                commands = commands(
                    LaunchAppCommand(
                        appId = "com.example.app",
                        clearState = true,
                    )
                ),
            )
        )),
        LaunchAppCommand(
            appId = "com.example.app",
        ),
    )

    @Test
    fun T008_config_unknownKeys() = expectCommands(
        ApplyConfigurationCommand(MaestroConfig(
            ext = mapOf(
                "extra" to true,
                "extraMap" to mapOf(
                    "keyA" to "valueB"
                ),
                "extraArray" to listOf("itemA")
            )
        )),
        LaunchAppCommand(
            appId = "com.example.app",
        ),
    )

    @Test
    fun T009_invalidCommand() = expectException<SyntaxError> { e ->
        assertThat(e.message).contains("Unrecognized field \"invalid\"")
    }

    @Test
    fun T010_invalidCommand_string() = expectException<SyntaxError> { e ->
        assertThat(e.message).contains("Invalid command: \"invalid\"")
    }

    @Test
    fun T011_initFlow_file() = expectCommands(
        ApplyConfigurationCommand(MaestroConfig(
            initFlow = MaestroInitFlow(
                appId = "com.example.app",
                commands = commands(
                    ApplyConfigurationCommand(
                        config = MaestroConfig()
                    ),
                    LaunchAppCommand(
                        appId = "com.example.app",
                    )
                ),
            ),
        )),
        LaunchAppCommand(
            appId = "com.example.app",
        ),
    )

    @Test
    fun T012_initFlow_emptyString() = expectCommands(
        ApplyConfigurationCommand(MaestroConfig()),
        LaunchAppCommand(
            appId = "com.example.app",
        ),
    )

    @Test
    fun T013_initFlow_invalidFile() = expectException<InvalidInitFlowFile>()

    @Test
    fun T014_initFlow_recursive() = expectException<InvalidInitFlowFile>()

    @Test
    fun T015_onlyCommands() = expectException<SyntaxError> { e ->
        assertThat(e.message).contains("Flow files must contain a config section and a commands section")
    }

    @Test
    fun T016_launchApp_emptyString() = expectException<SyntaxError> { e ->
        assertThat(e.message).contains("No mapping provided for YamlFluentCommand")
    }

    @Test
    fun T017_launchApp_otherPackage() = expectCommands(
        ApplyConfigurationCommand(MaestroConfig()),
        LaunchAppCommand(
            appId = "com.other.app"
        ),
    )

    @Test
    fun T018_backPress_string() = expectCommands(
        ApplyConfigurationCommand(MaestroConfig()),
        BackPressCommand(),
    )

    @Test
    fun T019_scroll_string() = expectCommands(
        ApplyConfigurationCommand(MaestroConfig()),
        ScrollCommand(),
    )

    @Test
    fun T020_config_name() = expectCommands(
        ApplyConfigurationCommand(MaestroConfig(
            name = "Example Flow"
        )),
        LaunchAppCommand(
            appId = "com.example.app"
        ),
    )

    // Misc. tests

    @Test
    fun readFromZip() {
        val resource = this::class.java.getResource("/YamlCommandReaderTest/flow.zip")!!.toURI()
        assertThat(resource.scheme).isEqualTo("file")

        val commands = FileSystems.newFileSystem(Paths.get(resource), null).use { fs ->
            YamlCommandReader.readCommands(fs.getPath("flow.yaml"))
        }

        assertThat(commands).isEqualTo(commands(
            ApplyConfigurationCommand(
                config = MaestroConfig(
                    initFlow = MaestroInitFlow(
                        appId = "com.example.app",
                        commands = commands(
                            ApplyConfigurationCommand(
                                config = MaestroConfig()
                            ),
                            LaunchAppCommand(
                                appId = "com.example.app"
                            ),
                        )
                    )
                )
            ),
            LaunchAppCommand(
                appId = "com.example.app"
            )
        ))
    }

    private inline fun <reified T : Throwable> expectException(block: (e: T) -> Unit = {}) {
        try {
            parseCommands()
            assertWithMessage("Expected exception: ${T::class.java}").fail()
        } catch (e: Throwable) {
            if (e is AssertionError) throw e
            assertThat(e).isInstanceOf(T::class.java)
            block(e as T)
        }
    }

    private fun expectCommands(vararg expectedCommands: Command) {
        val actualCommands = parseCommands()
        val expectedMaestroCommands = commands(*expectedCommands)
        assertThat(actualCommands).isEqualTo(expectedMaestroCommands)
    }

    private fun commands(vararg commands: Command): List<MaestroCommand> {
        return commands.map(this::toMaestroCommand).toList()
    }

    private fun toMaestroCommand(command: Command): MaestroCommand {
        val constructor = MaestroCommand::class.java.constructors[1]
        val parameterIndex = constructor.parameterTypes.indexOf(command::class.java)
        if (parameterIndex == -1) throw IllegalArgumentException("Unsupported command type: ${command::class.java}")
        val args = constructor.parameters.map { parameter ->
            when (parameter.type) {
                command::class.java -> command
                Int::class.java -> 0
                else -> null
            }
        }
        return constructor.newInstance(*args.toTypedArray()) as MaestroCommand
    }

    private fun parseCommands(): List<MaestroCommand> {
        val resourceName = name.methodName.removePrefix("T") + ".yaml"
        val resource = this::class.java.getResource("/YamlCommandReaderTest/$resourceName")!!
        val resourceFile = Paths.get(resource.toURI())
        return YamlCommandReader.readCommands(resourceFile)
    }
}