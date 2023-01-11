package maestro

enum class SwipeDirection {
    UP,
    DOWN,
    RIGHT,
    LEFT
}

inline fun <reified SwipeDirection : Enum<SwipeDirection>> directionValueOfOrNull(input: String): SwipeDirection? {
    return enumValues<SwipeDirection>().find { it.name == input || it.name.lowercase() == input }
}