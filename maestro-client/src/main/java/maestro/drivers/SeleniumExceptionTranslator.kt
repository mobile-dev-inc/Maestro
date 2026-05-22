package maestro.drivers

import maestro.DeviceUnreachableException
import maestro.Driver
import maestro.MaestroException
import maestro.TreeNode
import org.openqa.selenium.NoSuchSessionException
import org.openqa.selenium.SessionNotCreatedException
import org.openqa.selenium.WebDriverException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * Wraps a [Driver] so Selenium exceptions thrown by the underlying web driver are translated
 * into maestro-level exceptions at the [Driver] interface boundary. Callers (Orchestra, worker)
 * see only [MaestroException] (test failure) or [DeviceUnreachableException] (infra failure),
 * never `org.openqa.selenium.*` — driver-specific knowledge stays inside this package.
 *
 * Infra-shaped Selenium failures (session gone, browser unreachable, network errors) map to
 * [DeviceUnreachableException]. Everything else is page-state and maps to
 * [MaestroException.AssertionFailure].
 */
object SeleniumExceptionTranslator {

    fun wrap(delegate: Driver): Driver =
        Proxy.newProxyInstance(
            Driver::class.java.classLoader,
            arrayOf(Driver::class.java),
        ) { _, method, args ->
            try {
                method.invoke(delegate, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw translate(e.targetException, method.name)
            }
        } as Driver

    private fun translate(throwable: Throwable, callName: String): Throwable {
        if (throwable !is WebDriverException) return throwable

        val message = throwable.message.orEmpty()
        val isInfra = throwable is SessionNotCreatedException ||
            throwable is NoSuchSessionException ||
            message.contains("chrome not reachable") ||
            message.contains("net::ERR_")

        return if (isInfra) {
            DeviceUnreachableException(callName = callName, cause = throwable)
        } else {
            MaestroException.AssertionFailure(
                message = "Web automation failed: ${throwable.message ?: throwable::class.simpleName}",
                hierarchyRoot = TreeNode(),
                debugMessage = throwable.message ?: throwable::class.simpleName.orEmpty(),
                cause = throwable,
            )
        }
    }
}
