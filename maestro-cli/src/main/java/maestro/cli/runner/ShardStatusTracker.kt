package maestro.cli.runner

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe tracker for monitoring what each shard is currently working on.
 * Allows real-time visibility into shard activity during parallel test execution.
 */
class ShardStatusTracker {
    private val shardStatus = ConcurrentHashMap<Int, ShardStatus>()
    
    data class ShardStatus(
        val flowName: String,
        val attemptNumber: Int,
        val startTime: Long = System.currentTimeMillis()
    )
    
    /**
     * Update the status of a shard to indicate it's starting a new flow.
     */
    fun updateShardStatus(shardIndex: Int, flowName: String, attemptNumber: Int = 0) {
        shardStatus[shardIndex] = ShardStatus(flowName, attemptNumber)
    }
    
    /**
     * Mark a shard as idle (no longer working on a flow).
     */
    fun markShardIdle(shardIndex: Int) {
        shardStatus.remove(shardIndex)
    }
    
    /**
     * Get the current status of a specific shard.
     */
    fun getShardStatus(shardIndex: Int): ShardStatus? {
        return shardStatus[shardIndex]
    }
    
    /**
     * Get the status of all shards.
     */
    fun getAllShardStatuses(): Map<Int, ShardStatus> {
        return shardStatus.toMap()
    }
    
    /**
     * Get a formatted string showing what each shard is working on.
     */
    fun getFormattedStatus(totalShards: Int): String {
        val statuses = (0 until totalShards).map { shardIndex ->
            val status = shardStatus[shardIndex]
            val shardLabel = "[shard ${shardIndex + 1}]"
            if (status != null) {
                val attemptSuffix = if (status.attemptNumber > 0) " (attempt ${status.attemptNumber + 1})" else ""
                "$shardLabel Running: ${status.flowName}$attemptSuffix"
            } else {
                "$shardLabel Idle"
            }
        }
        return statuses.joinToString(" | ")
    }
}

