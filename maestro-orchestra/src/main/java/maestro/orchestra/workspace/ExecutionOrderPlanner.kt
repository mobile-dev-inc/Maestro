package maestro.orchestra.workspace

import java.nio.file.Path

object ExecutionOrderPlanner {

    fun getFlowsToRunInSequence(
        paths: Map<String, Path>,
        flowOrder: List<String>,
    ): List<Path> {
        if (flowOrder.isEmpty()) return emptyList()

        val orderSet = flowOrder.toSet()

        val namesInOrder = paths.keys.filter { it in orderSet }
        if (namesInOrder.isEmpty()) return emptyList()

        val result = orderSet.takeWhile { it in namesInOrder }

        return if (result.isEmpty()) {
            error("Could not find flows needed for execution in order: ${(orderSet - namesInOrder.toSet()).joinToString()}")
        } else if (flowOrder.slice(result.indices) == result) {
            result.map { paths[it]!! }
        } else {
            emptyList()
        }
    }

}
