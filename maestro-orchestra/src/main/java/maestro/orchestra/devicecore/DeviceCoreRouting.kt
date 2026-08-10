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

/**
 * Pure routability rules: decides which selectors/conditions device-core's literal-text / literal-id
 * strategies can serve. No device access, no wiring — the backend calls these to decide whether to
 * serve a command or decline it back to legacy. (Adapted from the milestone-4 prototype; the
 * prototype's `fromEnvOrNull`/`System.setProperty` bolt-on wiring is intentionally dropped.)
 */
object DeviceCoreRouting {

    // A literal is anything with no regex metacharacters — device-core matches literal text, not regex.
    private val REGEX_METACHARS = Regex("""[.*+?\[\]{}()^$|\\]""")

    /** A bare standalone assertVisible/assertNotVisible on a literal-text selector, or null. */
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
        return toTextQuery(selector, mode)
    }

    /** Only a plain literal-text selector (optionally + index) is routable. Everything else stays on legacy. */
    private fun toTextQuery(s: ElementSelector, mode: AssertMode): RoutedQuery? {
        val text = s.textRegex ?: return null
        if (s.idRegex != null) return null
        if (REGEX_METACHARS.containsMatchIn(text)) return null
        if (hasUnservableConstraint(s)) return null
        val index = s.index?.toIntOrNull()
        if (s.index != null && index == null) return null
        return RoutedQuery(text = text, match = Match.EXACT, index = index, mode = mode)
    }

    /**
     * The literal resource-id for a plain id-only tap selector, or null. device-core's `getById` takes
     * a literal id; a selector carrying text, regex metacharacters, an index, or any relative/trait/
     * state constraint is not something the id strategy can honor, so it declines back to legacy.
     */
    fun routeIdTap(selector: ElementSelector): String? {
        val id = selector.idRegex ?: return null
        if (selector.textRegex != null) return null
        if (REGEX_METACHARS.containsMatchIn(id)) return null
        if (selector.index != null) return null
        if (hasUnservableConstraint(selector)) return null
        return id
    }

    // Any relative/size/trait/state/childOf/css constraint is outside device-core's literal strategies.
    private fun hasUnservableConstraint(s: ElementSelector): Boolean =
        s.size != null || s.below != null || s.above != null || s.leftOf != null || s.rightOf != null ||
            s.containsChild != null || s.containsDescendants != null || s.traits != null ||
            s.enabled != null || s.selected != null || s.checked != null || s.focused != null ||
            s.childOf != null || s.css != null
}
