package maestro.orchestra.devicecore

import dev.mobile.devicecore.prototype.api.Match
import dev.mobile.devicecore.prototype.api.Selector
import maestro.MaestroException
import maestro.orchestra.ElementSelector

/**
 * Pure translation of a Maestro [ElementSelector] into a device-core [Selector]. Legacy text/id
 * matching is always regex + case-insensitive, which is exactly device-core's [Match.PATTERN] +
 * ignoreCase — the whole rule, emitted for every text selector. Any selector field the four-command
 * vertical does not serve throws [MaestroException.NotImplemented] naming the field; there is no
 * decline-to-legacy path.
 */
object SelectorTranslator {

    fun translate(selector: ElementSelector): Selector {
        rejectUnsupported(selector)

        val hasText = selector.textRegex != null
        val hasId = selector.idRegex != null
        val base: Selector = when {
            hasText && hasId -> throw MaestroException.NotImplemented(
                "device-core has no combined text+id selector"
            )
            hasText -> Selector.Text(selector.textRegex!!, Match.PATTERN, ignoreCase = true)
            hasId -> Selector.Id(selector.idRegex!!)
            else -> throw MaestroException.NotImplemented("selector has neither text nor id")
        }

        val rawIndex = selector.index ?: return base
        val index = rawIndex.toDoubleOrNull()?.toInt()
            ?: throw MaestroException.NotImplemented("selector index='$rawIndex' is not an integer")
        return Selector.Nth(base, index)
    }

    private fun rejectUnsupported(s: ElementSelector) {
        val unsupported = buildList {
            if (s.size != null) add("size")
            if (s.below != null) add("below")
            if (s.above != null) add("above")
            if (s.leftOf != null) add("leftOf")
            if (s.rightOf != null) add("rightOf")
            if (s.containsChild != null) add("containsChild")
            if (s.containsDescendants != null) add("containsDescendants")
            if (s.traits != null) add("traits")
            if (s.enabled != null) add("enabled")
            if (s.selected != null) add("selected")
            if (s.checked != null) add("checked")
            if (s.focused != null) add("focused")
            if (s.childOf != null) add("childOf")
            if (s.css != null) add("css")
        }
        if (unsupported.isNotEmpty()) {
            throw MaestroException.NotImplemented(
                "device-core selector does not implement field(s): ${unsupported.joinToString(", ")}"
            )
        }
    }
}
