package maestro.orchestra.util

import com.google.common.truth.Truth.assertThat
import maestro.MaestroException
import maestro.ScrollDirection
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import maestro.orchestra.MaestroCommand
import maestro.orchestra.RepeatCommand
import maestro.orchestra.ScrollUntilVisibleCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.TapOnPointV2Command
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NumericFieldsTest {

    @Test
    fun `parseIndex parses a whole number string`() {
        assertThat(NumericFields.parseIndex("2")).isEqualTo(2)
    }

    @Test
    fun `parseIndex truncates a decimal string to match existing runtime behavior`() {
        assertThat(NumericFields.parseIndex("1.5")).isEqualTo(1)
    }

    @Test
    fun `parseIndex rejects a non-numeric value with a clear InvalidCommand`() {
        val error = assertThrows<MaestroException.InvalidCommand> {
            NumericFields.parseIndex("undefined")
        }
        assertThat(error.message).contains("undefined")
        assertThat(error.message).contains("index")
    }

    @Test
    fun `parseIndex rejects NaN instead of silently tapping the first element`() {
        val error = assertThrows<MaestroException.InvalidCommand> {
            NumericFields.parseIndex("NaN")
        }
        assertThat(error.message).contains("index")
    }

    @Test
    fun `parseIndex rejects Infinity`() {
        assertThrows<MaestroException.InvalidCommand> {
            NumericFields.parseIndex("Infinity")
        }
    }

    @Test
    fun `parseIndex rejects negative Infinity`() {
        assertThrows<MaestroException.InvalidCommand> {
            NumericFields.parseIndex("-Infinity")
        }
    }

    @Test
    fun `numeric parse errors are InvalidNumericFieldValue so optional never downgrades them`() {
        assertThrows<MaestroException.InvalidNumericFieldValue> { NumericFields.parseIndex("undefined") }
        assertThrows<MaestroException.InvalidNumericFieldValue> { NumericFields.parsePoint("undefined,90") }
        assertThrows<MaestroException.InvalidNumericFieldValue> { NumericFields.parseScrollSpeed("500") }
    }

    @Test
    fun `parsePoint parses two comma-separated coordinates`() {
        assertThat(NumericFields.parsePoint("10, 20")).isEqualTo(10 to 20)
    }

    @Test
    fun `parsePoint ignores percent signs so callers can range-check separately`() {
        assertThat(NumericFields.parsePoint("50%,90%")).isEqualTo(50 to 90)
    }

    @Test
    fun `parsePoint rejects a non-numeric coordinate with a clear InvalidCommand`() {
        val error = assertThrows<MaestroException.InvalidCommand> {
            NumericFields.parsePoint("undefined,90")
        }
        assertThat(error.message).contains("undefined,90")
        assertThat(error.message).contains("point")
    }

    @Test
    fun `parsePoint rejects a point with fewer than two coordinates`() {
        assertThrows<MaestroException.InvalidCommand> {
            NumericFields.parsePoint("10")
        }
    }

    @Test
    fun `parsePoint uses the first two when extra coordinates are given`() {
        assertThat(NumericFields.parsePoint("10,20,30")).isEqualTo(10 to 20)
    }

    @Test
    fun `parseScrollSpeed parses a whole number string`() {
        assertThat(NumericFields.parseScrollSpeed("40")).isEqualTo(40L)
    }

    @Test
    fun `parseScrollSpeed accepts the inclusive bounds 0 and 100`() {
        assertThat(NumericFields.parseScrollSpeed("0")).isEqualTo(0L)
        assertThat(NumericFields.parseScrollSpeed("100")).isEqualTo(100L)
    }

    @Test
    fun `parseScrollSpeed rejects a value above 100`() {
        val error = assertThrows<MaestroException.InvalidCommand> {
            NumericFields.parseScrollSpeed("500")
        }
        assertThat(error.message).contains("500")
        assertThat(error.message).contains("between 0 and 100")
    }

    @Test
    fun `parseScrollSpeed rejects a negative value`() {
        val error = assertThrows<MaestroException.InvalidCommand> {
            NumericFields.parseScrollSpeed("-5")
        }
        assertThat(error.message).contains("-5")
        assertThat(error.message).contains("between 0 and 100")
    }

    @Test
    fun `parseScrollSpeed rejects a non-numeric value with a clear InvalidCommand`() {
        val error = assertThrows<MaestroException.InvalidCommand> {
            NumericFields.parseScrollSpeed("undefined")
        }
        assertThat(error.message).contains("undefined")
        assertThat(error.message).contains("speed")
    }

    // ---- staticErrors: the shared Layer-2 (WorkspaceValidator) check reusing the same parsers ----

    @Test
    fun `staticErrors flags a literal invalid index on a selector`() {
        val command = TapOnElementCommand(selector = ElementSelector(textRegex = "Foo", index = "undefined"))

        val errors = NumericFields.staticErrors(command)

        assertThat(errors).isNotEmpty()
        assertThat(errors.first()).contains("undefined")
        assertThat(errors.first()).contains("index")
    }

    @Test
    fun `staticErrors ignores an index that is a JS variable, deferring it to runtime`() {
        val command = TapOnElementCommand(selector = ElementSelector(textRegex = "Foo", index = "\${i}"))

        assertThat(NumericFields.staticErrors(command)).isEmpty()
    }

    @Test
    fun `staticErrors accepts a valid literal index`() {
        val command = TapOnElementCommand(selector = ElementSelector(textRegex = "Foo", index = "2"))

        assertThat(NumericFields.staticErrors(command)).isEmpty()
    }

    @Test
    fun `staticErrors flags a bad index on a nested relative selector`() {
        val command = TapOnElementCommand(
            selector = ElementSelector(
                textRegex = "Foo",
                below = ElementSelector(textRegex = "Bar", index = "abc"),
            ),
        )

        assertThat(NumericFields.staticErrors(command)).isNotEmpty()
    }

    @Test
    fun `staticErrors flags a literal invalid point`() {
        val command = TapOnPointV2Command(point = "undefined,90")

        val errors = NumericFields.staticErrors(command)

        assertThat(errors).isNotEmpty()
        assertThat(errors.first()).contains("point")
    }

    @Test
    fun `staticErrors flags a bad element-relative point`() {
        val command = TapOnElementCommand(
            selector = ElementSelector(textRegex = "Foo"),
            relativePoint = "abc,def",
        )

        val errors = NumericFields.staticErrors(command)

        assertThat(errors).isNotEmpty()
        assertThat(errors.first()).contains("point")
    }

    @Test
    fun `staticErrors flags a literal invalid scroll speed`() {
        val command = ScrollUntilVisibleCommand(
            selector = ElementSelector(textRegex = "Foo"),
            direction = ScrollDirection.DOWN,
            scrollDuration = "undefined",
            visibilityPercentage = 100,
            centerElement = false,
        )

        val errors = NumericFields.staticErrors(command)

        assertThat(errors).isNotEmpty()
        assertThat(errors.first()).contains("speed")
    }

    @Test
    fun `staticErrors flags an out-of-range literal scroll speed`() {
        val command = ScrollUntilVisibleCommand(
            selector = ElementSelector(textRegex = "Foo"),
            direction = ScrollDirection.DOWN,
            scrollDuration = "500",
            visibilityPercentage = 100,
            centerElement = false,
        )

        val errors = NumericFields.staticErrors(command)

        assertThat(errors).isNotEmpty()
        assertThat(errors.first()).contains("between 0 and 100")
    }

    @Test
    fun `staticErrors flags a literal percent point outside 0 to 100`() {
        val command = TapOnPointV2Command(point = "150%,150%")

        val errors = NumericFields.staticErrors(command)

        assertThat(errors).isNotEmpty()
        assertThat(errors.first()).contains("percentages must be between 0 and 100")
    }

    @Test
    fun `staticErrors accepts an in-range percent point`() {
        assertThat(NumericFields.staticErrors(TapOnPointV2Command(point = "50%,90%"))).isEmpty()
    }

    @Test
    fun `staticErrors accepts an absolute point above 100 since pixel bounds are runtime-only`() {
        assertThat(NumericFields.staticErrors(TapOnPointV2Command(point = "500,600"))).isEmpty()
    }

    @Test
    fun `staticErrors flags a bad index inside a composite while-condition`() {
        val command = RepeatCommand(
            condition = Condition(visible = ElementSelector(textRegex = "Foo", index = "abc")),
            commands = emptyList(),
        )

        assertThat(NumericFields.staticErrors(command)).isNotEmpty()
    }

    @Test
    fun `staticErrors flags a bad index inside a composite subcommand`() {
        val command = RepeatCommand(
            commands = listOf(
                MaestroCommand(tapOnElement = TapOnElementCommand(selector = ElementSelector(index = "abc"))),
            ),
        )

        assertThat(NumericFields.staticErrors(command)).isNotEmpty()
    }
}
