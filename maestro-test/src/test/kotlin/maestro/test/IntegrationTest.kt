package maestro.test

import com.google.common.truth.Truth.assertThat
import com.oracle.truffle.js.nodes.function.EvalNode
import maestro.KeyCode
import maestro.Maestro
import maestro.MaestroException
import maestro.Point
import maestro.SwipeDirection
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.DefineVariablesCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.Orchestra
import maestro.orchestra.error.UnicodeNotSupportedError
import maestro.orchestra.util.Env.withEnv
import maestro.orchestra.yaml.YamlCommandReader
import maestro.test.drivers.FakeDriver
import maestro.test.drivers.FakeDriver.Event
import maestro.test.drivers.FakeLayoutElement
import maestro.test.drivers.FakeLayoutElement.Bounds
import maestro.test.drivers.FakeTimer
import maestro.utils.MaestroTimer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.awt.Color
import java.io.File
import java.nio.file.Paths
import kotlin.system.measureTimeMillis
import maestro.orchestra.util.Env.withDefaultEnvVars

class IntegrationTest {

    val fakeTimer = FakeTimer()

    @BeforeEach
    fun setUp() {
        MaestroTimer.setTimerFunc(fakeTimer.timer())
    }

    @AfterEach
    internal fun tearDown() {
        File("041_take_screenshot_with_filename.png").delete()
        File("099_screen_recording.mp4").delete()
        File("028_env.mp4").delete()
    }

