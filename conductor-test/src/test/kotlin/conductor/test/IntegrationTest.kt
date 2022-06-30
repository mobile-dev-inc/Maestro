package conductor.test

import conductor.Conductor
import conductor.Point
import conductor.orchestra.ConductorCommand
import conductor.orchestra.Orchestra
import conductor.orchestra.yaml.YamlCommandReader
import conductor.test.drivers.FakeDriver
import conductor.test.drivers.FakeDriver.Event
import conductor.test.drivers.FakeLayoutElement
import conductor.test.drivers.FakeLayoutElement.Bounds
import okio.Source
import okio.source
import org.junit.Test

class IntegrationTest {

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
        Conductor(driver).use {
            orchestra(it).executeCommands(commands)
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
        Conductor(driver).use {
            orchestra(it).executeCommands(commands)
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
        Conductor(driver).use {
            orchestra(it).executeCommands(commands)
        }

        // Then
        // No test failure
        driver.assertNoInteraction()
    }

    @Test(expected = Conductor.NotFoundException::class)
    fun `Case 004 - Assert no visible element with id`() {
        // Given
        val commands = readCommands("004_assert_no_visible_element_with_id")

        val driver = driver {
            element {
                id = "another_id"
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        // When
        Conductor(driver).use {
            orchestra(it).executeCommands(commands)
        }

        // Then
        // Test failure
    }

    @Test(expected = Conductor.NotFoundException::class)
    fun `Case 005 - Assert no visible element with text`() {
        // Given
        val commands = readCommands("005_assert_no_visible_element_with_text")

        val driver = driver {
            element {
                text = "Some other text"
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        // When
        Conductor(driver).use {
            orchestra(it).executeCommands(commands)
        }

        // Then
        // Test failure
    }

    @Test(expected = Conductor.NotFoundException::class)
    fun `Case 006 - Assert no visible element with size`() {
        // Given
        val commands = readCommands("005_assert_no_visible_element_with_text")

        val driver = driver {
            element {
                text = "Some other text"
                bounds = Bounds(0, 0, 101, 101)
            }
        }

        // When
        Conductor(driver).use {
            orchestra(it).executeCommands(commands)
        }

        // Then
        // Test failure
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
        Conductor(driver).use {
            orchestra(it).executeCommands(commands)
        }

        // Then
        // No test failure
        driver.assertNoInteraction()
    }

    @Test
    fun `Case 008 - Tap on element`() {
        // Given
        val commands = readCommands("008_tap_on_element")

        val driver = driver {
            element {
                text = "Primary button"
                bounds = Bounds(0, 0, 100, 100)
            }
        }

        // When
        Conductor(driver).use {
            orchestra(it).executeCommands(commands)
        }

        // Then
        // No test failure
        driver.assertHasEvent(Event.Tap(Point(50, 50)))
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
        Conductor(driver).use {
            orchestra(it).executeCommands(commands)
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
        Conductor(driver).use {
            orchestra(it).executeCommands(commands)
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
        Conductor(driver).use {
            orchestra(it).executeCommands(commands)
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
        Conductor(driver).use {
            orchestra(it).executeCommands(commands)
        }

        // Then
        // No test failure
        driver.assertHasEvent(Event.InputText("Hello World"))
    }

    private fun orchestra(it: Conductor) = Orchestra(it, lookupTimeoutMs = 0L, optionalLookupTimeoutMs = 0L)

    private fun driver(builder: FakeLayoutElement.() -> Unit): FakeDriver {
        val driver = FakeDriver()
        driver.setLayout(FakeLayoutElement().apply { builder() })
        driver.open()
        return driver
    }

    private fun readCommands(caseName: String): List<ConductorCommand> {
        return YamlCommandReader().readCommands(openFile("$caseName.yaml"))
    }

    private fun openFile(path: String): Source {
        return javaClass.classLoader.getResourceAsStream(path)
            ?.source()
            ?: throw IllegalArgumentException("File $path not found")
    }

}