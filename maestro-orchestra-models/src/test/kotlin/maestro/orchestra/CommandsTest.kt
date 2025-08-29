package maestro.orchestra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class CommandsTest {

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