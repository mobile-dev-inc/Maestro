package maestro.conformance.behavior.commands

import maestro.conformance.behavior.BehaviorContext

/** Resolve an element's bounds, polling the on-device tree until found or timeout.
 *  Robust to render timing (especially under screen-recording load). */
object Resolve {
    fun bounds(ctx: BehaviorContext, id: String, timeoutMs: Long = 4000): Bounds? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            TreeBounds.find(ctx.driver.contentDescriptor(), id)?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(200)
        }
    }
}
