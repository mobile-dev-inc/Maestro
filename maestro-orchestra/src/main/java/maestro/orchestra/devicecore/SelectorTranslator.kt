package maestro.orchestra.devicecore

import dev.mobile.devicecore.prototype.api.Match
import dev.mobile.devicecore.prototype.api.Relation
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

        val related = relate(base, selector)

        val rawIndex = selector.index ?: return related
        val index = rawIndex.toDoubleOrNull()?.toInt()
            ?: throw MaestroException.NotImplemented("selector index='$rawIndex' is not an integer")
        return Selector.Nth(related, index)
    }

    /**
     * Wrap [base] in device-core's [Selector.Relative] for the one relational field set on [s], if
     * any. device-core's Relative carries a SINGLE relation, so more than one directional field on
     * one selector is walled rather than guessed — an AND of two relations is not what Relative
     * means. The anchor is translated by the same rule, so it may itself be text/id/nth/relational.
     */
    private fun relate(base: Selector, s: ElementSelector): Selector {
        val relations = buildList {
            s.above?.let { add(Relation.ABOVE to it) }
            s.below?.let { add(Relation.BELOW to it) }
            s.leftOf?.let { add(Relation.LEFT_OF to it) }
            s.rightOf?.let { add(Relation.RIGHT_OF to it) }
        }
        return when (relations.size) {
            0 -> base
            1 -> relations.single().let { (relation, anchor) ->
                Selector.Relative(base, translate(anchor), relation)
            }
            else -> throw MaestroException.NotImplemented(
                "device-core selector does not implement combined relational fields: " +
                    relations.joinToString(", ") { it.first.name }
            )
        }
    }

    private fun rejectUnsupported(s: ElementSelector) {
        val unsupported = buildList {
            if (s.size != null) add("size")
            // below / above / leftOf / rightOf translate to Selector.Relative — see relate()
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
