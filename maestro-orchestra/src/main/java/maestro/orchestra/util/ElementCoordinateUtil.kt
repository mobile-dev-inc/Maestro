package maestro.orchestra.util

import maestro.MaestroException
import maestro.Point
import maestro.UiElement

/**
 * Calculates the absolute screen coordinates for a point relative to an element's bounds.
 * 
 * @param element The UI element to calculate coordinates relative to
 * @param point The relative point as a string (e.g., "50%, 90%" or "25, 40")
 * @return The absolute screen coordinates as a Point
 * @throws MaestroException.InvalidCommand if the point is invalid
 */
internal fun calculateElementRelativePoint(element: UiElement, point: String): Point {
    val bounds = element.bounds
    
    return if (point.contains("%")) {
        // Percentage-based coordinates within element bounds
        val (percentX, percentY) = point
            .replace("%", "")
            .split(",")
            .map { it.trim().toInt() }

        if (percentX !in 0..100 || percentY !in 0..100) {
            throw MaestroException.InvalidCommand("Invalid element-relative point: $point. Percentages must be between 0 and 100.")
        }

        val x = bounds.x + (bounds.width * percentX / 100)
        val y = bounds.y + (bounds.height * percentY / 100)
        Point(x, y)
    } else {
        // Absolute coordinates within element bounds
        val (x, y) = point.split(",")
            .map { it.trim().toInt() }

        if (x < 0 || y < 0 || x >= bounds.width || y >= bounds.height) {
            throw MaestroException.InvalidCommand("Invalid element-relative point: $point. Coordinates must be within element bounds (0,0) to (${bounds.width-1},${bounds.height-1}).")
        }

        Point(bounds.x + x, bounds.y + y)
    }
}
