package maestro.utils

import maestro.Point
import org.openqa.selenium.interactions.PointerInput
import org.openqa.selenium.interactions.Sequence
import org.openqa.selenium.remote.RemoteWebDriver
import java.time.Duration

/**
 * Duration to hold at the start position before initiating the drag movement.
 * This allows the OS/browser to recognize the gesture as a drag rather than a tap.
 */
private const val DRAG_LONG_PRESS_DURATION_MS = 1000L

/**
 * Shared gesture utilities for Selenium-based web drivers.
 */
object SeleniumGestures {

    /**
     * Performs a drag gesture from start to end point.
     * Includes a long-press phase at the start to initiate the drag.
     *
     * @param driver The Selenium RemoteWebDriver to perform the gesture on
     * @param start The starting point of the drag
     * @param end The ending point of the drag
     * @param durationMs Total duration of the drag gesture (including long-press phase)
     */
    fun performDrag(driver: RemoteWebDriver, start: Point, end: Point, durationMs: Long) {
        val finger = PointerInput(PointerInput.Kind.TOUCH, "finger")
        val drag = Sequence(finger, 1)

        // Move to start position
        drag.addAction(
            finger.createPointerMove(
                Duration.ofMillis(0),
                PointerInput.Origin.viewport(),
                start.x,
                start.y
            )
        )

        // Press and hold
        drag.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()))

        // Long press at start position before dragging
        drag.addAction(
            finger.createPointerMove(
                Duration.ofMillis(DRAG_LONG_PRESS_DURATION_MS),
                PointerInput.Origin.viewport(),
                start.x,
                start.y
            )
        )

        // Drag to end position
        val moveDuration = (durationMs - DRAG_LONG_PRESS_DURATION_MS).coerceAtLeast(0)
        drag.addAction(
            finger.createPointerMove(
                Duration.ofMillis(moveDuration),
                PointerInput.Origin.viewport(),
                end.x,
                end.y
            )
        )

        // Release
        drag.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()))

        driver.perform(listOf(drag))
    }
}
