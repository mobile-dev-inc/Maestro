package maestro.cli.runner

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe queue for managing flow execution across multiple shards.
 * Allows dynamic work distribution where devices pull flows as they complete.
 * Prevents the same flow from being executed simultaneously on multiple shards.
 */
class FlowQueue(
    flows: List<Path>,
    private val maxRetries: Int = 3
) {
    private val pendingFlows = ConcurrentLinkedQueue(flows.map { FlowTask(it, 0) })
    private val retryFlows = ConcurrentLinkedQueue<FlowTask>() // High-priority queue for retries
    private val failedFlows = ConcurrentLinkedQueue<FlowTask>()
    private val completedFlows = ConcurrentLinkedQueue<FlowTask>()
    private val inProgressFlows = ConcurrentHashMap<Path, FlowTask>()
    private val totalFlows = flows.size
    private val completedCount = AtomicInteger(0)
    private val failedCount = AtomicInteger(0)

    data class FlowTask(
        val flowPath: Path,
        val attemptNumber: Int
    )

    /**
     * Get the next flow to execute, or null if no flows are available.
     * This is thread-safe and can be called by multiple shards concurrently.
     * Ensures that the same flow (by path) is not executed simultaneously on multiple shards.
     * Prioritizes retries over new flows - retries are picked up immediately.
     */
    fun getNextFlow(): FlowTask? {
        while (true) {
            // Check retry queue first (high priority)
            val task = retryFlows.poll() ?: pendingFlows.poll() ?: return null

            // Try to mark this flow as in-progress
            // If it's already in progress, skip it and try the next one
            if (inProgressFlows.putIfAbsent(task.flowPath, task) == null) {
                // Successfully marked as in-progress
                return task
            }
            // Flow is already in progress on another shard, try next flow
        }
    }

    /**
     * Mark a flow as completed successfully.
     * Removes it from the in-progress tracking.
     */
    fun markCompleted(task: FlowTask) {
        completedFlows.add(task)
        completedCount.incrementAndGet()
        inProgressFlows.remove(task.flowPath)
    }

    /**
     * Mark a flow as failed. If retries are available, it will be re-queued.
     * Removes it from in-progress tracking and re-queues if retries remain.
     * Retries are added to a high-priority queue for immediate retry on the next available device.
     * @return true if the flow was re-queued for retry, false otherwise
     */
    fun markFailed(task: FlowTask): Boolean {
        failedCount.incrementAndGet()
        inProgressFlows.remove(task.flowPath)

        if (task.attemptNumber < maxRetries) {
            // Re-queue for immediate retry in the high-priority retry queue
            val retryTask = task.copy(attemptNumber = task.attemptNumber + 1)
            retryFlows.add(retryTask)
            return true
        } else {
            // Max retries reached
            failedFlows.add(task)
            return false
        }
    }

    /**
     * Check if there are any flows left to process (in either queue).
     */
    fun hasMoreFlows(): Boolean {
        return retryFlows.isNotEmpty() || pendingFlows.isNotEmpty()
    }

    /**
     * Get the total number of flows (original count, not including retries).
     */
    fun getTotalFlows(): Int = totalFlows

    /**
     * Get the number of completed flows.
     */
    fun getCompletedCount(): Int = completedCount.get()

    /**
     * Get the number of failed flows (including retries).
     */
    fun getFailedCount(): Int = failedCount.get()

    /**
     * Get all failed flows that have exhausted retries.
     */
    fun getFailedFlows(): List<FlowTask> = failedFlows.toList()

    /**
     * Get all completed flows.
     */
    fun getCompletedFlows(): List<FlowTask> = completedFlows.toList()
}

