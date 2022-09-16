package maestro.orchestra

import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.google.common.truth.Truth.assertThat
import maestro.KeyCode
import maestro.Point
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Test

internal class MaestroCommandSerializationTest {
    @Test
    fun `serialize TapOnElementCommand`() {
        // given
        val command = MaestroCommand(
            command = TapOnElementCommand(
                selector = ElementSelector(textRegex = "[A-f0-9]"),
                retryIfNoChange = false,
                waitUntilVisible = true,
                longPress = false,
            )
        )

        // when
        val serializedCommandJson = command.toJson()
        val deserializedCommand = objectMapper.readValue(serializedCommandJson, MaestroCommand::class.java)

        // then
        @Language("json")
        val expectedJson = """
            {
              "tapOnElement" : {
                "selector" : {
                  "textRegex" : "[A-f0-9]",
                  "optional" : false
                },
                "retryIfNoChange" : false,
                "waitUntilVisible" : true,
                "longPress" : false
              }
            }
          """.trimIndent()

        assertThat(serializedCommandJson)
            .isEqualTo(expectedJson)
        assertThat(deserializedCommand)
            .isEqualTo(command)
    }

    @Test
    fun `serialize TapOnPointCommand`() {
        // given
        val command = MaestroCommand(
            TapOnPointCommand(
                x = 100,
                y = 100,
                retryIfNoChange = false,
                waitUntilVisible = true,
                longPress = false,
            )
        )

        // when
        val serializedCommandJson = command.toJson()
        val deserializedCommand = objectMapper.readValue(serializedCommandJson, MaestroCommand::class.java)

        // then
        @Language("json")
        val expectedJson = """
            {
              "tapOnPoint" : {
                "x" : 100,
                "y" : 100,
                "retryIfNoChange" : false,
                "waitUntilVisible" : true,
                "longPress" : false
              }
            }
          """.trimIndent()
        assertThat(serializedCommandJson)
            .isEqualTo(expectedJson)
        assertThat(deserializedCommand)
            .isEqualTo(command)
    }

    @Test
    fun `serialize ScrollCommand`() {
        // given
        val command = MaestroCommand(
            ScrollCommand()
        )

        // when
        val serializedCommandJson = command.toJson()
        val deserializedCommand = objectMapper.readValue(serializedCommandJson, MaestroCommand::class.java)

        // then
        @Language("json")
        val expectedJson = """
            {
              "scrollCommand" : { }
            }
          """.trimIndent()
        assertThat(serializedCommandJson)
            .isEqualTo(expectedJson)
        assertThat(deserializedCommand)
            .isEqualTo(command)
    }

    @Test
    fun `serialize SwipeCommand`() {
        // given
        val command = MaestroCommand(
            SwipeCommand(
                Point(10, 10),
                Point(100, 100),
            )
        )

        // when
        val serializedCommandJson = command.toJson()
        val deserializedCommand = objectMapper.readValue(serializedCommandJson, MaestroCommand::class.java)

        // then
        @Language("json")
        val expectedJson = """
            {
              "swipeCommand" : {
                "startPoint" : {
                  "x" : 10,
                  "y" : 10
                },
                "endPoint" : {
                  "x" : 100,
                  "y" : 100
                }
              }
            }
          """.trimIndent()
        assertThat(serializedCommandJson)
            .isEqualTo(expectedJson)
        assertThat(deserializedCommand)
            .isEqualTo(command)
    }

    @Test
    fun `serialize BackPressCommand`() {
        // given
        val command = MaestroCommand(
            BackPressCommand()
        )

        // when
        val serializedCommandJson = command.toJson()
        val deserializedCommand = objectMapper.readValue(serializedCommandJson, MaestroCommand::class.java)

        // then
        @Language("json")
        val expectedJson = """
            {
              "backPressCommand" : { }
            }
          """.trimIndent()
        assertThat(serializedCommandJson)
            .isEqualTo(expectedJson)
        assertThat(deserializedCommand)
            .isEqualTo(command)
    }

    @Test
    fun `serialize AssertCommand`() {
        // given
        val command = MaestroCommand(
            AssertCommand(
                ElementSelector(textRegex = "[A-f0-9]"),
                ElementSelector(textRegex = "\\s")
            )
        )

        // when
        val serializedCommandJson = command.toJson()
        val deserializedCommand = objectMapper.readValue(serializedCommandJson, MaestroCommand::class.java)

        // then
        @Language("json")
        val expectedJson = """
            {
              "assertCommand" : {
                "visible" : {
                  "textRegex" : "[A-f0-9]",
                  "optional" : false
                },
                "notVisible" : {
                  "textRegex" : "\\s",
                  "optional" : false
                }
              }
            }
          """.trimIndent()
        assertThat(serializedCommandJson)
            .isEqualTo(expectedJson)
        assertThat(deserializedCommand)
            .isEqualTo(command)
    }

