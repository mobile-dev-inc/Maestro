package conductor

import conductor.UiElement.Companion.toUiElement
import kotlin.math.abs

typealias ElementLookupPredicate = (TreeNode) -> Boolean

object Predicates {

    fun allOf(vararg predicates: ElementLookupPredicate): ElementLookupPredicate = allOf(predicates.toList())

    fun allOf(predicates: List<ElementLookupPredicate>): ElementLookupPredicate = { node ->
        predicates.all { it(node) }
    }

    fun textMatches(text: String): ElementLookupPredicate {
        return {
            it.attributes["text"]?.let { value ->
                text == value
            } ?: false
        }
    }

    fun textMatches(regex: Regex): ElementLookupPredicate {
        return {
            it.attributes["text"]?.let { value ->
                regex.matches(value)
            } ?: false
        }
    }

    fun idMatches(regex: Regex): ElementLookupPredicate {
        return {
            it.attributes["resource-id"]?.let { value ->
                regex.matches(value)
            } ?: false
        }
    }

    fun sizeMatches(
        width: Int? = null,
        height: Int? = null,
        tolerance: Int? = null,
    ): ElementLookupPredicate {
        fun predicate(it: TreeNode): Boolean {
            if (it.attributes["bounds"] == null) {
                return false
            }

            val uiElement = it.toUiElement()

            val finalTolerance = tolerance ?: 0
            if (width != null) {
                if (abs(uiElement.bounds.width - width) > finalTolerance) {
                    return false
                }
            }

            if (height != null) {
                if (abs(uiElement.bounds.height - height) > finalTolerance) {
                    return false
                }
            }

            return true
        }

        return { predicate(it) }
    }
}
