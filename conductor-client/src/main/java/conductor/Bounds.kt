package conductor

data class Bounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
) {

    fun center(): Point {
        return Point(
            x = x + width / 2,
            y = y + height / 2
        )
    }
}
