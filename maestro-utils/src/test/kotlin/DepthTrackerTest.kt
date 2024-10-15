import maestro.utils.DepthTracker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DepthTrackerTest {

    @BeforeEach
    fun setUp() {
        DepthTracker.trackDepth(0)
    }

    @Test
    fun `trackDepth should update currentDepth and maxDepth`() {
        DepthTracker.trackDepth(10)
        assertEquals(10, DepthTracker.getMaxDepth())
    }

    @Test
    fun `getMaxDepth should return the maximum depth tracked`() {
        DepthTracker.trackDepth(3)
        DepthTracker.trackDepth(2)
        DepthTracker.trackDepth(5)

        assertEquals(5, DepthTracker.getMaxDepth())
    }
}