package maestro.cli.view

import maestro.MaestroException
import maestro.orchestra.error.InvalidFlowFile
import maestro.orchestra.error.NoInputException
import maestro.orchestra.error.UnicodeNotSupportedError
import maestro.orchestra.error.ValidationError
import org.mozilla.javascript.EcmaError

object ErrorViewUtils {

    fun exceptionToMessage(e: Exception): String {
        return when (e) {
            is ValidationError -> e.message
            is NoInputException -> "No commands found in Flow file"
            is InvalidFlowFile -> "Flow file is invalid: ${e.flowPath}"
            is UnicodeNotSupportedError -> "Unicode character input is not supported: ${e.text}. Please use ASCII characters. Follow the issue: https://github.com/mobile-dev-inc/maestro/issues/146"
            is InterruptedException -> "Interrupted"
            is MaestroException -> e.message
            is EcmaError -> "${e.name}: ${e.message}}"
            else -> e.stackTraceToString()
        }
    }

}
