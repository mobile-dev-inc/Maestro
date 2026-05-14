package maestro.cli.mcp.visualizer

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import java.util.concurrent.atomic.AtomicReference

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = VisualizerEvent.MaestroConnected::class, name = "maestro.connected"),
    JsonSubTypes.Type(value = VisualizerEvent.Command::class, name = "maestro.command"),
    JsonSubTypes.Type(value = VisualizerEvent.Tap::class, name = "driver.tap"),
    JsonSubTypes.Type(value = VisualizerEvent.Swipe::class, name = "driver.swipe"),
    JsonSubTypes.Type(value = VisualizerEvent.InputText::class, name = "driver.input_text"),
)
internal sealed interface VisualizerEvent {

    data class MaestroConnected(
        val platform: String,
        val deviceId: String,
    ) : VisualizerEvent

    data class Command(
        val status: CommandStatus,
        val callId: String,
        val yaml: String?,
        val errorMessage: String? = null,
    ) : VisualizerEvent

    data class Tap(
        val status: DriverStatus,
        val point: Point2D,
    ) : VisualizerEvent

    data class Swipe(
        val status: DriverStatus,
        val start: Point2D,
        val end: Point2D,
        val durationMs: Long,
    ) : VisualizerEvent

    data class InputText(
        val status: DriverStatus,
        val textLength: Int,
    ) : VisualizerEvent
}

internal enum class CommandStatus(@JsonValue val wire: String) {
    STARTED("started"),
    COMPLETED("completed"),
    FAILED("failed"),
    WARNED("warned"),
    SKIPPED("skipped"),
}

internal enum class DriverStatus(@JsonValue val wire: String) {
    STARTED("started"),
    COMPLETED("completed"),
    FAILED("failed"),
}
// Normalized [0, 1] coordinates within the device's screen.
internal data class Point2D(val x: Double, val y: Double)

internal object McpVisualizerEvents {
    private val publisher = AtomicReference<((VisualizerEvent) -> Unit)?>(null)

    fun register(publish: (VisualizerEvent) -> Unit): AutoCloseable {
        publisher.set(publish)
        return AutoCloseable { publisher.compareAndSet(publish, null) }
    }

    fun publish(event: VisualizerEvent) {
        publisher.get()?.invoke(event)
    }
}
