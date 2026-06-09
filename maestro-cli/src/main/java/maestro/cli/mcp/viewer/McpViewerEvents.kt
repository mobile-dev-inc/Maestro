package maestro.cli.mcp.viewer

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonValue
import java.util.concurrent.atomic.AtomicReference

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = ViewerEvent.MaestroConnected::class, name = "maestro.connected"),
    JsonSubTypes.Type(value = ViewerEvent.FlowState::class, name = "maestro.flow_state"),
    JsonSubTypes.Type(value = ViewerEvent.Tap::class, name = "driver.tap"),
    JsonSubTypes.Type(value = ViewerEvent.Swipe::class, name = "driver.swipe"),
    JsonSubTypes.Type(value = ViewerEvent.InputText::class, name = "driver.input_text"),
)
internal sealed interface ViewerEvent {

    data class MaestroConnected(
        val platform: String,
        // null for web sessions, which the viewer doesn't stream.
        val deviceType: StreamDeviceType?,
        val deviceId: String,
    ) : ViewerEvent

    // Full snapshot of the in-flight flow's top-level commands. Published whenever
    // anything changes — the frontend is a pure projection of the latest snapshot,
    // merging by commandId so prior runs' rows accumulate alongside the current one.
    data class FlowState(
        val commands: List<CommandEntry>,
    ) : ViewerEvent

    data class CommandEntry(
        val commandId: String,
        val yaml: String,
        // 0 = top-level; >0 = nested inside a composite command (runFlow, repeat, retry).
        // The frontend decides how to render nested commands; today it ignores them.
        val depth: Int,
        val status: CommandStatus,
        val errorMessage: String? = null,
    )

    data class Tap(
        val status: DriverStatus,
        val point: Point2D,
    ) : ViewerEvent

    data class Swipe(
        val status: DriverStatus,
        val start: Point2D,
        val end: Point2D,
        val durationMs: Long,
    ) : ViewerEvent

    data class InputText(
        val status: DriverStatus,
        val textLength: Int,
    ) : ViewerEvent
}

internal enum class CommandStatus(@JsonValue val wire: String) {
    PENDING("pending"),
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

internal object McpViewerEvents {
    private val publisher = AtomicReference<((ViewerEvent) -> Unit)?>(null)

    fun register(publish: (ViewerEvent) -> Unit): AutoCloseable {
        publisher.set(publish)
        return AutoCloseable { publisher.compareAndSet(publish, null) }
    }

    fun publish(event: ViewerEvent) {
        publisher.get()?.invoke(event)
    }
}
