package maestro.orchestra.util

import maestro.DeviceInfo
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
    return resolveRelativePoint(
        point = point,
        originX = bounds.x,
        originY = bounds.y,
        width = bounds.width,
        height = bounds.height,
        regionLabel = "element",
    )
}

/**
 * Calculates the absolute screen coordinates for a point relative to the device screen.
 *
 * @param point The point as a string (e.g., "50%, 90%" or "100, 400")
 * @param deviceInfo The device info used to resolve percentage-based coordinates
 * @return The absolute screen coordinates as a Point
 * @throws MaestroException.InvalidCommand if the point is invalid
 */
internal fun calculateScreenRelativePoint(point: String, deviceInfo: DeviceInfo): Point {
    return resolveRelativePoint(
        point = point,
        originX = 0,
        originY = 0,
        width = deviceInfo.widthGrid,
        height = deviceInfo.heightGrid,
        regionLabel = "screen",
    )
}

/**
 * Resolves a `"x%, y%"` (percentage) or `"x, y"` (absolute) point within a rectangular region
 * to absolute screen coordinates. The region is defined by its top-left origin and its size;
 * an element passes its bounds, the whole screen passes `(0, 0)` and the device dimensions.
 *
 * @param regionLabel Used in error messages to name the region (e.g. "element", "screen").
 * @throws MaestroException.InvalidCommand if the point is out of range for the region.
 */
private fun resolveRelativePoint(
    point: String,
    originX: Int,
    originY: Int,
    width: Int,
    height: Int,
    regionLabel: String,
): Point {
    return if (point.contains("%")) {
        // Percentage-based coordinates within the region
        val (percentX, percentY) = point
            .replace("%", "")
            .split(",")
            .map { it.trim().toInt() }

        if (percentX !in 0..100 || percentY !in 0..100) {
            throw MaestroException.InvalidCommand("Invalid $regionLabel-relative point: $point. Percentages must be between 0 and 100.")
        }

        Point(
            x = originX + (width * percentX / 100),
            y = originY + (height * percentY / 100),
        )
    } else {
        // Absolute coordinates within the region
        val (x, y) = point.split(",")
            .map { it.trim().toInt() }

        if (x < 0 || y < 0 || x >= width || y >= height) {
            throw MaestroException.InvalidCommand("Invalid $regionLabel-relative point: $point. Coordinates must be within $regionLabel bounds (0,0) to (${width - 1},${height - 1}).")
        }

        Point(originX + x, originY + y)
    }
}
