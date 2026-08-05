package maestro.orchestra.devicecore

import dev.mobile.devicecore.prototype.api.Match
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector

data class RoutedQuery(
    val text: String,
    val match: Match,
    val index: Int?,
    val mode: AssertMode,
)

object DeviceCoreRouting {

    // A literal is anything with no regex metacharacters — device-core matches literal text, not regex.
    private val REGEX_METACHARS = Regex("""[.*+?\[\]{}()^$|\\]""")

    fun route(condition: Condition): RoutedQuery? {
        // Must be a bare standalone assert: no platform guard, no script, exactly one of visible/notVisible.
        if (condition.platform != null || condition.scriptCondition != null) return null
        val visible = condition.visible
        val notVisible = condition.notVisible
        val (selector, mode) = when {
            visible != null && notVisible == null -> visible to AssertMode.VISIBLE
            notVisible != null && visible == null -> notVisible to AssertMode.NOT_VISIBLE
            else -> return null
        }
        val query = toTextQuery(selector) ?: return null
        return query.copy(mode = mode)
    }

    /** Only a plain literal-text selector (optionally + index) is routable. Everything else stays on legacy. */
    private fun toTextQuery(s: ElementSelector): RoutedQuery? {
        val text = s.textRegex ?: return null
        if (s.idRegex != null) return null
        if (REGEX_METACHARS.containsMatchIn(text)) return null
        // Reject any constraint device-core's text strategy can't honor.
        if (s.size != null || s.below != null || s.above != null || s.leftOf != null || s.rightOf != null ||
            s.containsChild != null || s.containsDescendants != null || s.traits != null ||
            s.enabled != null || s.selected != null || s.checked != null || s.focused != null ||
            s.childOf != null || s.css != null
        ) return null
        val index = s.index?.toIntOrNull()
        if (s.index != null && index == null) return null
        return RoutedQuery(text = text, match = Match.EXACT, index = index, mode = AssertMode.VISIBLE)
    }
}
