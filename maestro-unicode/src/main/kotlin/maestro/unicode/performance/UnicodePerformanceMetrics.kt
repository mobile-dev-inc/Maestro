package maestro.unicode.performance

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Performance metrics collection and analysis for Unicode input operations.
 * Provides detailed insights into Unicode input performance characteristics.
 */
class UnicodePerformanceMetrics {
    
    private val operationCounts = ConcurrentHashMap<String, AtomicLong>()
    private val operationTimes = ConcurrentHashMap<String, MutableList<Long>>()
    private val errorCounts = ConcurrentHashMap<String, AtomicLong>()
    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)
    private val keyboardSwitches = AtomicLong(0)
    private val fallbackUsages = ConcurrentHashMap<String, AtomicLong>()
    
    /**
     * Records the execution time of an operation.
     */
    fun recordOperation(operationType: String, durationMs: Long) {
        operationCounts.computeIfAbsent(operationType) { AtomicLong(0) }.incrementAndGet()
        operationTimes.computeIfAbsent(operationType) { mutableListOf() }.add(durationMs)
    }
    
    /**
     * Records an error occurrence.
     */
    fun recordError(errorType: String) {
        errorCounts.computeIfAbsent(errorType) { AtomicLong(0) }.incrementAndGet()
    }
    
    /**
     * Records a cache hit.
     */
    fun recordCacheHit() {
        cacheHits.incrementAndGet()
    }
    
    /**
     * Records a cache miss.
     */
    fun recordCacheMiss() {
        cacheMisses.incrementAndGet()
    }
    
    /**
     * Records a keyboard switch operation.
     */
    fun recordKeyboardSwitch() {
        keyboardSwitches.incrementAndGet()
    }
    
    /**
     * Records usage of a fallback method.
     */
    fun recordFallbackUsage(fallbackType: String) {
        fallbackUsages.computeIfAbsent(fallbackType) { AtomicLong(0) }.incrementAndGet()
    }
    
    /**
     * Gets comprehensive performance statistics.
     */
    fun getPerformanceStats(): PerformanceStats {
        val stats = mutableMapOf<String, OperationStats>()
        
        for ((operation, times) in operationTimes) {
            val timesList = times.toList()
            if (timesList.isNotEmpty()) {
                val count = operationCounts[operation]?.get() ?: 0
                val total = timesList.sum()
                val average = total.toDouble() / timesList.size
                val minimum = timesList.minOrNull() ?: 0L
                val maximum = timesList.maxOrNull() ?: 0L
                val median = timesList.sorted().let { sorted ->
                    val size = sorted.size
                    if (size % 2 == 0) {
                        (sorted[size / 2 - 1] + sorted[size / 2]) / 2.0
                    } else {
                        sorted[size / 2].toDouble()
                    }
                }
                
                stats[operation] = OperationStats(
                    count = count,
                    totalTimeMs = total,
                    averageTimeMs = average,
                    minTimeMs = minimum,
                    maxTimeMs = maximum,
                    medianTimeMs = median
                )
            }
        }
        
        val totalCacheOperations = cacheHits.get() + cacheMisses.get()
        val cacheHitRate = if (totalCacheOperations > 0) {
            cacheHits.get().toDouble() / totalCacheOperations
        } else {
            0.0
        }
        
        return PerformanceStats(
            operationStats = stats,
            cacheHitRate = cacheHitRate,
            totalCacheOperations = totalCacheOperations,
            keyboardSwitches = keyboardSwitches.get(),
            errorCounts = errorCounts.mapValues { it.value.get() },
            fallbackUsages = fallbackUsages.mapValues { it.value.get() }
        )
    }
    
    /**
     * Gets a performance report as a formatted string.
     */
    fun getPerformanceReport(): String {
        val stats = getPerformanceStats()
        val report = StringBuilder()
        
        report.appendLine("=== Unicode Performance Report ===")
        report.appendLine()
        
        // Operation statistics
        report.appendLine("Operation Statistics:")
        for ((operation, operationStats) in stats.operationStats) {
            report.appendLine("  $operation:")
            report.appendLine("    Count: ${operationStats.count}")
            report.appendLine("    Total Time: ${operationStats.totalTimeMs}ms")
            report.appendLine("    Average Time: ${"%.2f".format(operationStats.averageTimeMs)}ms")
            report.appendLine("    Min Time: ${operationStats.minTimeMs}ms")
            report.appendLine("    Max Time: ${operationStats.maxTimeMs}ms")
            report.appendLine("    Median Time: ${"%.2f".format(operationStats.medianTimeMs)}ms")
            report.appendLine()
        }
        
        // Cache performance
        report.appendLine("Cache Performance:")
        report.appendLine("  Hit Rate: ${"%.2f".format(stats.cacheHitRate * 100)}%")
        report.appendLine("  Total Cache Operations: ${stats.totalCacheOperations}")
        report.appendLine("  Cache Hits: ${cacheHits.get()}")
        report.appendLine("  Cache Misses: ${cacheMisses.get()}")
        report.appendLine()
        
        // Keyboard switches
        report.appendLine("Keyboard Switches: ${stats.keyboardSwitches}")
        report.appendLine()
        
        // Error statistics
        if (stats.errorCounts.isNotEmpty()) {
            report.appendLine("Error Statistics:")
            for ((errorType, count) in stats.errorCounts) {
                report.appendLine("  $errorType: $count")
            }
            report.appendLine()
        }
        
        // Fallback usage
        if (stats.fallbackUsages.isNotEmpty()) {
            report.appendLine("Fallback Usage:")
            for ((fallbackType, count) in stats.fallbackUsages) {
                report.appendLine("  $fallbackType: $count")
            }
            report.appendLine()
        }
        
        // Performance insights
        report.appendLine("Performance Insights:")
        report.appendLine(generatePerformanceInsights(stats))
        
        return report.toString()
    }
    
    /**
     * Generates performance insights and recommendations.
     */
    private fun generatePerformanceInsights(stats: PerformanceStats): String {
        val insights = mutableListOf<String>()
        
        // Cache performance insights
        when {
            stats.cacheHitRate > 0.8 -> insights.add("✅ Excellent cache performance (${(stats.cacheHitRate * 100).toInt()}% hit rate)")
            stats.cacheHitRate > 0.6 -> insights.add("⚠️  Good cache performance (${(stats.cacheHitRate * 100).toInt()}% hit rate)")
            stats.cacheHitRate > 0.4 -> insights.add("⚠️  Average cache performance (${(stats.cacheHitRate * 100).toInt()}% hit rate) - consider optimizing cache strategy")
            else -> insights.add("❌ Poor cache performance (${(stats.cacheHitRate * 100).toInt()}% hit rate) - cache optimization needed")
        }
        
        // Keyboard switch insights
        val avgOperations = stats.operationStats.values.map { it.count }.sum()
        val switchRatio = if (avgOperations > 0) stats.keyboardSwitches.toDouble() / avgOperations else 0.0
        
        when {
            switchRatio < 0.1 -> insights.add("✅ Excellent keyboard switch efficiency (${(switchRatio * 100).toInt()}% switch ratio)")
            switchRatio < 0.3 -> insights.add("⚠️  Good keyboard switch efficiency (${(switchRatio * 100).toInt()}% switch ratio)")
            switchRatio < 0.5 -> insights.add("⚠️  Average keyboard switch efficiency (${(switchRatio * 100).toInt()}% switch ratio) - consider keeping keyboard active")
            else -> insights.add("❌ Poor keyboard switch efficiency (${(switchRatio * 100).toInt()}% switch ratio) - optimization needed")
        }
        
        // Operation time insights
        val unicodeOperations = stats.operationStats.filter { it.key.contains("unicode", ignoreCase = true) }
        if (unicodeOperations.isNotEmpty()) {
            val avgUnicodeTime = unicodeOperations.values.map { it.averageTimeMs }.average()
            when {
                avgUnicodeTime < 200 -> insights.add("✅ Excellent Unicode input performance (avg ${avgUnicodeTime.toInt()}ms)")
                avgUnicodeTime < 500 -> insights.add("⚠️  Good Unicode input performance (avg ${avgUnicodeTime.toInt()}ms)")
                avgUnicodeTime < 1000 -> insights.add("⚠️  Average Unicode input performance (avg ${avgUnicodeTime.toInt()}ms) - consider optimization")
                else -> insights.add("❌ Poor Unicode input performance (avg ${avgUnicodeTime.toInt()}ms) - optimization needed")
            }
        }
        
        // Error rate insights
        val totalOperations = stats.operationStats.values.map { it.count }.sum()
        val totalErrors = stats.errorCounts.values.sum()
        val errorRate = if (totalOperations > 0) totalErrors.toDouble() / totalOperations else 0.0
        
        when {
            errorRate < 0.01 -> insights.add("✅ Excellent error rate (${(errorRate * 100).toInt()}%)")
            errorRate < 0.05 -> insights.add("⚠️  Good error rate (${(errorRate * 100).toInt()}%)")
            errorRate < 0.1 -> insights.add("⚠️  Average error rate (${(errorRate * 100).toInt()}%) - consider improving error handling")
            else -> insights.add("❌ High error rate (${(errorRate * 100).toInt()}%) - investigate error causes")
        }
        
        // Fallback usage insights
        val totalFallbacks = stats.fallbackUsages.values.sum()
        val fallbackRate = if (totalOperations > 0) totalFallbacks.toDouble() / totalOperations else 0.0
        
        when {
            fallbackRate < 0.05 -> insights.add("✅ Excellent primary method reliability (${(fallbackRate * 100).toInt()}% fallback rate)")
            fallbackRate < 0.1 -> insights.add("⚠️  Good primary method reliability (${(fallbackRate * 100).toInt()}% fallback rate)")
            fallbackRate < 0.2 -> insights.add("⚠️  Average primary method reliability (${(fallbackRate * 100).toInt()}% fallback rate)")
            else -> insights.add("❌ Poor primary method reliability (${(fallbackRate * 100).toInt()}% fallback rate) - investigate primary method issues")
        }
        
        return insights.joinToString("\n  ", prefix = "  ")
    }
    
    /**
     * Resets all metrics.
     */
    fun reset() {
        operationCounts.clear()
        operationTimes.clear()
        errorCounts.clear()
        cacheHits.set(0)
        cacheMisses.set(0)
        keyboardSwitches.set(0)
        fallbackUsages.clear()
    }
    
    /**
     * Gets metrics for a specific operation type.
     */
    fun getOperationMetrics(operationType: String): OperationStats? {
        return getPerformanceStats().operationStats[operationType]
    }
    
    /**
     * Checks if performance is within acceptable thresholds.
     */
    fun isPerformanceAcceptable(): Boolean {
        val stats = getPerformanceStats()
        
        // Check cache hit rate
        if (stats.cacheHitRate < 0.5) return false
        
        // Check Unicode operation performance
        val unicodeStats = stats.operationStats.values.filter { it.averageTimeMs > 0 }
        if (unicodeStats.any { it.averageTimeMs > 2000 }) return false
        
        // Check error rate
        val totalOperations = stats.operationStats.values.map { it.count }.sum()
        val totalErrors = stats.errorCounts.values.sum()
        val errorRate = if (totalOperations > 0) totalErrors.toDouble() / totalOperations else 0.0
        if (errorRate > 0.1) return false
        
        return true
    }
    
    data class OperationStats(
        val count: Long,
        val totalTimeMs: Long,
        val averageTimeMs: Double,
        val minTimeMs: Long,
        val maxTimeMs: Long,
        val medianTimeMs: Double
    )
    
    data class PerformanceStats(
        val operationStats: Map<String, OperationStats>,
        val cacheHitRate: Double,
        val totalCacheOperations: Long,
        val keyboardSwitches: Long,
        val errorCounts: Map<String, Long>,
        val fallbackUsages: Map<String, Long>
    )
}

/**
 * Inline function to measure execution time and record metrics.
 */
inline fun <T> UnicodePerformanceMetrics.measured(
    operationType: String,
    block: () -> T
): T {
    val startTime = System.currentTimeMillis()
    return try {
        block()
    } finally {
        val endTime = System.currentTimeMillis()
        recordOperation(operationType, endTime - startTime)
    }
}