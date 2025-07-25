package maestro.orchestra.workspace

import com.google.common.truth.Truth.assertThat
import maestro.orchestra.workspace.ExecutionOrderPlanner.getFlowsToRunInSequence
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.IllegalStateException
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
    fun `if there are less paths then the sequence it should return in only those in the sequence in the correct order if the missing are after all the paths in the sequence`() {
        val paths = mapOf("flowA" to Path("flowA"), "flowB" to Path("flowB"), "flowC" to Path("flowC"))
        val flowOrder = listOf("flowC", "flowA", "flowD")
        val expected = listOf(Path("flowC"), Path("flowA"))

        val result = getFlowsToRunInSequence(paths, flowOrder)
        assertThat(result).isEqualTo(expected)
    }

    @Test
    fun `if there are less paths then the sequence it should return error if the missing are not after all present`() {
        val pathsX = mapOf("flowA" to Path("flowA"), "flowB" to Path("flowB"), "flowC" to Path("flowC"))
        val flowOrderX = listOf("flowG", "flowC", "flowD", "flowA")

        val pathsY = mapOf("flowA" to Path("flowA"))
        val flowOrderY = listOf("flowE", "flowC", "flowD", "flowA")

        val exceptionX = assertThrows<IllegalStateException> {
            getFlowsToRunInSequence(pathsX, flowOrderX)
        }
        val exceptionY = assertThrows<IllegalStateException> {
            getFlowsToRunInSequence(pathsY, flowOrderY)
        }
        assertThat(exceptionX.message).isEqualTo("Could not find flows needed for execution in order: flowG, flowD")
        assertThat(exceptionY.message).isEqualTo("Could not find flows needed for execution in order: flowE, flowC, flowD")
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
    fun `if no paths are present in the sequence it should return an empty list`() {
        val paths = mapOf("flowA" to Path("flowA"), "flowB" to Path("flowB"), "flowC" to Path("flowC"))
        val flowOrder = listOf("flowE", "flowD")
        val expected = emptyList<String>()

        val result = getFlowsToRunInSequence(paths, flowOrder)
        assertThat(result).isEqualTo(expected)
    }

}
