package maestro.cli.view

import maestro.MaestroException
import maestro.orchestra.error.InvalidFlowFile
import maestro.orchestra.error.NoInputException
import maestro.orchestra.error.UnicodeNotSupportedError
import maestro.orchestra.error.ValidationError
import org.graalvm.polyglot.PolyglotException
import org.mozilla.javascript.EcmaError

object ErrorViewUtils {

    fun exceptionToMessage(e: Throwable): String {
        return when (e) {
            is ValidationError -> e.message
            is NoInputException -> "No commands found in Flow file"
            is InvalidFlowFile -> "Flow file is invalid: ${e.flowPath}"
            is UnicodeNotSupportedError -> "Unicode character input is not supported: ${e.text}. Please use ASCII characters. Follow the issue: https://github.com/mobile-dev-inc/maestro/issues/146"
            is InterruptedException -> "Interrupted"
            is MaestroException -> e.message
            is PolyglotException -> {
                // Get the first stack trace element which should contain the JS location
                val firstTrace = e.stackTrace.firstOrNull()?.toString() ?: ""
                // Extract path from "at <js>.:program(/path/to/file.js:line)"
                val location = if (firstTrace.contains("(") && firstTrace.contains(")")) {
                    firstTrace.substringAfter("(").substringBefore(")")
                } else {
                    firstTrace.ifEmpty { "unknown location" }
                }
                "Script failed: ${e.message}\n    at $location\n\nCheck maestro.log for full polyglot stacktrace."
            }
            is EcmaError -> "${e.name}: ${e.message}}"
            else -> e.stackTraceToString()
        }
    }

}
