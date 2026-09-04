package maestro.orchestra.yaml

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.orchestra.AirplaneValue
import maestro.orchestra.DarkModeValue
import maestro.orchestra.SetAirplaneModeCommand
import maestro.orchestra.SetDarkModeCommand
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Paths

/**
 * `setDarkMode` and `setAirplaneMode` accept a bare value or a map, and their accepted vocabulary is
 * the `yamlValue` of each [DarkModeValue] / [AirplaneValue] constant -- not the constant names, which
 * are the MaestroCommand wire format and are pinned separately by the last test here.
 */
class YamlSetModeTest {

    private val flowPath = Paths.get("test.yaml")

    @Test
    fun `setDarkMode accepts a bare value`() {
        val command = parseSingle("setDarkMode: enabled")

        assertThat(command).isEqualTo(SetDarkModeCommand(DarkModeValue.Enable))
    }

    @Test
    fun `setDarkMode accepts the map form`() {
        val command = parseSingle(
            """
            setDarkMode:
              value: disabled
              label: "Turn dark mode off"
            """.trimIndent()
        )

        assertThat(command).isEqualTo(SetDarkModeCommand(DarkModeValue.Disable, label = "Turn dark mode off"))
    }

    /** Regression: the map form read `value` and `label` but silently dropped `optional`. */
    @Test
    fun `setDarkMode keeps optional from the map form`() {
        val command = parseSingle(
            """
            setDarkMode:
              value: enabled
              optional: true
            """.trimIndent()
        )

        assertThat(command).isEqualTo(SetDarkModeCommand(DarkModeValue.Enable, optional = true))
    }

    @Test
    fun `setDarkMode rejects an unknown value`() {
        assertThrows<Exception> { parseSingle("setDarkMode: sometimes") }
    }

    @Test
    fun `setAirplaneMode accepts a bare value`() {
        val command = parseSingle("setAirplaneMode: disabled")

        assertThat(command).isEqualTo(SetAirplaneModeCommand(AirplaneValue.Disable))
    }

    @Test
    fun `setAirplaneMode keeps optional from the map form`() {
        val command = parseSingle(
            """
            setAirplaneMode:
              value: enabled
              optional: true
            """.trimIndent()
        )

        assertThat(command).isEqualTo(SetAirplaneModeCommand(AirplaneValue.Enable, optional = true))
    }

    @Test
    fun `setAirplaneMode rejects an unknown value`() {
        assertThrows<Exception> { parseSingle("setAirplaneMode: maybe") }
    }

    /**
     * The YAML word and the MaestroCommand word are different, and only the YAML one is free to move.
     * The constant names are what `SetDarkModeCommand.value` / `SetAirplaneModeCommand.value` serialize
     * to on the wire the backend persists and the worker sends, so a `@JsonProperty` renaming them there
     * would reject every command already stored or in flight. Pinned here because nothing else looks at
     * that wire, and the YAML tests above pass either way.
     */
    @Test
    fun `the MaestroCommand wire keeps the constant names`() {
        val mapper = jacksonObjectMapper()

        assertThat(mapper.writeValueAsString(SetDarkModeCommand(DarkModeValue.Enable)))
            .contains(""""value":"Enable"""")
        assertThat(mapper.readValue(""" {"value":"Disable"} """, SetDarkModeCommand::class.java).value)
            .isEqualTo(DarkModeValue.Disable)

        assertThat(mapper.writeValueAsString(SetAirplaneModeCommand(AirplaneValue.Enable)))
            .contains(""""value":"Enable"""")
        assertThat(mapper.readValue(""" {"value":"Disable"} """, SetAirplaneModeCommand::class.java).value)
            .isEqualTo(AirplaneValue.Disable)
    }

    private fun parseSingle(command: String) =
        MaestroFlowParser.parseCommand(flowPath, "com.example.app", command).single().asCommand()
}
