package maestro

data class FindElementResult(val element: UiElement, val hierarchy: ViewHierarchy)

/**
 * What a lookup ended with: the element, or null when nothing matched in time, and either way the
 * last hierarchy that was searched.
 */
data class ElementLookup(val found: FindElementResult?, val lastHierarchy: ViewHierarchy)
