package maestro.orchestra.util

import maestro.MaestroException
import maestro.orchestra.Command
import maestro.orchestra.ElementSelector
import maestro.orchestra.ScrollUntilVisibleCommand
import maestro.orchestra.SwipeCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.TapOnPointV2Command
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Parses the numeric string fields flows can set from JS variables (`index`, `point`, scroll `speed`).
 * Raises a clear [MaestroException.InvalidCommand] instead of a raw [NumberFormatException], and backs
 * the static [maestro.orchestra.workspace.WorkspaceValidator] check with the same rules.
 */
object NumericFields {

    fun parseIndex(raw: String): Int = raw.toDoubleOrNull()?.toInt()
        ?: throw MaestroException.InvalidCommand(
            "Invalid index value \"$raw\": index must be a whole number. " +
                "If it comes from a variable, make sure the variable resolves to a number."
        )

    /** Parses `x,y` into two ints. `%` is stripped so callers keep their own range checks. */
    fun parsePoint(raw: String): Pair<Int, Int> {
        val coordinates = raw.replace("%", "").split(",").map { it.trim().toIntOrNull() ?: throw invalidPoint(raw) }
        if (coordinates.size < 2) throw invalidPoint(raw)
        return coordinates[0] to coordinates[1]
    }

    fun parseScrollSpeed(raw: String): Long = raw.toLongOrNull()
        ?: throw MaestroException.InvalidCommand(
            "Invalid speed value \"$raw\": speed must be a number between 0 and 100. " +
                "If it comes from a variable, make sure the variable resolves to a number."
        )

    private fun invalidPoint(raw: String) = MaestroException.InvalidCommand(
        "Invalid point value \"$raw\": expected two whole-number coordinates like \"x,y\" or \"x%,y%\". " +
            "If they come from variables, make sure the variables resolve to numbers."
    )

    /**
     * Validates a command's literal numeric fields up front, reusing the runtime parsers.
     * `${...}` values are left to runtime. Selectors (and their `index`) are discovered by type via
     * [reachableNodes], so new/nested/composite commands are covered without a maintained list.
     */
    fun staticErrors(command: Command): List<String> {
        val errors = mutableListOf<String>()
        val nodes = reachableNodes(command)

        nodes.filterIsInstance<ElementSelector>()
            .forEach { selector -> selector.index?.let { checkLiteral(it, errors, ::parseIndex) } }

        nodes.filterIsInstance<Command>().forEach { cmd ->
            pointFieldsOf(cmd).forEach { checkLiteral(it, errors, ::parsePoint) }
            speedFieldsOf(cmd).forEach { checkLiteral(it, errors, ::parseScrollSpeed) }
        }

        return errors
    }

    private fun checkLiteral(raw: String, errors: MutableList<String>, parse: (String) -> Any) {
        if (raw.contains("\${")) return // unresolved variable — deferred to runtime
        try {
            parse(raw)
        } catch (e: MaestroException.InvalidCommand) {
            errors.add(e.message)
        }
    }

    /**
     * Every model object reachable from [root] (commands, selectors, conditions, nested selectors,
     * composite sub-commands). Walks only `maestro.*` types and guards against cycles.
     */
    private fun reachableNodes(root: Any): List<Any> {
        val nodes = mutableListOf<Any>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

        fun visit(value: Any?) {
            when (value) {
                null, is String, is Number, is Boolean, is Char, is Enum<*> -> return
                is Iterable<*> -> value.forEach(::visit)
                is Map<*, *> -> value.values.forEach(::visit)
                else -> {
                    if (!value.javaClass.name.startsWith("maestro.")) return
                    if (!seen.add(value)) return
                    nodes.add(value)
                    value.javaClass.declaredFields.forEach { field ->
                        if (Modifier.isStatic(field.modifiers) || field.isSynthetic) return@forEach
                        runCatching { field.isAccessible = true; field.get(value) }.getOrNull()?.let(::visit)
                    }
                }
            }
        }

        visit(root)
        return nodes
    }

    // point/speed are plain Strings, indistinguishable by type, so these stay explicit and must be
    // kept in sync with the runtime parsePoint/parseScrollSpeed call sites.
    private fun pointFieldsOf(command: Command): List<String> = when (command) {
        is TapOnPointV2Command -> listOf(command.point)
        is TapOnElementCommand -> listOfNotNull(command.relativePoint)
        is SwipeCommand -> listOfNotNull(command.startRelative, command.endRelative)
        else -> emptyList()
    }

    private fun speedFieldsOf(command: Command): List<String> = when (command) {
        is ScrollUntilVisibleCommand -> listOf(command.scrollDuration)
        else -> emptyList()
    }
}
