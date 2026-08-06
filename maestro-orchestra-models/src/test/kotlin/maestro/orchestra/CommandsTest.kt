package maestro.orchestra

import maestro.MaestroException
import maestro.js.GraalJsEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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

    // https://github.com/mobile-dev-inc/Maestro/issues/3483
    @Test
    fun `ScrollUntilVisibleCommand waitToSettleTimeoutMsValue should return null, parse valid values, and throw on invalid`() {
        val selector = ElementSelector()
        assertNull(
            ScrollUntilVisibleCommand(
                selector = selector,
                direction = maestro.ScrollDirection.DOWN,
                visibilityPercentage = 100,
                centerElement = false,
                waitToSettleTimeoutMs = null,
            ).waitToSettleTimeoutMsValue()
        )
        assertEquals(
            2000,
            ScrollUntilVisibleCommand(
                selector = selector,
                direction = maestro.ScrollDirection.DOWN,
                visibilityPercentage = 100,
                centerElement = false,
                waitToSettleTimeoutMs = "2000",
            ).waitToSettleTimeoutMsValue()
        )
        val command = ScrollUntilVisibleCommand(
            selector = selector,
            direction = maestro.ScrollDirection.DOWN,
            visibilityPercentage = 100,
            centerElement = false,
            waitToSettleTimeoutMs = "abc",
        )
        val ex = assertThrows(MaestroException.InvalidCommand::class.java) {
            command.waitToSettleTimeoutMsValue()
        }
        assertEquals(
            "Invalid waitToSettleTimeoutMs value 'abc' in '${command.description()}'. waitToSettleTimeoutMs must be a number of milliseconds.",
            ex.message
        )
    }

    // https://github.com/mobile-dev-inc/Maestro/issues/3483
    @Test
    fun `ScrollUntilVisibleCommand evaluateScripts interpolates waitToSettleTimeoutMs`() {
        GraalJsEngine(platform = "android").use { jsEngine ->
            jsEngine.putEnv("SETTLE_MS", "2000")

            val evaluated = ScrollUntilVisibleCommand(
                selector = ElementSelector(),
                direction = maestro.ScrollDirection.DOWN,
                visibilityPercentage = 100,
                centerElement = false,
                waitToSettleTimeoutMs = "\${SETTLE_MS}",
            ).evaluateScripts(jsEngine)

            assertEquals("2000", evaluated.waitToSettleTimeoutMs)
            assertEquals(2000, evaluated.waitToSettleTimeoutMsValue())
        }
    }

    // https://github.com/mobile-dev-inc/Maestro/issues/3483
    @Test
    fun `SwipeCommand durationMs should parse valid values and throw on invalid`() {
        assertEquals(400L, SwipeCommand(direction = maestro.SwipeDirection.LEFT).durationMs())
        assertEquals(
            2000L,
            SwipeCommand(direction = maestro.SwipeDirection.LEFT, duration = "2000").durationMs()
        )
        val command = SwipeCommand(direction = maestro.SwipeDirection.LEFT, duration = "abc")
        val ex = assertThrows(MaestroException.InvalidCommand::class.java) {
            command.durationMs()
        }
        assertEquals(
            "Invalid duration value 'abc' in '${command.description()}'. duration must be a number of milliseconds.",
            ex.message
        )
    }

    // https://github.com/mobile-dev-inc/Maestro/issues/3483
    @Test
    fun `SwipeCommand waitToSettleTimeoutMsValue should return null, parse valid values, and throw on invalid`() {
        assertNull(SwipeCommand(direction = maestro.SwipeDirection.LEFT).waitToSettleTimeoutMsValue())
        assertEquals(
            50,
            SwipeCommand(direction = maestro.SwipeDirection.LEFT, waitToSettleTimeoutMs = "50").waitToSettleTimeoutMsValue()
        )
        val command = SwipeCommand(direction = maestro.SwipeDirection.LEFT, waitToSettleTimeoutMs = "abc")
        val ex = assertThrows(MaestroException.InvalidCommand::class.java) {
            command.waitToSettleTimeoutMsValue()
        }
        assertEquals(
            "Invalid waitToSettleTimeoutMs value 'abc' in '${command.description()}'. waitToSettleTimeoutMs must be a number of milliseconds.",
            ex.message
        )
    }

    // https://github.com/mobile-dev-inc/Maestro/issues/3483
    @Test
    fun `SwipeCommand evaluateScripts interpolates duration and waitToSettleTimeoutMs`() {
        GraalJsEngine(platform = "android").use { jsEngine ->
            jsEngine.putEnv("DURATION_MS", "1500")
            jsEngine.putEnv("SETTLE_MS", "2000")

            val evaluated = SwipeCommand(
                direction = maestro.SwipeDirection.LEFT,
                duration = "\${DURATION_MS}",
                waitToSettleTimeoutMs = "\${SETTLE_MS}",
            ).evaluateScripts(jsEngine)

            assertEquals("1500", evaluated.duration)
            assertEquals(1500L, evaluated.durationMs())
            assertEquals("2000", evaluated.waitToSettleTimeoutMs)
            assertEquals(2000, evaluated.waitToSettleTimeoutMsValue())
        }
    }

    // https://github.com/mobile-dev-inc/Maestro/issues/3483
    @Test
    fun `TapOnElementCommand waitToSettleTimeoutMsValue clamps to MAX_TIMEOUT_WAIT_TO_SETTLE_MS`() {
        val selector = ElementSelector()
        assertNull(TapOnElementCommand(selector = selector).waitToSettleTimeoutMsValue())
        assertEquals(
            5000,
            TapOnElementCommand(selector = selector, waitToSettleTimeoutMs = "5000").waitToSettleTimeoutMsValue()
        )
        assertEquals(
            TapOnElementCommand.MAX_TIMEOUT_WAIT_TO_SETTLE_MS,
            TapOnElementCommand(selector = selector, waitToSettleTimeoutMs = "999999").waitToSettleTimeoutMsValue()
        )
    }

    // https://github.com/mobile-dev-inc/Maestro/issues/3483
    @Test
    fun `TapOnPointV2Command waitToSettleTimeoutMsValue clamps to MAX_TIMEOUT_WAIT_TO_SETTLE_MS`() {
        assertNull(TapOnPointV2Command(point = "10,10").waitToSettleTimeoutMsValue())
        assertEquals(
            5000,
            TapOnPointV2Command(point = "10,10", waitToSettleTimeoutMs = "5000").waitToSettleTimeoutMsValue()
        )
        assertEquals(
            TapOnElementCommand.MAX_TIMEOUT_WAIT_TO_SETTLE_MS,
            TapOnPointV2Command(point = "10,10", waitToSettleTimeoutMs = "999999").waitToSettleTimeoutMsValue()
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