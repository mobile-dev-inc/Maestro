package maestro.test

import com.google.common.truth.Truth.assertThat
import maestro.KeyCode
import maestro.Maestro
import maestro.MaestroException
import maestro.MaestroTimer
import maestro.Point
import maestro.SwipeDirection
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.MaestroInitFlow
import maestro.orchestra.Orchestra
import maestro.orchestra.error.UnicodeNotSupportedError
import maestro.orchestra.yaml.YamlCommandReader
import maestro.test.drivers.FakeDriver
import maestro.test.drivers.FakeDriver.Event
import maestro.test.drivers.FakeLayoutElement
import maestro.test.drivers.FakeLayoutElement.Bounds
import maestro.test.drivers.FakeTimer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.awt.Color
import java.io.File
import java.nio.file.Paths

class IntegrationTest {

    val fakeTimer = FakeTimer()

    @BeforeEach
    fun setUp() {
        MaestroTimer.setTimerFunc(fakeTimer.timer())
    }

    @AfterEach
    internal fun tearDown() {
        File("screenshot.png").delete()
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
        assertThrows<MaestroException.ElementNotFound> {
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
        assertThrows<MaestroException.ElementNotFound> {
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
        assertThrows<MaestroException.ElementNotFound> {
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
    fun `Case 008 - Tap on element - Retry if no UI change`() {
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
        driver.assertEventCount(Event.Tap(Point(50, 50)), expectedCount = 2)
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
                    ApplyConfigurationCommand(
                        config = MaestroConfig(
                            appId = "com.example.app",
                            initFlow = MaestroInitFlow(
                                appId = "com.example.app",
                                commands = listOf(
                                    MaestroCommand(
                                        LaunchAppCommand(
                                            appId = "com.example.app"
                                        )
                                    )
                                ),
                            )
                        )
                    ),
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
    fun `Case 023 - runFlow with initFlow`() {
        // Given
        val commands = readCommands("024_init_flow_init_state")
        val initFlow = YamlCommandReader.getConfig(commands)!!.initFlow!!

        val driver = driver {
            element {
                text = "Hello"
                bounds = Bounds(0, 0, 100, 100)
            }
        }
        driver.addInstalledApp("com.example.app")

        val otherDriver = driver {
            element {
                text = "Hello"
                bounds = Bounds(0, 0, 100, 100)
            }
        }
        otherDriver.addInstalledApp("com.example.app")

        // When
        val state = Maestro(driver).use {
            orchestra(it).runInitFlow(initFlow)
        }!!

        Maestro(otherDriver).use {
            orchestra(it).runFlow(commands, state)
        }

        // Then
        // No test failure
        otherDriver.assertPushedAppState(
            listOf(
                Event.LaunchApp("com.example.app"),
            )
        )
        otherDriver.assertHasEvent(Event.Tap(Point(50, 50)))
    }

    @Test
    fun `Case 024 - runFlow with initState`() {
        // Given
        val commands = readCommands("023_init_flow")

        val driver = driver {
            element {
                text = "Hello"
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
        driver.assertPushedAppState(
            listOf(
                Event.LaunchApp("com.example.app"),
            )
        )
        driver.assertHasEvent(Event.Tap(Point(50, 50)))
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
        val commands = readCommands("028_env")
            .map {
                it.injectEnv(
                    envParameters = mapOf(
                        "APP_ID" to "com.example.app",
                        "BUTTON_ID" to "button_id",
                        "BUTTON_TEXT" to "button_text",
                        "PASSWORD" to "testPassword",
                        "NON_EXISTENT_TEXT" to "nonExistentText",
                        "NON_EXISTENT_ID" to "nonExistentId",
                        "URL" to "secretUrl",
                    )
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
                Event.OpenLink("https://example.com/secretUrl")
            )
        )
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
    fun `Case 036 - Erase text`() {
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
        val commands = readCommands("049_run_flow_conditionally")
            .map {
                it.injectEnv(
                    mapOf(
                        "NOT_CLICKED" to "Not Clicked"
                    )
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
        val commands = readCommands("057_runFlow_env")
            .map {
                it.injectEnv(
                    mapOf(
                        "OUTER_ENV" to "Outer Parameter"
                    )
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
        val commands = readCommands("060_pass_env_to_env")
            .map {
                it.injectEnv(
                    mapOf(
                        "PARAM" to "Value"
                    )
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

    private fun orchestra(maestro: Maestro) = Orchestra(
        maestro,
        lookupTimeoutMs = 0L,
        optionalLookupTimeoutMs = 0L
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

    private fun readCommands(caseName: String): List<MaestroCommand> {
        val resource = javaClass.classLoader.getResource("$caseName.yaml")
            ?: throw IllegalArgumentException("File $caseName.yaml not found")
        return YamlCommandReader.readCommands(Paths.get(resource.toURI()))
    }
}