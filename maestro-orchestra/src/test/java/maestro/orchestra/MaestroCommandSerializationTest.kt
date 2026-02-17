package maestro.orchestra

import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.google.common.truth.Truth.assertThat
import maestro.DeviceOrientation
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
                label = "My Tap"
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
                "longPress" : false,
                "label" : "My Tap",
                "optional" : false
              }
            }
          """.trimIndent()

        assertThat(serializedCommandJson)
            .isEqualTo(expectedJson)
        assertThat(deserializedCommand)
            .isEqualTo(command)
    }

    @Test
    fun `serialize TapOnElementCommand with relativePoint`() {
        // given
        val command = MaestroCommand(
            command = TapOnElementCommand(
                selector = ElementSelector(textRegex = "Submit"),
                retryIfNoChange = false,
                waitUntilVisible = true,
                longPress = false,
                relativePoint = "50%, 90%",
                label = "Tap Submit Button"
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
                  "textRegex" : "Submit",
                  "optional" : false
                },
                "retryIfNoChange" : false,
                "waitUntilVisible" : true,
                "longPress" : false,
                "relativePoint" : "50%, 90%",
                "label" : "Tap Submit Button",
                "optional" : false
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
                label = "My TapOnPoint"
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
                "longPress" : false,
                "label" : "My TapOnPoint",
                "optional" : false
              }
            }
          """.trimIndent()
        assertThat(serializedCommandJson)
            .isEqualTo(expectedJson)
        assertThat(deserializedCommand)
            .isEqualTo(command)
    }

    @Test
    fun `serialize TapOnPointV2Command`() {
        // given
        val command = MaestroCommand(
            TapOnPointV2Command(
                point = "20,30",
                retryIfNoChange = false,
                longPress = false,
                label = "My TapOnPointV2"
            )
        )

        // when
        val serializedCommandJson = command.toJson()
        val deserializedCommand = objectMapper.readValue(serializedCommandJson, MaestroCommand::class.java)

        // then
        @Language("json")
        val expectedJson = """
            {
              "tapOnPointV2Command" : {
                "point" : "20,30",
                "retryIfNoChange" : false,
                "longPress" : false,
                "label" : "My TapOnPointV2",
                "optional" : false
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
              "scrollCommand" : {
                "optional" : false
              }
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
                start = "10, 10",
                end = "100, 100",
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
                },
                "duration" : 400,
                "optional" : false
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
              "backPressCommand" : {
                "optional" : false
              }
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
                },
                "optional" : false
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
                "text" : "Hello, world!",
                "optional" : false
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
                "appId" : "com.twitter.android",
                "optional" : false
              }
            }
          """.trimIndent()
        assertThat(serializedCommandJson)
            .isEqualTo(expectedJson)
        assertThat(deserializedCommand)
            .isEqualTo(command)
    }

    @Test
    fun `serialize SetPermissionsCommand`() {
        // given
        val command = MaestroCommand(
            SetPermissionsCommand("com.twitter.android", permissions = mapOf("all" to "deny", "notifications" to "unset"))
        )

        // when
        val serializedCommandJson = command.toJson()
        val deserializedCommand = objectMapper.readValue(serializedCommandJson, MaestroCommand::class.java)

        // then
        @Language("json")
        val expectedJson = """
            {
              "setPermissionsCommand" : {
                "appId" : "com.twitter.android",
                "permissions" : {
                  "all" : "deny",
                  "notifications" : "unset"
                },
                "optional" : false
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
                  "tags" : [ ],
                  "ext" : { },
                  "properties" : { }
                },
                "optional" : false
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
                "link" : "https://mobile.dev",
                "optional" : false
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
                "code" : "ENTER",
                "optional" : false
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
                "charactersToErase" : 128,
                "optional" : false
              }
            }
          """.trimIndent()
        assertThat(serializedCommandJson)
            .isEqualTo(expectedJson)
        assertThat(deserializedCommand)
            .isEqualTo(command)
    }

    @Test
    fun `serialize TakeScreenshotCommand`() {
        // given
        val command = MaestroCommand(
            TakeScreenshotCommand("screenshot.png", cropOn = ElementSelector(textRegex = "[A-f0-9]"))
        )

        // when
        val serializedCommandJson = command.toJson()
        val deserializedCommand = objectMapper.readValue(serializedCommandJson, MaestroCommand::class.java)

        // then
        @Language("json")
        val expectedJson = """
            {
              "takeScreenshotCommand" : {
                "path" : "screenshot.png",
                "cropOn" : {
                  "textRegex" : "[A-f0-9]",
                  "optional" : false
                },
                "optional" : false
              }
            }
          """.trimIndent()
        assertThat(serializedCommandJson)
            .isEqualTo(expectedJson)
        assertThat(deserializedCommand)
            .isEqualTo(command)
    }

    @Test
    fun `serialize InputRandomCommand with text`() {
        // given
        val command = MaestroCommand(
            InputRandomCommand(InputRandomType.TEXT, 2)
        )

        // when
        val serializedCommandJson = command.toJson()
        val deserializedCommand = objectMapper.readValue(serializedCommandJson, MaestroCommand::class.java)

        // then
        @Language("json")
        val expectedJson = """
            {
              "inputRandomTextCommand" : {
                "inputType" : "TEXT",
                "length" : 2,
                "optional" : false
              }
            }
          """.trimIndent()
        assertThat(serializedCommandJson)
            .isEqualTo(expectedJson)
        assertThat(deserializedCommand)
            .isEqualTo(command)
    }

    @Test
    fun `serialize InputRandomCommand with number`() {
        // given
        val command = MaestroCommand(
            InputRandomCommand(InputRandomType.NUMBER, 3)
        )

        // when
        val serializedCommandJson = command.toJson()
        val deserializedCommand = objectMapper.readValue(serializedCommandJson, MaestroCommand::class.java)

        // then
        @Language("json")
        val expectedJson = """
            {
              "inputRandomTextCommand" : {
                "inputType" : "NUMBER",
                "length" : 3,
                "optional" : false
              }
            }
          """.trimIndent()
        assertThat(serializedCommandJson)
            .isEqualTo(expectedJson)
        assertThat(deserializedCommand)
            .isEqualTo(command)
    }

    @Test
    fun `serialize InputRandomCommand with email`() {
        // given
        val command = MaestroCommand(
            InputRandomCommand(InputRandomType.TEXT_EMAIL_ADDRESS)
        )

        // when
        val serializedCommandJson = command.toJson()
        val deserializedCommand = objectMapper.readValue(serializedCommandJson, MaestroCommand::class.java)

        // then
        @Language("json")
        val expectedJson = """
            {
              "inputRandomTextCommand" : {
                "inputType" : "TEXT_EMAIL_ADDRESS",
                "length" : 8,
                "optional" : false
              }
            }
          """.trimIndent()
        assertThat(serializedCommandJson)
            .isEqualTo(expectedJson)
        assertThat(deserializedCommand)
            .isEqualTo(command)
    }

    @Test
    fun `serialize InputRandomCommand with person name`() {
        // given
        val command = MaestroCommand(
            InputRandomCommand(InputRandomType.TEXT_PERSON_NAME, optional = true)
        )

        // when
        val serializedCommandJson = command.toJson()
        val deserializedCommand = objectMapper.readValue(serializedCommandJson, MaestroCommand::class.java)

        // then
        @Language("json")
        val expectedJson = """
            {
              "inputRandomTextCommand" : {
                "inputType" : "TEXT_PERSON_NAME",
                "length" : 8,
                "optional" : true
              }
            }
          """.trimIndent()
        assertThat(serializedCommandJson)
            .isEqualTo(expectedJson)
        assertThat(deserializedCommand)
            .isEqualTo(command)
    }

    @Test
    fun `serialize WaitForAnimationToEndCommand`() {
        // given
        val command = MaestroCommand(
            WaitForAnimationToEndCommand(timeout = 9)
        )

        // when
        val serializedCommandJson = command.toJson()
        val deserializedCommand = objectMapper.readValue(serializedCommandJson, MaestroCommand::class.java)

        // then
        @Language("json")
        val expectedJson = """
            {
              "waitForAnimationToEndCommand" : {
                "timeout" : 9,
                "optional" : false
              }
            }
          """.trimIndent()
        assertThat(serializedCommandJson)
            .isEqualTo(expectedJson)
        assertThat(deserializedCommand)
            .isEqualTo(command)
    }

    @Test
    fun `serialize SetOrientationCommand`() {
        // given
        val command = MaestroCommand(
            SetOrientationCommand(DeviceOrientation.PORTRAIT)
        )

        // when
        val serializedCommandJson = command.toJson()
        val deserializedCommand = objectMapper.readValue(serializedCommandJson, MaestroCommand::class.java)

        // then
        @Language("json")
        val expectedJson = """
            {
              "setOrientationCommand" : {
                "orientation" : "PORTRAIT",
                "optional" : false
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
