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
    VOLUME_DOWN("Volume Down"),
    REMOTE_UP("Remote Dpad Up"),
    REMOTE_DOWN("Remote Dpad Down"),
    REMOTE_LEFT("Remote Dpad Left"),
    REMOTE_RIGHT("Remote Dpad Right"),
    REMOTE_CENTER("Remote Dpad Center"),
    REMOTE_PLAY_PAUSE("Remote Media Play Pause"),
    REMOTE_STOP("Remote Media Stop"),
    REMOTE_NEXT("Remote Media Next"),
    REMOTE_PREVIOUS("Remote Media Previous"),
    REMOTE_REWIND("Remote Media Rewind"),
    REMOTE_FAST_FORWARD("Remote Media Fast Forward"),
    ESCAPE("Escape"),
    POWER("Power"),
    TAB("Tab"),
    REMOTE_SYSTEM_NAVIGATION_UP("Remote System Navigation Up"),
    REMOTE_SYSTEM_NAVIGATION_DOWN("Remote System Navigation Down"),
    REMOTE_BUTTON_A("Remote Button A"),
    REMOTE_BUTTON_B("Remote Button B"),
    REMOTE_MENU("Remote Menu"),
    TV_INPUT("TV Input"),
    TV_INPUT_HDMI_1("TV Input HDMI 1"),
    TV_INPUT_HDMI_2("TV Input HDMI 2"),
    TV_INPUT_HDMI_3("TV Input HDMI 3");

    companion object {
        fun getByName(name: String): KeyCode? {
            val normalizedName = name.lowercase().replace("_", " ").replace("-", " ")
            
            // First try to match by description (e.g., "Volume Down")
            values().find { it.description.lowercase() == normalizedName }?.let { return it }
            
            // Then try to match by enum name (e.g., "VOLUME_DOWN")
            val enumName = name.uppercase().replace(" ", "_").replace("-", "_")
            return try {
                valueOf(enumName)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }

}
