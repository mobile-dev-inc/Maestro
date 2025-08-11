package maestro

enum class DeviceOrientation {
    PORTRAIT,
    LANDSCAPE_LEFT,
    LANDSCAPE_RIGHT,
    UPSIDE_DOWN;

    // Return the camelCase representation of the enum name, for example "landscapeLeft"
    val camelCaseName: String
        get() = name.split("_")
            .mapIndexed { index, part ->
                if (index == 0) part.lowercase()
                else part.lowercase().capitalize()
            }
            .joinToString("")

    companion object {
        // Support lookup of enum value by name, ignoring underscores and case. This allow inputs like
        // "LANDSCAPE_LEFT" or "landscapeLeft" to both be matched to the LANDSCAPE_LEFT enum value.
        fun getByName(name: String): DeviceOrientation? {
            return values().find { 
                comparableName(it.name) == comparableName(name)
            }
        }

        private fun comparableName(name: String): String {
            return name.lowercase().replace("_", "")
        }
    }
}
