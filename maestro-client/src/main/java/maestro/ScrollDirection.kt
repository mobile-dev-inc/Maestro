package maestro

enum class ScrollDirection {
    UP,
    DOWN,
    RIGHT,
    LEFT
}

fun ScrollDirection.toSwipeDirection(): SwipeDirection = when (this) {
    ScrollDirection.DOWN -> SwipeDirection.UP
    ScrollDirection.UP -> SwipeDirection.DOWN
    ScrollDirection.LEFT -> SwipeDirection.RIGHT
    ScrollDirection.RIGHT -> SwipeDirection.LEFT
}