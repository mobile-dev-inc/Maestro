package maestro.device

enum class Platform(val description: String) {
    ANDROID("Android"),
    IOS("iOS"),
    WEB("Web");

    companion object {
        fun fromString(p: String): Platform {
            return entries.first { it.description.equals(p, ignoreCase = true) }
        }
    }
}
