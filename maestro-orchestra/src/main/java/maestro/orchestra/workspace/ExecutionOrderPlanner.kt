package maestro.orchestra.workspace

import maestro.orchestra.error.MissingOrderedFlows
import java.nio.file.Path

object ExecutionOrderPlanner {

    fun getFlowsToRunInSequence(
        paths: Map<String, Path>,
        flowOrder: List<String>,
        allowMissing: Boolean = false,
    ): List<Path> {
        val missingFlows = getMissingFlows(paths, flowOrder)
        if (missingFlows.isNotEmpty() && !allowMissing) {
            val message = """
                |These Flows are listed in executionOrder.flowsOrder but are not part of this run:
                |${missingFlows.joinToString("\n") { "- $it" }}
                |
                |Check that the names are spelled correctly, and that include/exclude tags or Flow inclusion patterns haven't left them out.
                """.trimMargin()
            throw MissingOrderedFlows(message, missingFlows)
        }

        return flowOrder.distinct().mapNotNull { paths[it] }
    }

    fun getMissingFlows(
        paths: Map<String, Path>,
        flowOrder: List<String>,
    ): List<String> = flowOrder.distinct().filterNot { it in paths }

}
