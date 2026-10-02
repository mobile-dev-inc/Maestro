package maestro.orchestra.workspace

import java.nio.file.Path

object ExecutionOrderPlanner {

    fun getFlowsToRunInSequence(
        paths: Map<String, Path>,
        flowOrder: List<String>,
    ): List<Path> {
        if (flowOrder.isEmpty()) return emptyList()

        val order = flowOrder.distinct()
        val present = order.filter { it in paths }
        if (present.isEmpty()) return emptyList()

        // The sequence may stop early - flows missing after the last present one are ignored -
        // but it must not skip a step and carry on, so a gap before a present flow is an error.
        val lastPresentIndex = order.indexOf(present.last())
        if (order.subList(0, lastPresentIndex).any { it !in paths }) {
            val missing = order.filterNot { it in paths }
            error("Could not find flows needed for execution in order: ${missing.joinToString()}")
        }

        return present.map { paths.getValue(it) }
    }

}
