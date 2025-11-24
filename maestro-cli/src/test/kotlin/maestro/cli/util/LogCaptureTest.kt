package maestro.cli.util

import maestro.LogLevel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class LogCaptureTest {

    @Test
    fun `parseLogLine should parse valid logcat line`() {
        val logCapture = LogCapture()
        val parseMethod = logCapture.javaClass.getDeclaredMethod("parseLogLine", String::class.java)
        parseMethod.isAccessible = true

        val line = "11-24 10:35:09.552  662  4254 I ArtService: Dexopt result: packageName = org.wikipedia"
        val entry = parseMethod.invoke(logCapture, line) as maestro.LogEntry?

        assertNotNull(entry)
        assertEquals("11-24 10:35:09.552", entry.timestamp)
        assertEquals(662, entry.pid)
        assertEquals(4254, entry.tid)
        assertEquals(LogLevel.INFO, entry.level)
        assertEquals("ArtService", entry.tag)
        assertEquals("Dexopt result: packageName = org.wikipedia", entry.message)
    }

    @Test
    fun `parseLogLine should handle different log levels`() {
        val logCapture = LogCapture()
        val parseMethod = logCapture.javaClass.getDeclaredMethod("parseLogLine", String::class.java)
        parseMethod.isAccessible = true

        val testCases = mapOf(
            "11-24 10:35:09.552  662  4254 V MyTag: verbose message" to LogLevel.VERBOSE,
            "11-24 10:35:09.552  662  4254 D MyTag: debug message" to LogLevel.DEBUG,
            "11-24 10:35:09.552  662  4254 I MyTag: info message" to LogLevel.INFO,
            "11-24 10:35:09.552  662  4254 W MyTag: warn message" to LogLevel.WARN,
            "11-24 10:35:09.552  662  4254 E MyTag: error message" to LogLevel.ERROR,
            "11-24 10:35:09.552  662  4254 A MyTag: assert message" to LogLevel.ASSERT
        )

        testCases.forEach { (line, expectedLevel) ->
            val entry = parseMethod.invoke(logCapture, line) as maestro.LogEntry?
            assertNotNull(entry, "Failed to parse: $line")
            assertEquals(expectedLevel, entry.level)
        }
    }

    @Test
    fun `parseLogLine should skip invalid lines`() {
        val logCapture = LogCapture()
        val parseMethod = logCapture.javaClass.getDeclaredMethod("parseLogLine", String::class.java)
        parseMethod.isAccessible = true

        val invalidLines = listOf(
            "",
            "--------- beginning of main",
            "invalid format",
            "11-24 10:35:09.552 not enough parts"
        )

        invalidLines.forEach { line ->
            val entry = parseMethod.invoke(logCapture, line) as maestro.LogEntry?
            assertNull(entry, "Should not parse invalid line: $line")
        }
    }

    @Test
    fun `parseLogLine should handle messages with colons`() {
        val logCapture = LogCapture()
        val parseMethod = logCapture.javaClass.getDeclaredMethod("parseLogLine", String::class.java)
        parseMethod.isAccessible = true

        val line = "11-24 10:35:09.552  662  4254 I MyTag: Message with: multiple: colons"
        val entry = parseMethod.invoke(logCapture, line) as maestro.LogEntry?

        assertNotNull(entry)
        assertEquals("Message with: multiple: colons", entry.message)
    }

    @Test
    fun `parseLogLine should handle long tag names`() {
        val logCapture = LogCapture()
        val parseMethod = logCapture.javaClass.getDeclaredMethod("parseLogLine", String::class.java)
        parseMethod.isAccessible = true

        val line = "11-24 10:35:09.552  662  4254 I maestro.drivers.AndroidDriver: Started log capture"
        val entry = parseMethod.invoke(logCapture, line) as maestro.LogEntry?

        assertNotNull(entry)
        assertEquals("maestro.drivers.AndroidDriver", entry.tag)
        assertEquals("Started log capture", entry.message)
    }
}
