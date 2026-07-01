package maestro.cli.view

import maestro.MaestroException
import maestro.orchestra.error.InvalidFlowFile
import maestro.orchestra.error.NoInputException
import maestro.orchestra.error.ValidationError
import maestro.orchestra.error.formatForTerminal

object ErrorViewUtils {

    fun exceptionToMessage(e: Exception): String {
        return when (e) {
            is ValidationError -> e.formatForTerminal()
            is NoInputException -> "No commands found in Flow file"
            is InvalidFlowFile -> "Flow file is invalid: ${e.flowPath}"
            is InterruptedException -> "Interrupted"
            is MaestroException -> e.message
            else -> e.stackTraceToString()
        }
    }

}