    @Test
    fun `Case 001 - Assert element visible by id`() {
        // Given
        val commands = readCommands("001_assert_visible_by_id")

        val driver = driver {
            element {
                id = "element_id"
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertNoInteraction()
    }

    @Test
    fun `Case 002 - Assert element visible by text`() {
        // Given
        val commands = readCommands("002_assert_visible_by_text")

        val driver = driver {
            element {
                text = "Element Text"
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertNoInteraction()
    }

    @Test
    fun `Case 003 - Assert element visible by size`() {
        // Given
        val commands = readCommands("003_assert_visible_by_size")

        val driver = driver {
            element {
                text = "Element Text"
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertNoInteraction()
    }

    @Test
    fun `Case 004 - Assert visible - no element with id`() {
        // Given
        val commands = readCommands("004_assert_no_visible_element_with_id")

        val driver = driver {
            element {
                id = "another_id"
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        // When & Then
        assertThrows<MaestroException.AssertionFailure> {
            Maestro(driver).use {
                orchestra(it).runFlow(commands)
            }
        }
    }

    @Test
    fun `Case 005 - Assert visible - no element with text`() {
        // Given
        val commands = readCommands("005_assert_no_visible_element_with_text")

        val driver = driver {
            element {
                text = "Some other text"
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        // When & Then
        assertThrows<MaestroException.AssertionFailure> {
            Maestro(driver).use {
                orchestra(it).runFlow(commands)
            }
        }
    }

    @Test
    fun `Case 006 - Assert visible - no element with size`() {
        // Given
        val commands = readCommands("005_assert_no_visible_element_with_text")

        val driver = driver {
            element {
                text = "Some other text"
                bounds = Bounds(0, 0, 101, 101)
            }
        }

        // When & Then
        assertThrows<MaestroException.AssertionFailure> {
            Maestro(driver).use {
                orchestra(it).runFlow(commands)
            }
        }
    }

    @Test
    fun `Case 007 - Assert element visible by size with tolerance`() {
        // Given
        val commands = readCommands("007_assert_visible_by_size_with_tolerance")

        val driver = driver {
            element {
                text = "Element Text"
                bounds = Bounds(0, 0, 101, 101)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertNoInteraction()
    }

    @Test
    fun `Case 008 - Tap on element - Do not retry by default if no UI change`() {
        // Given
        val commands = readCommands("008_tap_on_element")

        val driver = driver {
            element {
                text = "Primary button"
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEventCount(Event.Tap(Point(50, 50)), expectedCount = 1)
    }

    @Test
    fun `Case 008 - Tap on element - Do not retry if view hierarchy changed`() {
        // Given
        val commands = readCommands("008_tap_on_element")

        val driver = driver {
            element {
                text = "Primary button"
                bounds = Bounds(0, 0, 100, 100)

                onClick = { element ->
                    element.text = "Updated text"
                }
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEventCount(Event.Tap(Point(50, 50)), expectedCount = 1)
    }

    @Test
    fun `Case 008 - Tap on element - Do not retry if screenshot changed`() {
        // Given
        val commands = readCommands("008_tap_on_element")

        val driver = driver {
            element {
                text = "Primary button"
                bounds = Bounds(0, 0, 100, 100)

                onClick = { element ->
                    element.color = Color.RED
                }
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEventCount(Event.Tap(Point(50, 50)), expectedCount = 1)
    }

    @Test
    fun `Case 009 - Skip optional elements`() {
        // Given
        val commands = readCommands("009_skip_optional_elements")

        val driver = driver {
            element {
                text = "Non Optional"
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
    }

    @Test
    fun `Case 010 - Scroll`() {
        // Given
        val commands = readCommands("010_scroll")

        val driver = driver {
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertHasEvent(Event.Scroll)
    }

    @Test
    fun `Case 011 - Back press`() {
        // Given
        val commands = readCommands("011_back_press")

        val driver = driver {
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertHasEvent(Event.BackPress)
    }

    @Test
    fun `Case 012 - Input text`() {
        // Given
        val commands = readCommands("012_input_text")

        val driver = driver {
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertHasEvent(Event.InputText("Hello World"))
        driver.assertHasEvent(Event.InputText("user@example.com"))
        driver.assertCurrentTextInput("Hello Worlduser@example.com")
    }

    @Test
    fun `Case 013 - Launch app`() {
        // Given
        val commands = readCommands("013_launch_app")

        val driver = driver {
        }
        driver.addInstalledApp("com.example.app")

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEvents(
            listOf(
                Event.StopApp("com.example.app"),
                Event.LaunchApp("com.example.app")
            )
        )
    }

    @Test
    fun `Case 014 - Tap on point`() {
        // Given
        val commands = readCommands("014_tap_on_point")

        val driver = driver {
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertHasEvent(Event.Tap(Point(100, 200)))
    }

    @Test
    fun `Case 015 - Tap on element relative position`() {
        // Given
        val commands = readCommands("015_element_relative_position")

        val driver = driver {
            element {
                text = "Top Left"
                bounds = Bounds(0, 100, 100, 200)
            }
            element {
                text = "Top"
                bounds = Bounds(100, 100, 200, 200)
            }
            element {
                text = "Top Right"
                bounds = Bounds(200, 100, 300, 200)
            }
            element {
                text = "Left"
                bounds = Bounds(0, 200, 100, 300)
            }
            element {
                text = "Middle"
                bounds = Bounds(100, 200, 200, 300)
            }
            element {
                text = "Right"
                bounds = Bounds(200, 200, 300, 300)
            }
            element {
                text = "Bottom Left"
                bounds = Bounds(0, 300, 100, 400)
            }
            element {
                text = "Bottom"
                bounds = Bounds(100, 300, 200, 400)
            }
            element {
                text = "Bottom Right"
                bounds = Bounds(200, 300, 300, 400)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEvents(
            listOf(
                Event.Tap(Point(150, 150)), // Top
                Event.Tap(Point(150, 350)), // Bottom
                Event.Tap(Point(50, 250)), // Left
                Event.Tap(Point(250, 250)), // Right
                Event.Tap(Point(50, 150)), // Top Left
                Event.Tap(Point(250, 150)), // Top Right
                Event.Tap(Point(50, 350)), // Bottom Left
                Event.Tap(Point(250, 350)), // Bottom Right
            )
        )
    }

    @Test
    fun `Case 016 - Multiline text`() {
        // Given
        val commands = readCommands("016_multiline_text")

        val driver = driver {
            element {
                text = "Hello World\nHere is a second line"
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertHasEvent(Event.Tap(Point(50, 50)))
    }

    @Test
    fun `Case 017 - Swipe`() {
        // Given
        val commands = readCommands("017_swipe")

        val driver = driver {
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertHasEvent(Event.Swipe(start = Point(100, 500), End = Point(100, 200), durationMs = 3000))
    }

    @Test
    fun `Case 018 - Contains child`() {
        // Given
        val commands = readCommands("018_contains_child")

        val driver = driver {
            element {
                bounds = Bounds(0, 0, 200, 200)

                element {
                    text = "Child"
                    bounds = Bounds(0, 0, 100, 100)
                }
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertHasEvent(Event.Tap(Point(100, 100)))
    }

    @Test
    fun `Case 019 - Do not wait until visible`() {
        // Given
        val commands = readCommands("019_dont_wait_for_visibility")

        val driver = driver {
            element {
                text = "Button"
                bounds = Bounds(0, 0, 100, 100)
            }
            element {
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertHasEvent(Event.Tap(Point(50, 50)))
        fakeTimer.assertNoEvent(MaestroTimer.Reason.WAIT_UNTIL_VISIBLE)
    }

    @Test
    fun `Case 020 - Parse config`() {
        // When
        val commands = readCommands("020_parse_config")

        // Then
        assertThat(commands).isEqualTo(
            listOf(
                MaestroCommand(
                    DefineVariablesCommand(
                        env = mapOf("MAESTRO_FILENAME" to "020_parse_config")
                    )
                ),
                MaestroCommand(
                    ApplyConfigurationCommand(
                        config = MaestroConfig(
                            appId = "com.example.app"
                        )
                    )
                ),
                MaestroCommand(
                    LaunchAppCommand(
                        appId = "com.example.app"
                    )
                )
            )
        )
    }

    @Test
    fun `Case 021 - Launch app with clear state`() {
        // Given
        val commands = readCommands("021_launch_app_with_clear_state")

        val driver = driver {
        }
        driver.addInstalledApp("com.example.app")

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertHasEvent(Event.ClearState("com.example.app"))
        driver.assertHasEvent(Event.LaunchApp("com.example.app"))
    }

    @Test
    fun `Case 022 - Launch app that is not installed`() {
        // Given
        val commands = readCommands("022_launch_app_that_is_not_installed")

        val driver = driver {
        }

        // When & Then
        assertThrows<MaestroException.UnableToLaunchApp> {
            Maestro(driver).use {
                orchestra(it).runFlow(commands)
            }
        }
    }

    @Test
    fun `Case 025 - Tap on element relative position using shortcut`() {
        // Given
        val commands = readCommands("025_element_relative_position_shortcut")

        val driver = driver {
            element {
                text = "Top Left"
                bounds = Bounds(0, 100, 100, 200)
            }
            element {
                text = "Top"
                bounds = Bounds(100, 100, 200, 200)
            }
            element {
                text = "Top Right"
                bounds = Bounds(200, 100, 300, 200)
            }
            element {
                text = "Left"
                bounds = Bounds(0, 200, 100, 300)
            }
            element {
                text = "Middle"
                bounds = Bounds(100, 200, 200, 300)
            }
            element {
                text = "Right"
                bounds = Bounds(200, 200, 300, 300)
            }
            element {
                text = "Bottom Left"
                bounds = Bounds(0, 300, 100, 400)
            }
            element {
                text = "Bottom"
                bounds = Bounds(100, 300, 200, 400)
            }
            element {
                text = "Bottom Right"
                bounds = Bounds(200, 300, 300, 400)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEvents(
            listOf(
                Event.Tap(Point(150, 150)), // Top
                Event.Tap(Point(150, 350)), // Bottom
                Event.Tap(Point(50, 250)), // Left
                Event.Tap(Point(250, 250)), // Right
                Event.Tap(Point(50, 150)), // Top Left
                Event.Tap(Point(250, 150)), // Top Right
                Event.Tap(Point(50, 350)), // Bottom Left
                Event.Tap(Point(250, 350)), // Bottom Right
            )
        )
    }

    @Test
    fun `Case 026 - Assert not visible - no element with id`() {
        // Given
        val commands = readCommands("026_assert_not_visible")

        val driver = driver {
            element {
                id = "another_id"
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
    }

    @Test
    fun `Case 026 - Assert not visible - element with id is present`() {
        // Given
        val commands = readCommands("026_assert_not_visible")

        val driver = driver {
            element {
                id = "element_id"
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        // When & Then
        assertThrows<MaestroException.AssertionFailure> {
            Maestro(driver).use {
                orchestra(it).runFlow(commands)
            }
        }
    }

    @Test
    fun `Case 027 - Open link`() {
        // Given
        val commands = readCommands("027_open_link")

        val driver = driver {}

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEvents(
            listOf(
                Event.OpenLink("https://example.com")
            )
        )
    }

    @Test
    fun `Case 028 - Env`() {
        // Given
        val commands = readCommands("028_env") {
            mapOf(
                "APP_ID" to "com.example.app",
                "BUTTON_ID" to "button_id",
                "BUTTON_TEXT" to "button_text",
                "PASSWORD" to "testPassword",
                "NON_EXISTENT_TEXT" to "nonExistentText",
                "NON_EXISTENT_ID" to "nonExistentId",
                "URL" to "secretUrl",
                "LAT" to "37.82778",
                "LNG" to "-122.48167",
            )
        }

        val driver = driver {

            element {
                id = "button_id"
                text = "button_text"
                bounds = Bounds(0, 0, 100, 100)
            }

        }
        driver.addInstalledApp("com.example.app")

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEvents(
            listOf(
                Event.LaunchApp(appId = "com.example.app"),
                Event.Tap(Point(50, 50)),
                Event.Tap(Point(50, 50)),
                Event.InputText("\${PASSWORD} is testPassword"),
                Event.OpenLink("https://example.com/secretUrl"),
                Event.SetLocation(latitude = 37.82778, longitude = -122.48167),
                Event.StartRecording,
            )
        )
        assert(File("028_env.mp4").exists())
    }

    @Test
    fun `Case 029 - Long press on element`() {
        // Given
        val commands = readCommands("029_long_press_on_element")

        val driver = driver {
            element {
                text = "Primary button"
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertHasEvent(Event.LongPress(Point(50, 50)))
    }

    @Test
    fun `Case 030 - Long press on point`() {
        // Given
        val commands = readCommands("030_long_press_on_point")

        val driver = driver {
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertHasEvent(Event.LongPress(Point(100, 200)))
    }

    @Test
    fun `Case 031 - Traits`() {
        // Given
        val commands = readCommands("031_traits")

        val driver = driver {
            element {
                text = "Text"
                bounds = Bounds(0, 0, 200, 100)
            }
            element {
                text = "Square"
                bounds = Bounds(0, 100, 100, 200)
            }
            element {
                text = String(CharArray(500))   // Long text
                bounds = Bounds(0, 200, 200, 400)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEvents(
            listOf(
                Event.Tap(Point(100, 50)),  // Text
                Event.Tap(Point(50, 150)),  // Square
                Event.Tap(Point(100, 300)),  // Long text
            )
        )
    }

    @Test
    fun `Case 032 - Element index`() {
        // Given
        val commands = readCommands("032_element_index")

        val driver = driver {
            element {
                text = "Item 2"
                bounds = Bounds(0, 200, 100, 300)
            }
            element {
                text = "Item 1"
                bounds = Bounds(0, 100, 100, 200)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEvents(
            listOf(
                Event.Tap(Point(50, 150)),  // Item 1
                Event.Tap(Point(50, 250)),  // Item 2
            )
        )
    }

    @Test
    fun `Case 033 - Text with number`() {
        // Given
        val commands = readCommands("033_int_text")

        val driver = driver {
            element {
                text = "2022"
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertHasEvent(Event.Tap(Point(50, 50)))
    }

    @Test
    fun `Case 034 - Press key`() {
        // Given
        val commands = readCommands("034_press_key")

        val driver = driver {
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        driver.assertHasEvent(Event.PressKey(KeyCode.ENTER))
        driver.assertHasEvent(Event.PressKey(KeyCode.BACKSPACE))
        driver.assertHasEvent(Event.PressKey(KeyCode.HOME))
        driver.assertHasEvent(Event.PressKey(KeyCode.BACK))
        driver.assertHasEvent(Event.PressKey(KeyCode.VOLUME_UP))
        driver.assertHasEvent(Event.PressKey(KeyCode.VOLUME_DOWN))
        driver.assertHasEvent(Event.PressKey(KeyCode.LOCK))
        driver.assertHasEvent(Event.PressKey(KeyCode.REMOTE_UP))
        driver.assertHasEvent(Event.PressKey(KeyCode.REMOTE_DOWN))
        driver.assertHasEvent(Event.PressKey(KeyCode.REMOTE_LEFT))
        driver.assertHasEvent(Event.PressKey(KeyCode.REMOTE_RIGHT))
        driver.assertHasEvent(Event.PressKey(KeyCode.REMOTE_CENTER))
        driver.assertHasEvent(Event.PressKey(KeyCode.REMOTE_PLAY_PAUSE))
        driver.assertHasEvent(Event.PressKey(KeyCode.REMOTE_STOP))
        driver.assertHasEvent(Event.PressKey(KeyCode.REMOTE_NEXT))
        driver.assertHasEvent(Event.PressKey(KeyCode.REMOTE_PREVIOUS))
        driver.assertHasEvent(Event.PressKey(KeyCode.REMOTE_REWIND))
        driver.assertHasEvent(Event.PressKey(KeyCode.REMOTE_FAST_FORWARD))
        driver.assertHasEvent(Event.PressKey(KeyCode.POWER))
        driver.assertHasEvent(Event.PressKey(KeyCode.TAB))
        driver.assertHasEvent(Event.PressKey(KeyCode.REMOTE_SYSTEM_NAVIGATION_UP))
        driver.assertHasEvent(Event.PressKey(KeyCode.REMOTE_SYSTEM_NAVIGATION_DOWN))
        driver.assertHasEvent(Event.PressKey(KeyCode.REMOTE_BUTTON_A))
        driver.assertHasEvent(Event.PressKey(KeyCode.REMOTE_BUTTON_B))
        driver.assertHasEvent(Event.PressKey(KeyCode.REMOTE_MENU))
        driver.assertHasEvent(Event.PressKey(KeyCode.TV_INPUT))
        driver.assertHasEvent(Event.PressKey(KeyCode.TV_INPUT_HDMI_1))
        driver.assertHasEvent(Event.PressKey(KeyCode.TV_INPUT_HDMI_2))
        driver.assertHasEvent(Event.PressKey(KeyCode.TV_INPUT_HDMI_3))
    }

    @Test
    fun `Case 035 - Ignore duplicates when refreshing item position`() {
        // Given
        val commands = readCommands("035_refresh_position_ignore_duplicates")

        val driver = driver {

            element {
                id = "icon"
                bounds = Bounds(0, 0, 100, 100)
            }

            element {
                text = "Item"
                bounds = Bounds(0, 100, 100, 200)
            }

            element {
                id = "icon"
                bounds = Bounds(0, 200, 100, 300)
            }

        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        driver.assertHasEvent(Event.Tap(Point(50, 250)))
    }

    @Test
    fun `Case 036 - Erase text with numbers`() {
        // Given
        val commands = readCommands("036_erase_text")

        val driver = driver {
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        driver.assertCurrentTextInput("Hello")
    }

    @Test
    fun `Case 037 - Throw exception when trying to input text with unicode characters`() {
        // Given
        val commands = readCommands("037_unicode_input")

        val driver = driver {
        }

        // When & Then
        assertThrows<UnicodeNotSupportedError> {
            Maestro(driver).use {
                orchestra(it).runFlow(commands)
            }
        }
    }

    @Test
    fun `Case 038 - Partial id matching`() {
        // Given
        val commands = readCommands("038_partial_id")

        val driver = driver {
            element {
                id = "com.google.android.inputmethod.latin:id/another_keyboard_area"
                bounds = Bounds(0, 0, 100, 100)
            }

            element {
                id = "com.google.android.inputmethod.latin:id/keyboard_area"
                bounds = Bounds(0, 100, 100, 200)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        driver.assertEvents(
            listOf(
                Event.Tap(Point(50, 150)),
                Event.Tap(Point(50, 50)),
            )
        )
    }

    @Test
    fun `Case 039 - Hide keyboard`() {
        // Given
        val commands = readCommands("039_hide_keyboard")

        val driver = driver {
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEvents(
            listOf(
                Event.HideKeyboard,
            )
        )
    }

    @Test
    fun `Case 040 - Escape regex characters`() {
        // Given
        val commands = readCommands("040_escape_regex")

        val driver = driver {
            element {
                text = "+123456"
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        driver.assertHasEvent(Event.Tap(Point(50, 50)))
    }

    @Test
    fun `Case 041 - Take screenshot`() {
        // Given
        val commands = readCommands("041_take_screenshot")

        val driver = driver {
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEvents(
            listOf(
                Event.TakeScreenshot,
            )
        )
        assert(File("041_take_screenshot_with_filename.png").exists())
    }

    @Test
    fun `Case 042 - Extended waitUntil`() {
        // Given
        val commands = readCommands("042_extended_wait")

        val driver = driver {
            element {
                text = "Item"
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertNoInteraction()
    }

    @Test
    fun `Case 042 - Extended waitUntil - element not found`() {
        // Given
        val commands = readCommands("042_extended_wait")

        val driver = driver {
        }

        // When running flow - throw an exception
        assertThrows<MaestroException.AssertionFailure> {
            Maestro(driver).use {
                orchestra(it).runFlow(commands)
            }
        }
    }

    @Test
    fun `Case 043 - Stop app`() {
        // Given
        val commands = readCommands("043_stop_app")

        val driver = driver {
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertHasEvent(Event.StopApp("com.example.app"))
        driver.assertHasEvent(Event.StopApp("another.app"))
    }

    @Test
    fun `Case 044 - Clear state`() {
        // Given
        val commands = readCommands("044_clear_state")

        val driver = driver {
        }

        driver.addInstalledApp("com.example.app")
        driver.addInstalledApp("another.app")

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertHasEvent(Event.ClearState("com.example.app"))
        driver.assertHasEvent(Event.ClearState("another.app"))
    }

    @Test
    fun `Case 045 - Clear keychain`() {
        // Given
        val commands = readCommands("045_clear_keychain")

        val driver = driver {
        }

        driver.addInstalledApp("com.example.app")

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEvents(
            listOf(
                Event.ClearKeychain,
                Event.ClearKeychain,
                Event.LaunchApp("com.example.app"),
            )
        )
    }

    @Test
    fun `Case 046 - Run flow`() {
        // Given
        val commands = readCommands("046_run_flow")

        val driver = driver {
            element {
                text = "Primary button"
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        driver.addInstalledApp("com.example.app")
        driver.addInstalledApp("com.other.app")

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEvents(
            listOf(
                Event.LaunchApp("com.example.app"),
                Event.Tap(Point(50, 50)),
            )
        )
    }

    @Test
    fun `Case 047 - Nested run flow`() {
        // Given
        val commands = readCommands("047_run_flow_nested")

        val driver = driver {
            element {
                text = "Primary button"
                bounds = Bounds(0, 0, 100, 100)
            }
            element {
                text = "Secondary button"
                bounds = Bounds(0, 0, 200, 200)
            }
        }

        driver.addInstalledApp("com.example.app")
        driver.addInstalledApp("com.other.app")

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEvents(
            listOf(
                Event.LaunchApp("com.example.app"),
                Event.Tap(Point(50, 50)),
                Event.Tap(Point(100, 100)),
            )
        )
    }

    @Test
    fun `Case 048 - tapOn prioritises clickable elements`() {
        // Given
        val commands = readCommands("048_tapOn_clickable")

        val driver = driver {
            element {
                text = "Button"
                bounds = Bounds(0, 0, 100, 100)
            }
            element {
                text = "Button"
                bounds = Bounds(0, 0, 200, 200)
                clickable = true

                onClick = {
                    text = "Clicked"
                }
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEvents(
            listOf(
                Event.Tap(Point(100, 100)),
            )
        )
    }

    @Test
    fun `Case 049 - Run flow conditionally`() {
        // Given
        val commands = readCommands("049_run_flow_conditionally") {
            mapOf(
                "NOT_CLICKED" to "Not Clicked"
            )
        }

        val driver = driver {
            val indicator = element {
                text = "Not Clicked"
                bounds = Bounds(0, 100, 0, 200)
            }

            element {
                text = "button"
                bounds = Bounds(0, 0, 100, 100)
                onClick = {
                    indicator.text = "Clicked"
                }
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEventCount(Event.Tap(Point(50, 50)), 1)
    }

    @Test
    fun `Case 051 - Set location`() {
        // Given
        val commands = readCommands("051_set_location")

        val driver = driver {
        }

        driver.addInstalledApp("com.example.app")

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEvents(
            listOf(
                Event.LaunchApp("com.example.app"),
                Event.SetLocation(12.5266, 78.2150),
            )
        )
    }

    @Test
    fun `Case 052 - Input random`() {
        // Given
        val commands = readCommands("052_text_random")

        val driver = driver {
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertAllEvent(condition = {
            ((it as? Event.InputText?)?.text?.length ?: -1) >= 5
        })
        driver.assertAnyEvent(condition = {
            val number = try {
                (it as? Event.InputText?)?.text?.toInt() ?: -1
            } catch (e: NumberFormatException) {
                -1
            }
            number in 10000..99999
        })

        driver.assertAnyEvent(condition = {
            val text = (it as? Event.InputText?)?.text ?: ""
            text.contains("@")
        })

        driver.assertAnyEvent(condition = {
            val text = (it as? Event.InputText?)?.text ?: ""
            text.contains(" ")
        })
    }

    @Test
    fun `Case 053 - Repeat N times`() {
        // Given
        val commands = readCommands("053_repeat_times")

        var counter = 0
        val driver = driver {

            val indicator = element {
                text = counter.toString()
                bounds = Bounds(0, 100, 100, 200)
            }

            element {
                text = "Button"
                bounds = Bounds(0, 0, 100, 100)
                onClick = {
                    counter++
                    indicator.text = counter.toString()
                }
            }

        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEvents(
            listOf(
                Event.Tap(Point(50, 50)),
                Event.Tap(Point(50, 50)),
                Event.Tap(Point(50, 50)),
                Event.Tap(Point(50, 50)),
                Event.Tap(Point(50, 50)),
                Event.Tap(Point(50, 50)),
            )
        )
    }

    @Test
    fun `Case 054 - Enabled state`() {
        // Given
        val commands = readCommands("054_enabled")

        val driver = driver {

            element {
                text = "Button"
                bounds = Bounds(0, 0, 100, 100)
                onClick = {
                    enabled = false
                }
            }

        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEventCount(
            Event.Tap(Point(50, 50)),
            1
        )
    }

    @Test
    fun `Case 055 - Tap on element - Compare regex`() {
        // Given
        val commands = readCommands("055_compare_regex")

        val driver = driver {
            element {
                text = "(Secondary button)"
                bounds = Bounds(0, 100, 100, 200)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertHasEvent(Event.Tap(Point(50, 150)))
    }

    @Test
    fun `Case 056 - Ignore an error in Orchestra`() {
        // Given
        val commands = readCommands("056_ignore_error")

        val driver = driver {
            element {
                text = "Button"
                bounds = Bounds(0, 100, 100, 200)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(
                maestro = it,
                onCommandFailed = { _, command, _ ->
                    if (command.tapOnElement?.selector?.textRegex == "Non existent text") {
                        Orchestra.ErrorResolution.CONTINUE
                    } else {
                        Orchestra.ErrorResolution.FAIL
                    }
                },
            ).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertHasEvent(Event.Tap(Point(50, 150)))
    }

    @Test
    fun `Case 057 - Pass inner env variables to runFlow`() {
        // Given
        val commands = readCommands("057_runFlow_env") {
            mapOf(
                "OUTER_ENV" to "Outer Parameter"
            )
        }

        val driver = driver {
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertHasEvent(Event.InputText("Inner Parameter"))
        driver.assertHasEvent(Event.InputText("Outer Parameter"))
        driver.assertHasEvent(Event.InputText("Overriden Parameter"))
    }

    @Test
    fun `Case 058 - Inline env parameters`() {
        // Given
        val commands = readCommands("058_inline_env")

        val driver = driver {
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertHasEvent(Event.InputText("Inline Parameter"))
        driver.assertHasEvent(Event.InputText("Overriden Parameter"))
    }

    @Test
    fun `Case 059 - Do a directional swipe command`() {
        // given
        val commands = readCommands("059_directional_swipe_command")
        val driver = driver { }

        // when
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // then
        driver.assertHasEvent(Event.SwipeWithDirection(SwipeDirection.RIGHT, 500))
    }

    @Test
    fun `Case 060 - Pass env param to an env param`() {
        // given
        val commands = readCommands("060_pass_env_to_env") {
            mapOf(
                "PARAM" to "Value"
            )
        }
        val driver = driver { }

        // when
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // then
        driver.assertEventCount(Event.InputText("Value"), expectedCount = 3)
    }

    @Test
    fun `Case 061 - Launch app without stopping it`() {
        // given
        val commands = readCommands("061_launchApp_withoutStopping")
        val driver = driver { }
        driver.addInstalledApp("com.example.app")

        // when
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // then
        driver.assertEvents(
            listOf(
                Event.LaunchApp("com.example.app"),
            )
        )
    }

    @Test
    fun `Case 062 - Copy paste text`() {

        // Given
        val commands = readCommands("062_copy_paste_text")

        val myCopiedText = "Some text to copy"

        val driver = driver {
            element {
                id = "com.google.android.inputmethod.latin:id/myId"
                text = myCopiedText
                bounds = Bounds(0, 100, 100, 200)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertCurrentTextInput(myCopiedText)
    }

    @Test
    fun `Case 063 - Javascript injection`() {
        // given
        val commands = readCommands("063_js_injection")
        val driver = driver { }

        // when
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // then
        driver.assertEvents(
            listOf(
                Event.InputText("1"),
                Event.InputText("2"),
                Event.InputText("12"),
                Event.InputText("3.0"),
                Event.InputText("\${A} \${B} 1 2"),
            )
        )
    }

    @Test
    fun `Case 064 - Javascript files`() {
        // given
        val commands = readCommands("064_js_files")
        val driver = driver { }

        // when
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // then
        driver.assertEvents(
            listOf(
                Event.InputText("Main"),
                Event.InputText("Sub"),
                Event.InputText("Sub"),
                Event.InputText("Main"),
                Event.InputText("Sub"),
                Event.InputText("064_js_files"),
                Event.InputText("Hello, Input Parameter!"),
                Event.InputText("Hello, Evaluated Parameter!"),
                Event.InputText("064_js_files"),
            )
        )
    }

    @Test
    fun `Case 065 - When True condition`() {
        // given
        val commands = readCommands("065_when_true")
        val driver = driver { }

        // when
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // then
        driver.assertEvents(
            listOf(
                Event.InputText("True"),
                Event.InputText("String"),
                Event.InputText("Positive Int"),
                Event.InputText("Object"),
                Event.InputText("Array"),
            )
        )
    }

    @Test
    fun `Case 066 - Copy text into JS variable`() {
        // Given
        val commands = readCommands("066_copyText_jsVar")

        val myCopiedText = "Maestro"

        val driver = driver {
            element {
                id = "Field"
                text = myCopiedText
                bounds = Bounds(0, 100, 100, 200)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEvents(
            listOf(
                Event.InputText("Hello, Maestro"),
            )
        )
    }

    @Test
    fun `Case 067 - Assert True - Pass`() {
        // Given
        val commands = readCommands("067_assertTrue_pass")

        val driver = driver {
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
    }

    @Test
    fun `Case 067 - Assert True - Fail`() {
        // Given
        val commands = readCommands("067_assertTrue_fail")

        val driver = driver {
        }

        // Then
        assertThrows<MaestroException.AssertionFailure> {
            Maestro(driver).use {
                orchestra(it).runFlow(commands)
            }
        }
    }

    @Test
    fun `Case 068 - Erase all text`() {
        // given
        val commands = readCommands("068_erase_all_text")
        val driver = driver {
        }

        // when
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        driver.assertCurrentTextInput("")
    }

    @Test
    fun `Case 069 - Wait for animation to end`() {
        // given
        val commands = readCommands("069_wait_for_animation_to_end")
        val driver = driver {
        }

        // when
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEvents(
            listOf(
                Event.TakeScreenshot,
                Event.TakeScreenshot
            )
        )
    }

    @Test
    fun `Case 070 - Evaluate JS inline`() {
        // Given
        val commands = readCommands("070_evalScript")

        val driver = driver {
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEvents(
            listOf(
                Event.InputText("2"),
                Event.InputText("Result is: 2"),
            )
        )
    }

    @Test
    fun `Case 071 - Tap on relative point`() {
        // Given
        val commands = readCommands("071_tapOnRelativePoint")

        val driver = driver {
        }

        val deviceInfo = driver.deviceInfo()

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEvents(
            listOf(
                Event.Tap(Point(0, 0)),
                Event.Tap(Point(deviceInfo.widthGrid, deviceInfo.heightGrid)),
                Event.Tap(Point(deviceInfo.widthGrid / 2, deviceInfo.heightGrid / 2)),
                Event.Tap(Point(deviceInfo.widthGrid / 4, deviceInfo.heightGrid / 4)),
                Event.Tap(Point(deviceInfo.widthGrid / 4, deviceInfo.heightGrid / 4)),
            )
        )
    }

    @Test
    fun `Case 072 - Assert element visible by id`() {
        // Given
        val commands = readCommands("072_searchDepthFirst")

        val driver = driver {
            element {
                text = "Element"
                bounds = Bounds(0, 0, 100, 100)

                element {
                    text = "Element"
                    bounds = Bounds(0, 0, 50, 50)
                }
            }

            element {
                text = "Element"
                bounds = Bounds(0, 100, 100, 200)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertHasEvent(Event.Tap(Point(25, 25)))
    }

    @Test
    fun `Case 073 - Handle linebreaks`() {
        // Given
        val commands = readCommands("073_handle_linebreaks")

        val driver = driver {
            val indicator = element {
                text = "Indicator"
                bounds = Bounds(0, 100, 100, 100)
            }

            element {
                text = "Hello\nWorld"
                bounds = Bounds(0, 0, 100, 100)

                onClick = {
                    indicator.text += "!"
                }
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEventCount(Event.Tap(Point(50, 50)), expectedCount = 2)
    }

    @Test
    fun `Case 074 - Directional swipe on elements`() {
        // given
        val commands = readCommands("074_directional_swipe_element")
        val elementBounds = Bounds(0, 100, 100, 100)
        val driver = driver {
            element {
                text = "swiping element"
                bounds = elementBounds
            }
        }

        // when
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // then
        driver.assertHasEvent(
            Event.SwipeElementWithDirection(
                Point(50, 100),
                SwipeDirection.RIGHT,
                400
            )
        )
    }

    @Test
    fun `Case 075 - Repeat while`() {
        // Given
        val commands = readCommands("075_repeat_while")
        val driver = driver {
            var counter = 0

            val counterView = element {
                text = "Value 0"
                bounds = Bounds(0, 100, 100, 100)
            }

            element {
                text = "Button"
                bounds = Bounds(0, 0, 100, 100)
                onClick = {
                    counter++
                    counterView.text = "Value $counter"
                }
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failures

        driver.assertEventCount(
            Event.Tap(Point(50, 50)),
            expectedCount = 3
        )
    }

    @Test
    fun `Case 076 - Optional assertion`() {
        // Given
        val commands = readCommands("076_optional_assertion")

        val driver = driver {
            // No elements
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
    }

    @Test
    fun `Case 077 - Env special characters`() {
        // Given
        val commands = readCommands("077_env_special_characters") {
            mapOf(
                "OUTER" to "!@#\$&*()_+{}|:\"<>?[]\\\\;',./"
            )
        }

        val driver = driver {
            // No elements
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        driver.assertEvents(
            listOf(
                Event.InputText("!@#\$&*()_+{}|:\"<>?[]\\;',./"),
                Event.InputText("!@#\$&*()_+{}|:\"<>?[]\\;',./"),
            )
        )
    }

    @Test
    fun `Case 078 - Swipe with relative coordinates`() {
        // given
        val commands = readCommands("078_swipe_relative")
        val driver = driver {
        }
        val deviceInfo = driver.deviceInfo()

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        val expectedStart = Point(deviceInfo.widthGrid / 2, deviceInfo.heightGrid * 30 / 100)
        val expectedEnd = Point(deviceInfo.widthGrid / 2, deviceInfo.heightGrid * 60 / 100)
        driver.assertHasEvent(
            Event.Swipe(start = expectedStart, End = expectedEnd, durationMs = 3000)
        )
    }

    @Test
    fun `Case 079 - Scroll until view is visible - no view`() {
        // Given
        val commands = readCommands("079_scroll_until_visible")

        // No view
        val driver = driver {
            // No elements
        }

        // Then fail
        assertThrows<MaestroException.ElementNotFound> {
            Maestro(driver).use {
                assertThat(orchestra(it).runFlow(commands))
            }
        }
    }

    @Test
    fun `Case 079-2 - Scroll until view is visible - with view`() {
        // Given
        val commands = readCommands("079_scroll_until_visible")
        val info = driver { }.deviceInfo()

        val elementBounds = Bounds(0, 0 + info.heightGrid, 100, 100 + info.heightGrid)
        val driver = driver {
            element {
                text = "Test"
                bounds = elementBounds
            }
        }

        // When
        Maestro(driver).use {
            assertThat(orchestra(it).runFlow(commands)).isTrue()
        }

        // Then
        driver.assertEvents(
            listOf(
                Event.SwipeElementWithDirection(Point(270, 480), SwipeDirection.UP, 1),
            )
        )
    }

    @Test
    fun `Case 080 - Hierarchy pruning assert visible`() {
        // Given
        val commands = readCommands("080_hierarchy_pruning_assert_visible")

        val info = driver {}.deviceInfo()

        val driver = driver {
            element {
                id = "root"
                bounds = Bounds(0, 0, 500, 500)

                element {
                    id = "visible_1"
                    bounds = Bounds(0, 0, 100, 100)
                }

                element {
                    id = "visible_2"
                    bounds = Bounds(info.widthGrid - 50, 0, info.widthGrid + 100, 100)
                }

                element {
                    id = "visible_3"
                    bounds = Bounds(0, info.heightGrid - 50, 100, info.heightGrid + 100)
                }

                element {
                    id = "visible_4"
                    bounds = Bounds(-100, -100, info.widthGrid + 200, info.heightGrid + 200)
                }
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertNoInteraction()
    }

    @Test
    fun `Case 081 - Hierarchy pruning assert not visible`() {
        // Given
        val commands = readCommands("081_hierarchy_pruning_assert_not_visible")

        val info = driver {}.deviceInfo()

        val driver = driver {
            element {
                id = "root"
                bounds = Bounds(0, 0, 500, 500)

                element {
                    id = "not_visible_1"
                    bounds = Bounds(-100, -100, 0, 0)
                }

                element {
                    id = "not_visible_2"
                    bounds = Bounds(info.widthGrid, 0, info.widthGrid + 100, 100)
                }

                element {
                    id = "not_visible_3"
                    bounds = Bounds(0, info.heightGrid, 100, info.heightGrid + 100)
                }

                element {
                    id = "not_visible_4"
                    bounds = Bounds(0, info.heightGrid - 10, 100, info.heightGrid + 100)
                }
            }
        }

        // When & Then
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertNoInteraction()
    }

    @Test
    fun `Case 082 - Repeat while true`() {
        // Given
        val commands = readCommands("082_repeat_while_true")
        val driver = driver {
            var counter = 0

            val counterView = element {
                text = "Value 0"
                bounds = Bounds(0, 100, 100, 100)
            }

            element {
                text = "Button"
                bounds = Bounds(0, 0, 100, 100)
                onClick = {
                    counter++
                    counterView.text = "Value $counter"
                }
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failures
        driver.assertEventCount(
            Event.Tap(Point(50, 50)),
            expectedCount = 3
        )
    }

    @Test
    fun `Case 083 - Assert on properties`() {
        // Given
        val commands = readCommands("083_assert_properties")

        val driver = driver {
            val field = element {
                text = "Field"
                checked = true
                selected = true
                focused = true
                bounds = Bounds.ofSize(width = 100, height = 100)
            }

            element {
                text = "Flip"
                bounds = Bounds.ofSize(width = 100, height = 100)
                    .translate(y = 100)
                onClick = {
                    field.checked = field.checked?.not()
                    field.selected = field.selected?.not()
                    field.enabled = field.enabled?.not()
                    field.focused = field.focused?.not()
                }
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertHasEvent(Event.Tap(Point(50, 150)))
    }

    @Test
    fun `Case 084 - Open Browser`() {
        // given
        val commands = readCommands("084_open_browser")

        val driver = driver {}

        // when
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // then
        driver.assertEvents(
            listOf(
                Event.OpenBrowser("https://example.com")
            )
        )
    }

    @Test
    fun `Case 085 - Open link with auto verify`() {
        // Given
        val commands = readCommands("085_open_link_auto_verify")

        val driver = driver {}

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEvents(
            listOf(
                Event.OpenLink("https://example.com", autoLink = true)
            )
        )
    }

    @Test
    fun `Case 086 - launchApp sets all permissions to allow`() {
        // Given
        val commands = readCommands("086_launchApp_sets_all_permissions_to_allow")
        val driver = driver {}
        driver.addInstalledApp("com.example.app")

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        driver.assertEvents(
            listOf(
                Event.SetPermissions("com.example.app", mapOf("all" to "allow")),
                Event.LaunchApp("com.example.app"),
            )
        )
    }

    @Test
    fun `Case 087 - launchApp with all permissions to deny`() {
        // Given
        val commands = readCommands("087_launchApp_with_all_permissions_to_deny")
        val driver = driver {}
        driver.addInstalledApp("com.example.app")

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        driver.assertEvents(
            listOf(
                Event.SetPermissions("com.example.app", mapOf("all" to "deny")),
                Event.LaunchApp("com.example.app"),
            )
        )
    }

    @Test
    fun `Case 088 - launchApp with all permissions to deny and notification to allow`() {
        // Given
        val commands = readCommands("088_launchApp_with_all_permissions_to_deny_and_notification_to_allow")
        val driver = driver {}
        driver.addInstalledApp("com.example.app")

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        driver.assertEvents(
            listOf(
                Event.SetPermissions("com.example.app", mapOf("all" to "deny", "notifications" to "allow")),
                Event.LaunchApp("com.example.app"),
            )
        )
    }

    @Test
    fun `Case 089 - launchApp with SMS permissions`() {
        // Given
        val commands = readCommands("089_launchApp_with_sms_permission_group_to_allow")
        val driver = driver {}
        driver.addInstalledApp("com.example.app")

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        driver.assertEvents(
            listOf(
                Event.SetPermissions("com.example.app", mapOf("sms" to "allow")),
                Event.LaunchApp("com.example.app"),
            )
        )
    }

    @Test
    fun `Case 090 - Travel`() {
        // Given
        val commands = readCommands("090_travel")
        val driver = driver {}
        driver.addInstalledApp("com.example.app")

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        driver.assertEvents(
            listOf(
                Event.SetLocation(0.0, 0.0),
                Event.SetLocation(0.1, 0.0),
                Event.SetLocation(0.1, 0.1),
                Event.SetLocation(0.0, 0.1),
            )
        )
    }

    @Test
    fun `Case 091 - Assert visible by index`() {
        // Given
        val commands = readCommands("091_assert_visible_by_index")
        val driver = driver {

            element {
                text = "Item"
                bounds = Bounds.ofSize(100, 100)
            }

            element {
                text = "Item"
                bounds = Bounds.ofSize(100, 100)
                    .translate(y = 100)
            }

        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No failures
    }

    @Test
    fun `Case 092 - Log messages`() {
        // Given
        val commands = readCommands("092_log_messages")
        val driver = driver {
        }

        val receivedLogs = mutableListOf<String>()

        // When
        Maestro(driver).use {
            orchestra(
                it,
                onCommandMetadataUpdate = { _, metadata ->
                    receivedLogs += metadata.logMessages
                }
            ).runFlow(commands)
        }

        // Then
        assertThat(receivedLogs).containsExactly(
            "Log from evalScript",
            "Log from runScript",
        ).inOrder()
    }

    @Test
    fun `Case 093 - JS default values`() {
        // Given
        val commands = readCommands("093_js_default_value")
        val driver = driver {
        }
        driver.addInstalledApp("com.example.default")

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        driver.assertHasEvent(Event.LaunchApp("com.example.default"))
    }

    @Test
    fun `Case 094 - Subflow with inlined commands`() {
        // Given
        val commands = readCommands("094_runFlow_inline")
        val driver = driver {
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        driver.assertHasEvent(Event.InputText("Inner Parameter"))
    }

    @Test
    fun `Case 095 - Launch arguments`() {
        // Given
        val commands = readCommands("095_launch_arguments")
        val driver = driver {
        }
        driver.addInstalledApp("com.example.app")

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        driver.assertHasEvent(
            Event.LaunchApp(
                appId = "com.example.app",
                launchArguments = mapOf(
                    "argumentA" to true,
                    "argumentB" to 4,
                    "argumentC" to 4.0,
                    "argumentD" to "Hello String Value true"
                )
            )
        )
    }

    @Test
    fun `Case 096 - platform condition`() {
        // Given
        val commands = readCommands("096_platform_condition")
        val driver = driver {
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        driver.assertHasEvent(Event.InputText("Hello iOS"))
        driver.assertHasEvent(Event.InputText("Hello ios"))
        driver.assertNoEvent(Event.InputText("Hello Android"))
    }

    @Test
    fun `Case 097 - Contains descendants`() {
        // Given
        val commands = readCommands("097_contains_descendants")

        val driver = driver {
            element {
                id = "id1"
                bounds = Bounds(0, 0, 200, 200)

                element {
                    bounds = Bounds(0, 0, 200, 200)
                    element {
                        id = "id2"
                        bounds = Bounds(0, 0, 200, 200)
                        element {
                            text = "Child 1"
                            bounds = Bounds(0, 0, 100, 50)
                        }
                    }
                    element {
                        text = "Child 2"
                        bounds = Bounds(0, 0, 100, 100)
                        enabled = false
                    }
                }
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failures
        driver.assertNoInteraction()
    }

    @Test
    fun `Case 098 - Execute Javascript conditionally`() {
        // Given
        val commands = readCommands("098_runscript_conditionals")

        val driver = driver {
            element {
                text = "Click me"
                bounds = Bounds(0, 0, 100, 100)
                onClick = { element ->
                    element.text = "Clicked"
                }
            }
        }

        val receivedLogs = mutableListOf<String>()

        // When
        Maestro(driver).use {
            orchestra(
                it,
                onCommandMetadataUpdate = { _, metadata ->
                    receivedLogs += metadata.logMessages
                }
            ).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEventCount(Event.Tap(Point(50, 50)), 1)
        // Then
        assertThat(receivedLogs).containsExactly(
            "Log from runScript",
        ).inOrder()
    }

    @Test
    fun `Case 099 - Screen recording`() {
        // Given
        val commands = readCommands("099_screen_recording")

        val driver = driver {
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEvents(
            listOf(
                Event.StartRecording,
                Event.StopRecording,
            )
        )
        assert(File("099_screen_recording.mp4").exists())
    }

    @Test
    fun `Case 100 - tapOn multiple times`() {
        // Given
        val commands = readCommands("100_tapOn_multiple_times")

        val driver = driver {
            element {
                text = "Button"
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }


        // Then
        // No test failure
        driver.assertEventCount(Event.Tap(Point(50, 50)), 3)
    }

    @Test
    fun `Case 101 - doubleTapOn`() {
        // Given
        val commands = readCommands("101_doubleTapOn")

        val driver = driver {
            element {
                text = "Button"
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }


        // Then
        // No test failure
        driver.assertEventCount(Event.Tap(Point(50, 50)), 2)
    }

    @Test
    fun `Case 102 - GraalJs config`() {
        // given
        val commands = readCommands("102_graaljs")
        val driver = driver { }

        // when
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // then
        driver.assertEvents(
            listOf(
                Event.InputText("foo"),
                Event.InputText("bar"),
            )
        )
    }

    @Test
    fun `Case 103 - execute onFlowStart and onFlowComplete hooks`() {
        // given
        val commands = readCommands("103_on_flow_start_complete_hooks")
        val driver = driver {
        }
        val receivedLogs = mutableListOf<String>()

        // when
        Maestro(driver).use {
            orchestra(
                it,
                onCommandMetadataUpdate = { _, metadata ->
                    receivedLogs += metadata.logMessages
                }
            ).runFlow(commands)
        }

        // Then
        assertThat(receivedLogs).containsExactly(
            "setup",
            "teardown",
        ).inOrder()
        driver.assertEvents(
            listOf(
                Event.InputText("test1"),
                Event.Tap(Point(100, 200)),
                Event.InputText("test2"),
            )
        )
    }

    @Test
    fun `Case 104 - execute onFlowStart and onFlowComplete hooks when flow failed`() {
        // Given
        val commands = readCommands("104_on_flow_start_complete_hooks_flow_failed")

        val driver = driver {
            element {
                id = "another_id"
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        // When & Then
        assertThrows<MaestroException.AssertionFailure> {
            Maestro(driver).use {
                orchestra(it).runFlow(commands)
            }
        }
        driver.assertEvents(
            listOf(
                Event.InputText("test1"),
                Event.InputText("test2"),
            )
        )
    }

    @Test
    fun `Case 105 - execute onFlowStart and onFlowComplete when js output is set`() {
        // Given
        val commands = readCommands("105_on_flow_start_complete_when_js_output_set")

        val driver = driver {
        }
        val receivedLogs = mutableListOf<String>()

        // when
        Maestro(driver).use {
            orchestra(
                it,
                onCommandMetadataUpdate = { _, metadata ->
                    receivedLogs += metadata.logMessages
                }
            ).runFlow(commands)
        }

        // Then
        assertThat(receivedLogs).containsExactly(
            "setup",
            "teardown",
        ).inOrder()
    }

    @Test
    fun `Case 106 - execute onFlowStart and onFlowComplete when js output is set with subflows`() {
        // Given
        val commands = readCommands("106_on_flow_start_complete_when_js_output_set_subflows")

        val driver = driver {
        }
        val receivedLogs = mutableListOf<String>()

        // when
        Maestro(driver).use {
            orchestra(
                it,
                onCommandMetadataUpdate = { _, metadata ->
                    receivedLogs += metadata.logMessages
                }
            ).runFlow(commands)
        }

        // Then
        assertThat(receivedLogs).containsExactly(
            "subflow",
            "setup subflow",
            "teardown subflow",
        ).inOrder()
    }

    @Test
    fun `Case 107 - execute defineVariablesCommand before onFlowStart and onFlowComplete are executed`() {
        // Given
        val commands = readCommands("107_define_variables_command_before_hooks")

        val driver = driver {
        }
        driver.addInstalledApp("com.example.app")
        val receivedLogs = mutableListOf<String>()

        // when
        Maestro(driver).use {
            orchestra(
                it,
                onCommandMetadataUpdate = { _, metadata ->
                    receivedLogs += metadata.logMessages
                }
            ).runFlow(commands)
        }

        // Then
        assertThat(receivedLogs).containsExactly(
            "com.example.app",
        ).inOrder()
        driver.assertEvents(
            listOf(
                Event.LaunchApp("com.example.app")
            )
        )
    }

    @Test
    fun `Case 108 - fail the flow and skip commands in case of onStart hook failure`() {
        // Given
        val commands = readCommands("108_failed_start_hook")
        val driver = driver {
        }
        val receivedLogs = mutableListOf<String>()

        // When & Then
        assertThrows<MaestroException.AssertionFailure> {
            val result = Maestro(driver).use {
                orchestra(
                    it,
                    onCommandMetadataUpdate = { _, metadata ->
                        receivedLogs += metadata.logMessages
                    }
                ).runFlow(commands)
            }

            assertThat(result).isFalse()
        }
        assertThat(receivedLogs).containsExactly(
            "on start",
            "on complete",
        ).inOrder()
    }

    @Test
    fun `Case 109 - fail the flow and execute commands in case of onComplete hook failure`() {
        // Given
        val commands = readCommands("109_failed_complete_hook")
        val driver = driver {
        }
        val receivedLogs = mutableListOf<String>()

        // When & Then
        assertThrows<MaestroException.AssertionFailure> {
            val result = Maestro(driver).use {
                orchestra(
                    it,
                    onCommandMetadataUpdate = { _, metadata ->
                        receivedLogs += metadata.logMessages
                    }
                ).runFlow(commands)
            }

            assertThat(result).isFalse()
        }
        assertThat(receivedLogs).containsExactly(
            "on start",
            "main flow",
            "on complete",
        ).inOrder()
    }

    @Test
    fun `Case 110 - addMedia command emits add media event with correct path`() {
        // given
        val commands = readCommands("110_add_media_device")
        val driver = driver {}

        // when
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // then
        driver.assertEvents(listOf(Event.AddMedia))
    }

    @Test
    fun `Case 111 - addMedia command allows adding multiple media`() {
        // given
        val commands = readCommands("111_add_multiple_media")
        val driver = driver { }

        // when
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // then
        driver.assertEvents(listOf(Event.AddMedia, Event.AddMedia, Event.AddMedia))
    }

    @Test
    fun `Case 112 - Scroll until view is visible - with element center`() {
        // Given
        val commands = readCommands("112_scroll_until_visible_center")
        val info = driver { }.deviceInfo()

        val elementBounds = Bounds(0, 0 + info.heightGrid, 100, 100 + info.heightGrid)
        val driver = driver {
            element {
                text = "Test"
                bounds = elementBounds
            }
        }

        // When
        Maestro(driver).use {
            assertThat(orchestra(it).runFlow(commands)).isTrue()
        }

        // Then
        driver.assertEvents(
            listOf(
                Event.SwipeElementWithDirection(Point(270, 480), SwipeDirection.UP, 1),
            )
        )
    }

    @Test
    fun `Case 113 - Tap on element - with app settle timeout`() {
        // Given
        val commands = readCommands("113_tap_on_element_settle_timeout")

        val driver = driver {
            element {
                mutatingText = {
                    "The time is ${System.nanoTime()}"
                }
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        // When
        var elapsedTime: Long
        Maestro(driver).use { maestro ->
            elapsedTime = measureTimeMillis {
                orchestra(maestro).runFlow(commands)
            }
        }

        // Then
        // No test failure
        assertThat(elapsedTime).isAtMost(1000L)
        driver.assertEventCount(Event.Tap(Point(50, 50)), expectedCount = 1)
    }

    @Test
    fun `Case 114 - child of selector`() {
        // Given
        val commands = readCommands("114_child_of_selector")

        val driver = driver {
            element {
                id = "id1"
                bounds = Bounds(0, 0, 200, 600)

                element {
                    bounds = Bounds(0, 0, 200, 200)
                    text = "parent_id_1"
                    element {
                        text = "child_id"
                        bounds = Bounds(0, 0, 100, 200)
                    }
                }
                element {
                    bounds = Bounds(0, 200, 200, 400)
                    text = "parent_id_2"
                    element {
                        text = "child_id"
                        bounds = Bounds(0, 200, 100, 400)
                    }
                }
                element {
                    bounds = Bounds(0, 400, 200, 600)
                    text = "parent_id_3"
                    element {
                        text = "child_id_1"
                        bounds = Bounds(0, 400, 100, 600)
                    }
                }
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failures
        driver.assertNoInteraction()

    }

    @Test
    fun `Case 115 - airplane mode`() {
        val commands = readCommands("115_airplane_mode")
        val driver = driver { }

        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }
    }

    @Test
    fun `Case 116 - Kill app`() {
        // Given
        val commands = readCommands("116_kill_app")

        val driver = driver {
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertHasEvent(Event.KillApp("com.example.app"))
        driver.assertHasEvent(Event.KillApp("another.app"))
    }

    @Test
    fun `Case 117 - Scroll until view is visible - with speed and timeout evaluate`() {
        // Given
        val commands = readCommands("117_scroll_until_visible_speed")
        val expectedDuration = "601"
        val expectedTimeout = "20000"
        val info = driver { }.deviceInfo()

        val elementBounds = Bounds(0, 0 + info.heightGrid, 100, 100 + info.heightGrid)
        val driver = driver {
            element {
                id = "maestro"
                bounds = elementBounds
            }
        }

        // When
        var scrollDuration = "0"
        var timeout = "0"
        Maestro(driver).use {
            orchestra(it, onCommandMetadataUpdate = { _, metaData ->
                scrollDuration = metaData.evaluatedCommand?.scrollUntilVisible?.scrollDuration.toString()
                timeout = metaData.evaluatedCommand?.scrollUntilVisible?.timeout.toString()
            }).runFlow(commands)
        }

        // Then
        assertThat(scrollDuration).isEqualTo(expectedDuration)
        assertThat(timeout).isEqualTo(expectedTimeout)
        driver.assertEvents(
            listOf(
                Event.SwipeElementWithDirection(Point(270, 480), SwipeDirection.UP, expectedDuration.toLong()),
            )
        )
    }

    @Test
    fun `Case 118 - Scroll until view is visible - no negative values allowed`() {
        // Given
        val commands = readCommands("118_scroll_until_visible_negative")
        val expectedDuration = "40"
        val expectedTimeout = "20000"
        val info = driver { }.deviceInfo()

        val elementBounds = Bounds(0, 0 + info.heightGrid, 100, 100 + info.heightGrid)
        val driver = driver {
            element {
                id = "maestro"
                bounds = elementBounds
            }
        }

        // When
        var scrollDuration = "0"
        var timeout = "0"
        Maestro(driver).use {
            orchestra(it, onCommandMetadataUpdate = { _, metaData ->
                scrollDuration = metaData.evaluatedCommand?.scrollUntilVisible?.scrollDuration.toString()
                timeout = metaData.evaluatedCommand?.scrollUntilVisible?.timeout.toString()
            }).runFlow(commands)
        }

        // Then
        assertThat(scrollDuration).isEqualTo(expectedDuration)
        assertThat(timeout).isEqualTo(expectedTimeout)
        driver.assertEvents(
            listOf(
                Event.SwipeElementWithDirection(Point(270, 480), SwipeDirection.UP, expectedDuration.toLong()),
            )
        )
    }

    @Test
    fun `Case 119 - Retry set of commands with n attempts`() {
        // Given
        val commands = readCommands("119_retry_commands")

        var counter = 0
        val driver = driver {
            val indicator = element {
                text = counter.toString()
                bounds = Bounds(0, 100, 100, 200)
            }

            element {
                text = "Button"
                bounds = Bounds(0, 0, 100, 100)
                onClick = {
                    counter++
                    if (counter == 1) {
                        throw RuntimeException("Exception for the first time")
                    }
                    indicator.text = counter.toString()
                }
            }

        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEvents(
            listOf(
                Event.Scroll,
                Event.TakeScreenshot,
                /**----after retry----**/
                Event.Scroll,
                Event.TakeScreenshot,
                Event.Tap(Point(50, 50)),
                Event.Scroll,
            )
        )
    }

    @Test
    fun `Case 120 - Tap on element - Retry if no UI change opt-in`() {
        // Given
        val commands = readCommands("120_tap_on_element_retryTapIfNoChange")

        val driver = driver {
            element {
                text = "Primary button"
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        // When
        Maestro(driver).use {
            orchestra(it).runFlow(commands)
        }

        // Then
        // No test failure
        driver.assertEventCount(Event.Tap(Point(50, 50)), expectedCount = 2)
    }

    private fun orchestra(
        maestro: Maestro,
    ) = Orchestra(
        maestro,
        lookupTimeoutMs = 0L,
        optionalLookupTimeoutMs = 0L,
    )

    private fun orchestra(
        maestro: Maestro,
        onCommandMetadataUpdate: (MaestroCommand, Orchestra.CommandMetadata) -> Unit = { _, _ -> },
    ) = Orchestra(
        maestro,
        lookupTimeoutMs = 0L,
        optionalLookupTimeoutMs = 0L,
        onCommandMetadataUpdate = onCommandMetadataUpdate,
    )

    private fun orchestra(
        maestro: Maestro,
        onCommandFailed: (Int, MaestroCommand, Throwable) -> Orchestra.ErrorResolution,
    ) = Orchestra(
        maestro,
        lookupTimeoutMs = 0L,
        optionalLookupTimeoutMs = 0L,
        onCommandFailed = onCommandFailed,
    )

    private fun driver(builder: FakeLayoutElement.() -> Unit): FakeDriver {
        val driver = FakeDriver()
        driver.setLayout(FakeLayoutElement().apply { builder() })
        driver.open()
        return driver
    }

    private fun readCommands(caseName: String, withEnv: () -> Map<String, String> = { emptyMap() }): List<MaestroCommand> {
        val resource = javaClass.classLoader.getResource("$caseName.yaml")
            ?: throw IllegalArgumentException("File $caseName.yaml not found")
        val flowPath = Paths.get(resource.toURI())
        return YamlCommandReader.readCommands(flowPath)
            .withEnv(withEnv().withDefaultEnvVars(flowPath.toFile()))
    }

}
