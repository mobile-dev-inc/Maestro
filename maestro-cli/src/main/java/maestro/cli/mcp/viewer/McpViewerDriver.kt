package maestro.cli.mcp.viewer

import maestro.Driver
import maestro.Point
import maestro.SwipeDirection

// Wraps a Driver to publish the spatial events the viewer overlays consume:
// tap (dot), swipe (arrow), inputText (text length pill). Every other Driver
// method passes through untouched — they are not visualized.
internal class McpViewerDriver(
    private val delegate: Driver,
    private val platform: String,
) : Driver by delegate {

    private val screenDimensions: Pair<Int, Int> by lazy {
        val info = delegate.deviceInfo()
        info.widthGrid to info.heightGrid
    }

    override fun tap(point: Point) = emit({ status ->
        ViewerEvent.Tap(status = status, point = point.normalize())
    }) {
        delegate.tap(point)
    }

    override fun swipe(start: Point, end: Point, durationMs: Long) = emit({ status ->
        ViewerEvent.Swipe(
            status = status,
            start = start.normalize(),
            end = end.normalize(),
            durationMs = durationMs,
        )
    }) {
        delegate.swipe(start, end, durationMs)
    }

    override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) {
        val (start, end) = swipePoints(swipeDirection)
        emit({ status ->
            ViewerEvent.Swipe(status, start.normalize(), end.normalize(), durationMs)
        }) {
            delegate.swipe(swipeDirection, durationMs)
        }
    }

    override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) {
        val end = swipeEndPoint(elementPoint, direction)
        emit({ status ->
            ViewerEvent.Swipe(status, elementPoint.normalize(), end.normalize(), durationMs)
        }) {
            delegate.swipe(elementPoint, direction, durationMs)
        }
    }

    override fun inputText(text: String) = emit({ status ->
        ViewerEvent.InputText(status = status, textLength = text.length)
    }) {
        delegate.inputText(text)
    }

    private fun <T> emit(buildEvent: (DriverStatus) -> ViewerEvent, call: () -> T): T {
        McpViewerEvents.publish(buildEvent(DriverStatus.STARTED))
        return try {
            val result = call()
            McpViewerEvents.publish(buildEvent(DriverStatus.COMPLETED))
            result
        } catch (error: Throwable) {
            McpViewerEvents.publish(buildEvent(DriverStatus.FAILED))
            throw error
        }
    }

    private fun swipePoints(direction: SwipeDirection): Pair<Point, Point> {
        val (width, height) = screenDimensions
        val upStartY = if (platform == "android") 0.5.asPercentOf(height) else 0.9.asPercentOf(height)
        return when (direction) {
            SwipeDirection.UP -> Point(0.5.asPercentOf(width), upStartY) to
                Point(0.5.asPercentOf(width), 0.1.asPercentOf(height))
            SwipeDirection.DOWN -> Point(0.5.asPercentOf(width), 0.2.asPercentOf(height)) to
                Point(0.5.asPercentOf(width), 0.9.asPercentOf(height))
            SwipeDirection.RIGHT -> Point(0.1.asPercentOf(width), 0.5.asPercentOf(height)) to
                Point(0.9.asPercentOf(width), 0.5.asPercentOf(height))
            SwipeDirection.LEFT -> Point(0.9.asPercentOf(width), 0.5.asPercentOf(height)) to
                Point(0.1.asPercentOf(width), 0.5.asPercentOf(height))
        }
    }

    private fun swipeEndPoint(start: Point, direction: SwipeDirection): Point {
        val (width, height) = screenDimensions
        return when (direction) {
            SwipeDirection.UP -> Point(start.x, 0.1.asPercentOf(height))
            SwipeDirection.DOWN -> Point(start.x, 0.9.asPercentOf(height))
            SwipeDirection.RIGHT -> Point(0.9.asPercentOf(width), start.y)
            SwipeDirection.LEFT -> Point(0.1.asPercentOf(width), start.y)
        }
    }

    private fun Double.asPercentOf(total: Int): Int = (this * total).toInt()

    private fun Point.normalize(): Point2D {
        val (width, height) = screenDimensions
        return Point2D(x.toDouble() / width, y.toDouble() / height)
    }
}
