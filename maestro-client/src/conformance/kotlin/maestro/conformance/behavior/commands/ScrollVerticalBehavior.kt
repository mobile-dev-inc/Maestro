package maestro.conformance.behavior.commands

import maestro.conformance.behavior.*

class ScrollVerticalBehavior : CommandBehavior {
    override val name = "scrollVertical"
    override val coverage = Coverage.FRAMEWORK_SENSITIVE

    override fun run(ctx: BehaviorContext): CommandOutcome {
        val node = ctx.driver.contentDescriptor()
        TreeBounds.find(node, "scroll_container")
            ?: return fail("scroll_container not found in hierarchy")

        val w = ctx.markWatermark()
        ctx.driver.scrollVertical()

        val events = Poll.forEvents(ctx, w, "SCROLL")
        val ev = events.firstOrNull { it.payload["axis"] == "Y" }
        val expected = mapOf(
            "event" to "SCROLL",
            "axis" to "Y",
            "toOffset_gt_fromOffset" to true,
        )
        return if (ev != null) {
            val fromOffset = (ev.payload["fromOffset"] as? Number)?.toInt() ?: 0
            val toOffset = (ev.payload["toOffset"] as? Number)?.toInt() ?: 0
            if (toOffset > fromOffset) {
                CommandOutcome(
                    Verdict.pass(), OracleKind.APP_EVENT, expected,
                    mapOf("axis" to ev.payload["axis"], "fromOffset" to fromOffset, "toOffset" to toOffset),
                    emptyMap(),
                )
            } else {
                CommandOutcome(
                    Verdict.fail("toOffset=$toOffset is not greater than fromOffset=$fromOffset"),
                    OracleKind.APP_EVENT, expected,
                    mapOf("axis" to ev.payload["axis"], "fromOffset" to fromOffset, "toOffset" to toOffset),
                    emptyMap(),
                )
            }
        } else {
            CommandOutcome(
                Verdict.fail("no SCROLL with axis=Y past watermark"),
                OracleKind.APP_EVENT, expected,
                mapOf("events" to events.map { it.payload }),
                emptyMap(),
            )
        }
    }

    private fun fail(reason: String) = CommandOutcome(
        Verdict.fail(reason), OracleKind.APP_EVENT, emptyMap(), emptyMap(), emptyMap()
    )
}
