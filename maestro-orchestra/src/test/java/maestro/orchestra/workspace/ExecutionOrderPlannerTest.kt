package maestro.orchestra.workspace

import com.google.common.truth.Truth.assertThat
import maestro.orchestra.error.MissingOrderedFlows
import maestro.orchestra.workspace.ExecutionOrderPlanner.getFlowsToRunInSequence
import maestro.orchestra.workspace.ExecutionOrderPlanner.getMissingFlows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.io.path.Path

internal class ExecutionOrderPlannerTest {

    @Test
    fun `if the paths are already in sequence it should return the sequence`() {
        val paths = mapOf("flowA" to Path("flowA"), "flowB" to Path("flowB"))
        val flowOrder = listOf("flowA", "flowB")
        val expected = listOf(Path("flowA"), Path("flowB"))

        val result = getFlowsToRunInSequence(paths, flowOrder)
        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `if the paths are not in sequence it should return in the correct sequence`() {
        val paths = mapOf("flowA" to Path("flowA"), "flowB" to Path("flowB"))
        val flowOrder = listOf("flowB", "flowA")
        val expected = listOf(Path("flowB"), Path("flowA"))

        val result = getFlowsToRunInSequence(paths, flowOrder)
        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `if there are more paths then the sequence it should return in only those in the sequence in the correct order`() {
        val paths = mapOf("flowA" to Path("flowA"), "flowB" to Path("flowB"), "flowC" to Path("flowC"))
        val flowOrder = listOf("flowC", "flowA")
        val expected = listOf(Path("flowC"), Path("flowA"))

        val result = getFlowsToRunInSequence(paths, flowOrder)
        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `if the last flow in the sequence is missing it should return an error`() {
        val paths = mapOf("flowA" to Path("flowA"), "flowB" to Path("flowB"), "flowC" to Path("flowC"))
        val flowOrder = listOf("flowC", "flowA", "flowD")

        val exception = assertThrows<MissingOrderedFlows> {
            getFlowsToRunInSequence(paths, flowOrder)
        }
        assertThat(exception.flowNames).containsExactly("flowD")
    }

    @Test
    fun `if flows before a present one are missing it should return an error listing them in order`() {
        val pathsX = mapOf("flowA" to Path("flowA"), "flowB" to Path("flowB"), "flowC" to Path("flowC"))
        val flowOrderX = listOf("flowG", "flowC", "flowD", "flowA")

        val pathsY = mapOf("flowA" to Path("flowA"))
        val flowOrderY = listOf("flowE", "flowC", "flowD", "flowA")

        val exceptionX = assertThrows<MissingOrderedFlows> {
            getFlowsToRunInSequence(pathsX, flowOrderX)
        }
        val exceptionY = assertThrows<MissingOrderedFlows> {
            getFlowsToRunInSequence(pathsY, flowOrderY)
        }
        assertThat(exceptionX.flowNames).containsExactly("flowG", "flowD").inOrder()
        assertThat(exceptionY.flowNames).containsExactly("flowE", "flowC", "flowD").inOrder()
    }

    @Test
    fun `if a flow in the middle of the sequence is missing it should return an error`() {
        val paths = mapOf("flowA" to Path("flowA"), "flowB" to Path("flowB"), "flowC" to Path("flowC"))
        val flowOrder = listOf("flowC", "flowD", "flowA")

        val exception = assertThrows<MissingOrderedFlows> {
            getFlowsToRunInSequence(paths, flowOrder)
        }
        assertThat(exception.flowNames).containsExactly("flowD")
    }

    @Test
    fun `if the sequence is empty it should return an empty list`() {
        val paths = mapOf("flowA" to Path("flowA"), "flowB" to Path("flowB"), "flowC" to Path("flowC"))
        val flowOrder = emptyList<String>()
        val expected = emptyList<String>()

        val result = getFlowsToRunInSequence(paths, flowOrder)
        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `if no paths are present in the sequence it should return an error`() {
        val paths = mapOf("flowA" to Path("flowA"), "flowB" to Path("flowB"), "flowC" to Path("flowC"))
        val flowOrder = listOf("flowE", "flowD")

        val exception = assertThrows<MissingOrderedFlows> {
            getFlowsToRunInSequence(paths, flowOrder)
        }
        assertThat(exception.flowNames).containsExactly("flowE", "flowD").inOrder()
    }

    @Test
    fun `if a flow is listed more than once it should keep the order of its first appearance`() {
        val paths = mapOf("flowA" to Path("flowA"), "flowB" to Path("flowB"))
        val flowOrder = listOf("flowA", "flowA", "flowB")
        val expected = listOf(Path("flowA"), Path("flowB"))

        val result = getFlowsToRunInSequence(paths, flowOrder)
        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `if missing flows are allowed it should skip them and keep the order of the rest`() {
        val paths = mapOf("flowA" to Path("flowA"), "flowB" to Path("flowB"), "flowC" to Path("flowC"))
        val flowOrder = listOf("flowD", "flowC", "flowE", "flowA")
        val expected = listOf(Path("flowC"), Path("flowA"))

        val result = getFlowsToRunInSequence(paths, flowOrder, allowMissing = true)
        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `if missing flows are allowed and none are present it should return an empty list`() {
        val paths = mapOf("flowA" to Path("flowA"))
        val flowOrder = listOf("flowE", "flowD")

        val result = getFlowsToRunInSequence(paths, flowOrder, allowMissing = true)
        assertThat(result).isEmpty()
    }

    @Test
    fun `missing flows should be listed once each in the order of the sequence`() {
        val paths = mapOf("flowA" to Path("flowA"))
        val flowOrder = listOf("flowC", "flowA", "flowB", "flowC")

        val result = getMissingFlows(paths, flowOrder)
        assertThat(result).containsExactly("flowC", "flowB").inOrder()
    }

    @Test
    fun `if every flow in the sequence is present there should be no missing flows`() {
        val paths = mapOf("flowA" to Path("flowA"), "flowB" to Path("flowB"))
        val flowOrder = listOf("flowB", "flowA")

        val result = getMissingFlows(paths, flowOrder)
        assertThat(result).isEmpty()
    }

}
