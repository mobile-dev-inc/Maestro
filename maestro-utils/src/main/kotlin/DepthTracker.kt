package maestro.utils

object DepthTracker {

    private var currentDepth: Int = 0
    private var maxDepth: Int = 0

    fun trackDepth(depth: Int) {
        currentDepth = depth
        if (currentDepth > maxDepth) {
            maxDepth = currentDepth
        }
    }

    fun getMaxDepth(): Int = maxDepth
}