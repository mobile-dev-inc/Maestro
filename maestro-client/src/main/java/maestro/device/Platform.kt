package maestro.device

enum class Platform(val description: String) {
    ANDROID("Android"),
    IOS("iOS"),
    TVOS("tvOS"),
    WEB("Web");

    companion object {
        fun fromString(p: String?): Platform? {
            return values().firstOrNull { it.description.lowercase() == p?.lowercase() }
        }
    }
}