    @Test
    fun `serialize InputTextCommand`() {
        // given
        val command = MaestroCommand(
            InputTextCommand("Hello, world!")
        )

        // when
        val serializedCommandJson = command.toJson()
        val deserializedCommand = objectMapper.readValue(serializedCommandJson, MaestroCommand::class.java)

        // then
        @Language("json")
        val expectedJson = """
            {
              "inputTextCommand" : {
                "text" : "Hello, world!"
              }
            }
          """.trimIndent()
        assertThat(serializedCommandJson)
            .isEqualTo(expectedJson)
        assertThat(deserializedCommand)
            .isEqualTo(command)
    }

    @Test
    fun `serialize LaunchAppCommand`() {
        // given
        val command = MaestroCommand(
            LaunchAppCommand("com.twitter.android")
        )

        // when
        val serializedCommandJson = command.toJson()
        val deserializedCommand = objectMapper.readValue(serializedCommandJson, MaestroCommand::class.java)

        // then
        @Language("json")
        val expectedJson = """
            {
              "launchAppCommand" : {
                "appId" : "com.twitter.android"
              }
            }
          """.trimIndent()
        assertThat(serializedCommandJson)
            .isEqualTo(expectedJson)
        assertThat(deserializedCommand)
            .isEqualTo(command)
    }

    @Test
    fun `serialize ApplyConfigurationCommand`() {
        // given
        val command = MaestroCommand(
            ApplyConfigurationCommand(
                MaestroConfig(
                    appId = "com.twitter.android",
                    name = "Twitter",
                )
            )
        )

        // when
        val serializedCommandJson = command.toJson()
        val deserializedCommand = objectMapper.readValue(serializedCommandJson, MaestroCommand::class.java)

        // then
        @Language("json")
        val expectedJson = """
            {
              "applyConfigurationCommand" : {
                "config" : {
                  "appId" : "com.twitter.android",
                  "name" : "Twitter",
                  "ext" : { }
                }
              }
            }
          """.trimIndent()
        assertThat(serializedCommandJson)
            .isEqualTo(expectedJson)
        assertThat(deserializedCommand)
            .isEqualTo(command)
    }

    @Test
    fun `serialize OpenLinkCommand`() {
        // given
        val command = MaestroCommand(
            OpenLinkCommand("https://mobile.dev")
        )

        // when
        val serializedCommandJson = command.toJson()
        val deserializedCommand = objectMapper.readValue(serializedCommandJson, MaestroCommand::class.java)

        // then
        @Language("json")
        val expectedJson = """
            {
              "openLinkCommand" : {
                "link" : "https://mobile.dev"
              }
            }
          """.trimIndent()
        assertThat(serializedCommandJson)
            .isEqualTo(expectedJson)
        assertThat(deserializedCommand)
            .isEqualTo(command)
    }

    @Test
    fun `serialize PressKeyCommand`() {
        // given
        val command = MaestroCommand(
            PressKeyCommand(KeyCode.ENTER)
        )

        // when
        val serializedCommandJson = command.toJson()
        val deserializedCommand = objectMapper.readValue(serializedCommandJson, MaestroCommand::class.java)

        // then
        @Language("json")
        val expectedJson = """
            {
              "pressKeyCommand" : {
                "code" : "ENTER"
              }
            }
          """.trimIndent()
        assertThat(serializedCommandJson)
            .isEqualTo(expectedJson)
        assertThat(deserializedCommand)
            .isEqualTo(command)
    }

    @Test
    fun `serialize EraseTextCommand`() {
        // given
        val command = MaestroCommand(
            EraseTextCommand(128)
        )

        // when
        val serializedCommandJson = command.toJson()
        val deserializedCommand = objectMapper.readValue(serializedCommandJson, MaestroCommand::class.java)

        // then
        @Language("json")
        val expectedJson = """
            {
              "eraseTextCommand" : {
                "charactersToErase" : 128
              }
            }
          """.trimIndent()
        assertThat(serializedCommandJson)
            .isEqualTo(expectedJson)
        assertThat(deserializedCommand)
            .isEqualTo(command)
    }

    private fun MaestroCommand.toJson(): String =
        objectMapper
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(this)

    private val objectMapper = ObjectMapper()
        .setSerializationInclusion(Include.NON_NULL)
        .registerModule(KotlinModule.Builder().build())
}
