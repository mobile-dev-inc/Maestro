package maestro

enum class KeyCode(
    val description: String,
) {

    ENTER("Enter"),
    BACKSPACE("Backspace"),
    BACK("Back"),
    HOME("Home"),
    LOCK("Lock"),
    VOLUME_UP("Volume Up"),
    VOLUME_DOWN("Volume Down");

    companion object {
        fun getByName(name: String): KeyCode? {
            val lowercaseName = name.lowercase()
            return values().find { it.description.lowercase() == lowercaseName }
        }
    }

}