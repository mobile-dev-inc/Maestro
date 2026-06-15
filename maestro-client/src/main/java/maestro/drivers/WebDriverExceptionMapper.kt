package maestro.drivers

import maestro.DeviceUnreachableException
import maestro.MaestroException
import maestro.TreeNode
import org.openqa.selenium.NoSuchSessionException
import org.openqa.selenium.SessionNotCreatedException
import org.openqa.selenium.TimeoutException
import org.openqa.selenium.WebDriverException

/**
 * Classifies a Selenium [WebDriverException] into a maestro-level exception so that
 * callers (Orchestra, the cloud worker) never see `org.openqa.selenium.*` types.
 *
 * This is the web equivalent of the per-call translation done inside
 * [IOSDriver.runDeviceCall] and [AndroidDriver.runDeviceCall]: a pure decision table,
 * applied from within [CdpWebDriver] rather than via an external proxy decorator, so
 * stack traces stay direct and the concrete driver type is preserved.
 *
 *  - Infra failures (session gone, browser unreachable, network errors) -> [DeviceUnreachableException].
 *  - Selenium timeouts -> [MaestroException.DriverTimeout].
 *  - Everything else is treated as page-state -> [MaestroException.AssertionFailure].
 *
 * "chrome not reachable" / "net::ERR_" are matched on message text because Selenium
 * surfaces them as a plain [WebDriverException] with no dedicated subtype.
 */
internal object WebDriverExceptionMapper {

    fun toMaestroException(e: WebDriverException, callName: String): Throwable {
        val message = e.message.orEmpty()

        val isInfra = e is SessionNotCreatedException ||
            e is NoSuchSessionException ||
            message.contains("chrome not reachable") ||
            message.contains("net::ERR_")

        return when {
            isInfra -> DeviceUnreachableException(callName = callName, cause = e)

            e is TimeoutException -> MaestroException.DriverTimeout(
                message = "Web driver timed out during $callName: ${e.message ?: e::class.simpleName}",
                cause = e,
            )

            else -> MaestroException.AssertionFailure(
                message = "Web automation failed during $callName: ${e.message ?: e::class.simpleName}",
                hierarchyRoot = TreeNode(),
                debugMessage = e.message ?: e::class.simpleName.orEmpty(),
                cause = e,
            )
        }
    }
}
