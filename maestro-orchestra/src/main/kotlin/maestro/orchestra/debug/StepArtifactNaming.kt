package maestro.orchestra.debug

import maestro.orchestra.ClearStateCommand
import maestro.orchestra.CompositeCommand
import maestro.orchestra.InputTextCommand
import maestro.orchestra.KillAppCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.OpenLinkCommand
import maestro.orchestra.PressKeyCommand
import maestro.orchestra.StopAppCommand
import maestro.orchestra.TapOnPointCommand
import maestro.orchestra.TapOnPointV2Command

/**
 * File stem for a step's artifacts: `step-<NNN>[-<slug>]`, where NNN is the 1-based
 * sequence number (min width 3) and the optional slug names the command type plus one
 * salient argument. Screenshot and hierarchy for a step share this stem so the viewer's
 * hierarchy-over-screenshot overlay keeps pairing them.
 */
internal object StepArtifactNaming {

    private const val MIN_INDEX_WIDTH = 3
    private const val MAX_SLUG_LENGTH = 40
    private val ILLEGAL = Regex("""[<>:"|?*\p{Cntrl}]""")
    private val PATH_SEP_OR_WHITESPACE = Regex("""[/\\\s]+""")
    private val SEPARATOR_RUN = Regex("""([-_])\1+""")

    /** No on-screen action: composite parents, non-visible leaves, or an unidentifiable command. */
    fun isNoOp(command: MaestroCommand?): Boolean {
        val leaf = command?.asCommand() ?: return true
        return leaf is CompositeCommand || !leaf.visible()
    }

    fun stem(sequenceNumber: Int, command: MaestroCommand?): String {
        val index = (sequenceNumber + 1).toString().padStart(MIN_INDEX_WIDTH, '0')
        val slug = command?.let(::slug)
        return if (slug.isNullOrEmpty()) "step-$index" else "step-$index-$slug"
    }

    private fun slug(command: MaestroCommand): String? {
        val type = typeToken(command) ?: return null
        val arg = argToken(command)?.let(::sanitize)?.takeIf { it.isNotEmpty() }
        val slug = if (arg == null) type else "$type-$arg"
        return slug.take(MAX_SLUG_LENGTH).trim('-', '_', '.')
    }

    private fun typeToken(command: MaestroCommand): String? =
        command.asCommand()?.let { it::class.simpleName }
            ?.removeSuffix("Command")
            ?.replaceFirstChar(Char::lowercaseChar)

    private fun argToken(command: MaestroCommand): String? {
        command.elementSelector()?.let { selector ->
            return selector.textRegex ?: selector.idRegex ?: selector.description().ifEmpty { null }
        }
        return when (val leaf = command.asCommand()) {
            is TapOnPointV2Command -> leaf.point
            is TapOnPointCommand -> "${leaf.x},${leaf.y}"
            is InputTextCommand -> leaf.text
            is LaunchAppCommand -> leaf.appId
            is StopAppCommand -> leaf.appId
            is KillAppCommand -> leaf.appId
            is ClearStateCommand -> leaf.appId
            is OpenLinkCommand -> leaf.link
            is PressKeyCommand -> leaf.code.name
            else -> null
        }
    }

    private fun sanitize(raw: String): String =
        raw.replace(ILLEGAL, "")
            .replace(PATH_SEP_OR_WHITESPACE, "_")
            .replace(SEPARATOR_RUN, "$1")
            .trim('-', '_', '.', ' ')
}
