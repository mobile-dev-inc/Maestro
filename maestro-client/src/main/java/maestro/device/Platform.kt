package maestro.device

import kotlinx.serialization.Serializable

@Serializable
enum class Platform(val description: String) {
    ANDROID("Android"),
    IOS("iOS"),
    WEB("Web");

    companion object {
        fun fromString(p: String): Platform {
            return entries.firstOrNull { it.description.equals(p, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "Unknown platform: '$p'. Must be one of: ${entries.joinToString { it.description }}"
                )
        }
    }
}
