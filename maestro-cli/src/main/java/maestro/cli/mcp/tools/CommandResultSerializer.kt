package maestro.cli.mcp.tools

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.putJsonArray
import maestro.orchestra.CommandResult

/**
 * Serializes this [CommandResult]'s spatial data into a [JsonObjectBuilder].
 * Adds "bounds", "center", "start_point", and "end_point" fields when present.
 */
fun CommandResult.addBoundsTo(builder: JsonObjectBuilder) {
    bounds?.let { b ->
        builder.putJsonArray("bounds") {
            add(b.x); add(b.y)
            add(b.x + b.width); add(b.y + b.height)
        }
        builder.putJsonArray("center") {
            add(b.x + b.width / 2); add(b.y + b.height / 2)
        }
    }
    startPoint?.let { p ->
        builder.putJsonArray("start_point") { add(p.x); add(p.y) }
    }
    endPoint?.let { p ->
        builder.putJsonArray("end_point") { add(p.x); add(p.y) }
    }
}
