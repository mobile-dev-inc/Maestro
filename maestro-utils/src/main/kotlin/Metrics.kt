package maestro.utils

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.TimeUnit


// singleton to provide a metric manager across maestro code since there's so many singleton objects and passing it around would be a massive change
object MetricsProvider {
    private var metrics: Metrics = NoOpMetrics()

    fun setMetrics(metrics: Metrics) {
        this.metrics = metrics
    }

    fun getInstance(): Metrics {
        return metrics
    }
}

private fun toTags(map: Map<String, String?>): Iterable<Tag> {
    return map.filterValues {
        it != null
    }.map { Tag.of(it.key, it.value) }.toList()
}

private fun prefixed(prefix: String?, name: String): String {
    if (prefix == null) {
        return name
    }
    return "$prefix.$name"
}

open class Metrics(
    val registry: MeterRegistry,
    val prefix: String? = null,
    val tags: Map<String, String?> = emptyMap(),
) {
    fun <T> measured(name: String, tags: Map<String, String?> = emptyMap(), block: () -> T): T {
        val timer = Timer.builder(prefixed(prefix, name)).tags(toTags(tags)).register(registry)
        counter(prefixed(prefix, "$name.calls"), tags).increment()

        val t0 = System.currentTimeMillis()
        try {
            return block()
        } catch (e: Exception) {
            registry.counter(
                prefixed(prefix, "$name.errors"),
                toTags(tags + ("exception" to e.javaClass.simpleName))
            ).increment()
            throw e
        } finally {
            timer.record(System.currentTimeMillis() - t0, TimeUnit.MILLISECONDS)
        }
    }

    // get a metrics object that adds a certain prefix to all metrics
    fun withPrefix(prefix: String): Metrics {
        return Metrics(registry, prefixed(this.prefix, prefix))
    }

    // get a metrics object that adds labels to all metrics
    fun withTags(tags: Map<String, String>): Metrics {
        return Metrics(registry, prefix, this.tags + tags)
    }

    fun counter(name: String, labels: Map<String, String?> = emptyMap()): Counter {
        return Counter.builder(prefixed(prefix, name)).tags(toTags(tags + labels)).register(registry)
    }

    fun timer(name: String, labels: Map<String, String?> = emptyMap()): Timer {
        return Timer.builder(prefixed(prefix, name)).tags(toTags(tags + labels)).register(registry)
    }
}

class NoOpMetrics : Metrics(SimpleMeterRegistry(), "noop", emptyMap()) {

}
