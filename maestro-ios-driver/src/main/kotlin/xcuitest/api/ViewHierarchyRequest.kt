package xcuitest.api

data class ViewHierarchyRequest(
    val appIds: Set<String>,
    val excludeKeyboardElements: Boolean
)
