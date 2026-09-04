package maestro.orchestra.yaml.schema

import com.google.common.truth.Truth.assertThat
import maestro.orchestra.yaml.MaestroFlowParser
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * [FlowCommandSchema.selectorArguments] is what a consumer writes inside `tapOn`, `assertVisible` and
 * their siblings. Those commands publish no arguments of their own, so without this the schema says
 * nothing at all about the shape Maestro's most-used commands take.
 */
class SelectorArgumentsTest {

    @Test
    fun `every advertised selector argument is one the parser accepts`() {
        val rejected = FlowCommandSchema.selectorArguments.filterNot { argument ->
            val yaml = "tapOn:\n  ${argument.name}: ${placeholderFor(argument)}"
            runCatching { MaestroFlowParser.parseCommand(Paths.get("t.yaml"), "com.example.app", yaml) }.isSuccess
        }

        assertThat(rejected.map { it.name }).isEmpty()
    }

    @Test
    fun `the selector carries the fields a flow actually uses`() {
        assertThat(FlowCommandSchema.selectorArguments.map { it.name })
            .containsAtLeast("text", "id", "index", "enabled", "checked", "below", "containsChild")
    }

    private fun placeholderFor(argument: ArgumentSchema): String = when (argument.kind) {
        ArgumentKind.NUMBER -> "1"
        ArgumentKind.BOOLEAN -> "true"
        ArgumentKind.ENUM -> argument.values!!.first()
        ArgumentKind.ARRAY -> "[]"
        ArgumentKind.OBJECT -> "{}"
        ArgumentKind.STRING, ArgumentKind.SELECTOR, ArgumentKind.ANY -> "\"placeholder\""
    }
}
