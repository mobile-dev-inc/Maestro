package maestro.conformance.behavior.commands

import maestro.Point
import maestro.TreeNode
import maestro.conformance.behavior.BehaviorContext
import maestro.conformance.behavior.CommandBehavior
import maestro.conformance.behavior.CommandOutcome
import maestro.conformance.behavior.Coverage
import maestro.conformance.behavior.OracleKind
import maestro.conformance.behavior.Verdict

/**
 * Regression probe for webview content visibility in the accessibility hierarchy.
 *
 * WebViewScreen renders a payment-page-shaped document (form panel with a plain submit
 * button + sibling static content). Under a filtered AXMode (Chromium serves non-screen-
 * reader clients a reduced tree) the form subtree — including the Confirm button — can be
 * absent from the projection while sibling static text serializes; resolving "Confirm"
 * then fails exactly like the production incident (Element not found on visible content).
 * With the complete projection (a "complex"-profile accessibility service enabled), the
 * button resolves and the tap fires the page's JS bridge — the consuming-element oracle.
 *
 * The diagnostic payload reports which probe texts were present in the tree, so a failure
 * shows the partial-projection signature (static sibling present, form subtree absent)
 * rather than a bare not-found.
 */
class WebViewConfirmBehavior : CommandBehavior {
    override val name = "webViewConfirm"
    override val coverage = Coverage.FRAMEWORK_SENSITIVE

    override fun run(ctx: BehaviorContext): CommandOutcome {
        // Let the page load: WEB_PAGE_LOADED is emitted by the fixture's WebViewClient.
        // Resolve polls the tree anyway, so a bounded extra settle is enough.
        Thread.sleep(500)

        val expected = mapOf("event" to "WEB_CONFIRM_TAP", "target" to "confirm")
        // Webview a11y bounds lag layout (Chromium throttles location serialization ~500ms):
        // an early read returns pre-offset page-space bounds. Poll until two consecutive
        // reads agree before trusting the rect.
        var bounds = Resolve.bounds(ctx, "Confirm", timeoutMs = 8000)
        if (bounds != null) {
            var stable = false
            var tries = 0
            while (!stable && tries < 6) {
                Thread.sleep(700)
                tries++
                val next = Resolve.bounds(ctx, "Confirm", timeoutMs = 2000) ?: continue
                stable = next == bounds
                bounds = next
            }
        }
        if (bounds == null) {
            return CommandOutcome(
                Verdict.fail(
                    "'Confirm' not found in accessibility hierarchy " +
                        "(webview form subtree absent — filtered AXMode signature)"
                ),
                OracleKind.APP_EVENT,
                expected,
                mapOf("probeTextsInTree" to probePresence(ctx)),
                emptyMap(),
            )
        }

        val w = ctx.markWatermark()
        ctx.driver.tap(Point(bounds.centerX, bounds.centerY))

        val events = Poll.forEvents(ctx, w, "WEB_CONFIRM_TAP")
        val hit = events.firstOrNull { it.payload["target"] == "confirm" }
        return if (hit != null) {
            CommandOutcome(
                Verdict.pass(), OracleKind.APP_EVENT, expected, hit.payload,
                mapOf("point" to listOf(bounds.centerX, bounds.centerY)),
            )
        } else {
            CommandOutcome(
                Verdict.fail("'Confirm' resolved and tapped but no WEB_CONFIRM_TAP past watermark"),
                OracleKind.APP_EVENT, expected,
                mapOf(
                    "events" to events.map { it.payload },
                    "probeTextsInTree" to probePresence(ctx),
                ),
                mapOf("point" to listOf(bounds.centerX, bounds.centerY)),
            )
        }
    }

    /** Which of the page's landmark texts made it into the projection — the partial-tree signature. */
    private fun probePresence(ctx: BehaviorContext): Map<String, Boolean> {
        val texts = HashSet<String>()
        fun collect(node: TreeNode) {
            node.attributes["text"]?.let { texts += it }
            node.attributes["accessibilityText"]?.let { texts += it }
            node.children.forEach { collect(it) }
        }
        runCatching { collect(ctx.driver.contentDescriptor()) }
        return listOf(
            "Account Selection", "Savings Account", "Confirm", "Cancel",
            "Transaction Information", "Payments Network Malaysia",
        ).associateWith { probe -> texts.any { it == probe || it.contains(probe) } }
    }
}
