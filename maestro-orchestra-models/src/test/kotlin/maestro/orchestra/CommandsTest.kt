package maestro.orchestra

import maestro.MaestroException
import maestro.ScrollDirection
import maestro.js.GraalJsEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandsTest {

    // https://github.com/mobile-dev-inc/Maestro/issues/2416
    @Test
    fun `LaunchAppCommand evaluateScripts interpolates permission values but not names`() {
        GraalJsEngine(platform = "android").use { jsEngine ->
            jsEngine.putEnv("PERMISSION_VALUE", "allow")
            jsEngine.putEnv("PERMISSION_NAME", "location")

            val evaluated = LaunchAppCommand(
                appId = "com.example.app",
                permissions = mapOf("location" to "\${PERMISSION_VALUE}", "\${PERMISSION_NAME}" to "deny"),
            ).evaluateScripts(jsEngine)

            assertEquals(mapOf("location" to "allow", "\${PERMISSION_NAME}" to "deny"), evaluated.permissions)
        }
    }

    // https://github.com/mobile-dev-inc/Maestro/issues/3452
    @Test
    fun `LaunchAppCommand evaluateScripts interpolates launchArgument names and values`() {
        GraalJsEngine(platform = "android").use { jsEngine ->
            jsEngine.putEnv("ARG_NAME", "isCartScreen")
            jsEngine.putEnv("USER_NAME", "ada")

            val evaluated = LaunchAppCommand(
                appId = "com.example.app",
                launchArguments = mapOf("\${ARG_NAME}" to true, "user" to "\${USER_NAME}"),
            ).evaluateScripts(jsEngine)

            assertEquals(mapOf("isCartScreen" to true, "user" to "ada"), evaluated.launchArguments)
        }
    }

    // https://github.com/mobile-dev-inc/Maestro/issues/2416
    @Test
    fun `SetPermissionsCommand evaluateScripts interpolates permission values but not names`() {
        GraalJsEngine(platform = "android").use { jsEngine ->
            jsEngine.putEnv("PERMISSION_VALUE", "allow")
            jsEngine.putEnv("PERMISSION_NAME", "location")

            val evaluated = SetPermissionsCommand(
                appId = "com.example.app",
                permissions = mapOf("location" to "\${PERMISSION_VALUE}", "\${PERMISSION_NAME}" to "deny"),
            ).evaluateScripts(jsEngine)

            assertEquals(mapOf("location" to "allow", "\${PERMISSION_NAME}" to "deny"), evaluated.permissions)
        }
    }

    // https://github.com/mobile-dev-inc/Maestro/issues/3607
    // Every value from 1 to 99 used to truncate to 0.0 under integer division, leaving the threshold
    // inert: any element present in the hierarchy satisfied it. 0 and 100 normalized correctly by
    // coincidence, which is why the existing coverage never caught this.
    @Test
    fun `ScrollUntilVisibleCommand normalizes visibilityPercentage across the whole 0-100 range`() {
        assertEquals(0.0, scrollUntilVisible(0).visibilityPercentageNormalized)
        assertEquals(0.01, scrollUntilVisible(1).visibilityPercentageNormalized)
        assertEquals(0.5, scrollUntilVisible(50).visibilityPercentageNormalized)
        assertEquals(0.7, scrollUntilVisible(70).visibilityPercentageNormalized)
        assertEquals(0.99, scrollUntilVisible(99).visibilityPercentageNormalized)
        assertEquals(1.0, scrollUntilVisible(100).visibilityPercentageNormalized)
    }

    // https://github.com/mobile-dev-inc/Maestro/issues/3607
    // Orchestra stops scrolling once `visibility >= visibilityPercentageNormalized`. An element
    // mostly hidden behind a sticky footer must not satisfy a request for 70% visibility.
    @Test
    fun `ScrollUntilVisibleCommand threshold rejects visibility below the requested percentage`() {
        val threshold = scrollUntilVisible(70).visibilityPercentageNormalized
        assertFalse(0.125 >= threshold)
        assertFalse(0.69 >= threshold)
        assertTrue(0.7 >= threshold)
        assertTrue(0.95 >= threshold)
    }

    private fun scrollUntilVisible(visibilityPercentage: Int) = ScrollUntilVisibleCommand(
        selector = ElementSelector(idRegex = "some_link"),
        direction = ScrollDirection.DOWN,
        visibilityPercentage = visibilityPercentage,
        centerElement = false,
    )

    @Test
    fun `timeoutMs should return null for null timeout, parse valid values with underscores, and throw on invalid`() {
        assertNull(AssertConditionCommand(condition = Condition(), timeout = null).timeoutMs())
        assertEquals(10000L, AssertConditionCommand(condition = Condition(), timeout = "10_000").timeoutMs())
        val command = AssertConditionCommand(condition = Condition(), timeout = "abc")
        val ex = assertThrows(MaestroException.InvalidCommand::class.java) {
            command.timeoutMs()
        }
        assertEquals(
            "Invalid timeout value 'abc' in '${command.description()}'. Timeout must be a number of milliseconds.",
            ex.message
        )
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