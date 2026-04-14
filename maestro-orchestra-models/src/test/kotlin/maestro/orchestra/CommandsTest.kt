package maestro.orchestra

import maestro.MaestroException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CommandsTest {

    @Test
    fun `timeoutMs should return null for null timeout, parse valid values with underscores, and throw on invalid`() {
        assertNull(AssertConditionCommand(condition = Condition(), timeout = null).timeoutMs())
        assertEquals(10000L, AssertConditionCommand(condition = Condition(), timeout = "10_000").timeoutMs())
        assertThrows(MaestroException.InvalidCommand::class.java) {
            AssertConditionCommand(condition = Condition(), timeout = "abc").timeoutMs()
        }
    }

    @Test
    fun `should return not null value when call InputRandomCommand with NUMBER value`() {
        assertNotNull(InputRandomCommand(inputType = InputRandomType.NUMBER).genRandomString())
    }

    @Test
    fun `should return not null value when call InputRandomCommand with TEXT value`() {
        assertNotNull(InputRandomCommand(inputType = InputRandomType.TEXT).genRandomString())
    }

    @Test
    fun `should return not null value when call InputRandomCommand with TEXT_EMAIL_ADDRESS value`() {
        assertNotNull(InputRandomCommand(inputType = InputRandomType.TEXT_EMAIL_ADDRESS).genRandomString())
    }

    @Test
    fun `should return not null value when call InputRandomCommand with TEXT_PERSON_NAME value`() {
        assertNotNull(InputRandomCommand(inputType = InputRandomType.TEXT_PERSON_NAME).genRandomString())
    }

    @Test
    fun `should return not null value when call InputRandomCommand with TEXT_CITY_NAME value`() {
        assertNotNull(InputRandomCommand(inputType = InputRandomType.TEXT_CITY_NAME).genRandomString())
    }

    @Test
    fun `should return not null value when call InputRandomCommand with TEXT_COUNTRY_NAME value`() {
        assertNotNull(InputRandomCommand(inputType = InputRandomType.TEXT_COUNTRY_NAME).genRandomString())
    }

    @Test
    fun `should return not null value when call InputRandomCommand with TEXT_COLOR value`() {
        assertNotNull(InputRandomCommand(inputType = InputRandomType.TEXT_COLOR).genRandomString())
    }

    @Test
    fun `should return not null value when call InputRandomCommand without inputType value`() {
        assertNotNull(InputRandomCommand().genRandomString())
    }

    @Test
    fun `should return a value with 10 characters when call InputRandomCommand with NUMBER value and length value`() {
        assertEquals(10, InputRandomCommand(inputType = InputRandomType.NUMBER, length = 10).genRandomString().length)
    }

    @Test
    fun `should return a value with 20 characters when call InputRandomCommand with TEXT value and length value`() {
        assertEquals(20, InputRandomCommand(inputType = InputRandomType.TEXT, length = 20).genRandomString().length)
    }
}