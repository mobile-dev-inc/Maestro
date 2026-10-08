package maestro.orchestra.util

import maestro.MaestroException
import maestro.orchestra.Command
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

enum class NumericFieldKind { INDEX, POINT, SCROLL_SPEED }

/** Marks a numeric `String` field so [NumericFields.staticErrors] validates it generically. */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class NumericField(val kind: NumericFieldKind)

/**
 * Parses the numeric string fields flows can set from JS variables (`index`, `point`, scroll `speed`).
 * Raises a clear [MaestroException.InvalidNumericFieldValue] instead of a raw [NumberFormatException],
 * and backs the static [maestro.orchestra.workspace.WorkspaceValidator] check with the same rules.
 * These are syntax errors, so they fail the command hard and are never downgraded by `optional`.
 */
object NumericFields {

    private const val MIN_SCROLL_SPEED = 0L
    private const val MAX_SCROLL_SPEED = 100L

    fun parseIndex(raw: String): Int {
        val value = raw.toDoubleOrNull()
        // `toDoubleOrNull` accepts "NaN"/"Infinity"; reject them so we never tap a bogus element
        // (NaN would silently truncate to 0 and tap the first match).
        if (value == null || value.isNaN() || value.isInfinite()) {
            throw MaestroException.InvalidNumericFieldValue(
                "Invalid index value \"$raw\": index must be a whole number. " +
                    "If it comes from a variable, make sure the variable resolves to a number."
            )
        }
        return value.toInt()
    }

    /** Parses `x,y` into two ints. `%` is stripped so callers keep their own range checks. */
    fun parsePoint(raw: String): Pair<Int, Int> {
        val coordinates = raw.replace("%", "").split(",").map { it.trim().toIntOrNull() ?: throw invalidPoint(raw) }
        if (coordinates.size < 2) throw invalidPoint(raw)
        return coordinates[0] to coordinates[1]
    }

    fun parseScrollSpeed(raw: String): Long {
        val value = raw.toLongOrNull()
        if (value == null || value < MIN_SCROLL_SPEED || value > MAX_SCROLL_SPEED) {
            throw MaestroException.InvalidNumericFieldValue(
                "Invalid speed value \"$raw\": speed must be a number between " +
                    "$MIN_SCROLL_SPEED and $MAX_SCROLL_SPEED. " +
                    "If it comes from a variable, make sure the variable resolves to a number."
            )
        }
        return value
    }

    private fun invalidPoint(raw: String) = MaestroException.InvalidNumericFieldValue(
        "Invalid point value \"$raw\": expected two whole-number coordinates like \"x,y\" or \"x%,y%\". " +
            "If they come from variables, make sure the variables resolve to numbers."
    )

    /**
     * Static point check: [parsePoint] plus the runtime's 0..100 percent range. For absolute pixels only
     * the lower bound is device-independent, so we reject negatives here; the upper bound stays runtime-only.
     */
    private fun validatePointLiteral(raw: String): Pair<Int, Int> {
        val (x, y) = parsePoint(raw)
        if (raw.contains("%")) {
            if (x !in 0..100 || y !in 0..100) {
                throw MaestroException.InvalidNumericFieldValue(
                    "Invalid point value \"$raw\": percentages must be between 0 and 100."
                )
            }
        } else if (x < 0 || y < 0) {
            throw MaestroException.InvalidNumericFieldValue(
                "Invalid point value \"$raw\": coordinates must not be negative."
            )
        }
        return x to y
    }

    /** Validates literal numeric fields up front; `${...}` values are deferred to runtime. */
    fun staticErrors(command: Command): List<String> {
        val errors = mutableListOf<String>()
        numericFieldsOf(command).forEach { (kind, raw) -> checkLiteral(raw, errors, parserFor(kind)) }
        return errors
    }

    private fun parserFor(kind: NumericFieldKind): (String) -> Any = when (kind) {
        NumericFieldKind.INDEX -> ::parseIndex
        NumericFieldKind.POINT -> ::validatePointLiteral
        NumericFieldKind.SCROLL_SPEED -> ::parseScrollSpeed
    }

    private fun checkLiteral(raw: String, errors: MutableList<String>, parse: (String) -> Any) {
        if (raw.contains("\${")) return // unresolved variable — deferred to runtime
        try {
            parse(raw)
        } catch (e: MaestroException.InvalidCommand) {
            errors.add(e.message)
        }
    }

    /** Every [NumericField]-annotated string reachable from [root], with its kind. Cycle-guarded. */
    private fun numericFieldsOf(root: Any): List<Pair<NumericFieldKind, String>> {
        val hits = mutableListOf<Pair<NumericFieldKind, String>>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

        fun visit(value: Any?) {
            when (value) {
                null, is String, is Number, is Boolean, is Char, is Enum<*> -> return
                is Iterable<*> -> value.forEach(::visit)
                is Map<*, *> -> value.values.forEach(::visit)
                else -> {
                    if (!value.javaClass.name.startsWith("maestro.")) return
                    if (!seen.add(value)) return
                    value.javaClass.declaredFields.forEach { field ->
                        if (Modifier.isStatic(field.modifiers) || field.isSynthetic) return@forEach
                        val fieldValue = runCatching { field.isAccessible = true; field.get(value) }.getOrNull()
                        field.getAnnotation(NumericField::class.java)?.let { annotation ->
                            (fieldValue as? String)?.let { hits.add(annotation.kind to it) }
                        }
                        fieldValue?.let(::visit)
                    }
                }
            }
        }

        visit(root)
        return hits
    }
}
