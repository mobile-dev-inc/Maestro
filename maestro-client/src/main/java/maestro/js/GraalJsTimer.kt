package maestro.js

import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyExecutable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GraalJsTimer {
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val timeouts = ConcurrentHashMap<Int, ScheduledFuture<*>>()
    private var timeoutCounter = 0
    private var activeTimersCount = AtomicInteger(0)
    private var activeTimers = CountDownLatch(0)

    val setTimeout = ProxyExecutable { args ->
        val callback = args[0] as Value
        val delay = (args[1] as Value).asLong()
        val restArgs = args.drop(2).toTypedArray()
        setTimeout(callback, delay, *restArgs)
    }

    val clearTimeout = ProxyExecutable { args ->
        val timeoutId = (args[0] as Value).asInt()
        clearTimeout(timeoutId)
        null
    }

    private fun setTimeout(callback: Value, delay: Long, vararg args: Any?): Int {
        val timeoutId = timeoutCounter++
        
        synchronized(this) {
            if (activeTimersCount.getAndIncrement() == 0) {
                activeTimers = CountDownLatch(1)
            }
        }
        
        val future = scheduler.schedule({
            try {
                callback.executeVoid(*args)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                timeouts.remove(timeoutId)
                if (activeTimersCount.decrementAndGet() == 0) {
                    activeTimers.countDown()
                }
            }
        }, delay, TimeUnit.MILLISECONDS)
        
        timeouts[timeoutId] = future
        return timeoutId
    }

    private fun clearTimeout(timeoutId: Int) {
        timeouts.remove(timeoutId)?.let { future ->
            future.cancel(false)
            if (activeTimersCount.decrementAndGet() == 0) {
                activeTimers.countDown()
            }
        }
    }

    fun waitForActiveTimers(timeout: Long = 30_000) {
        if (activeTimersCount.get() > 0) {
            activeTimers.await(timeout, TimeUnit.MILLISECONDS)
        }
    }

    fun close() {
        scheduler.shutdown()
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (e: InterruptedException) {
            scheduler.shutdownNow()
        }
    }
} 