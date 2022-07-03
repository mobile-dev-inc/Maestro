package conductor.orchestra

data class ElementSelector(
    val textRegex: String? = null,
    val idRegex: String? = null,
    val size: SizeSelector? = null,
    val optional: Boolean = false,
) {

    data class SizeSelector(
        val width: Int? = null,
        val height: Int? = null,
        val tolerance: Int? = null,
    )

    fun description(): String {
        val descriptions = mutableListOf<String>()

        textRegex?.let {
            descriptions.add("Text matching regex: \"$it\"")
        }

        idRegex?.let {
            descriptions.add("ID matching regex: \"$it\"")
        }

        size?.let {
            descriptions.add("Size: ${it.width}x${it.height} (tolerance: ${it.tolerance})")
        }

        val combined = descriptions.joinToString(", ")

        return if (optional) {
            "(Optional) $combined"
        } else {
            combined
        }
    }

}
