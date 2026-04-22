package maestro.cli.mcp.hierarchy

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import maestro.Filters
import maestro.TreeNode

// Cross-checks `text:` selectors in a flow's YAML against the latest
// hierarchy snapshot. Matching delegates to Filters.textMatches so the
// validator agrees with the runner — same attributes (text / hintText /
// accessibilityText), same full-string regex semantics.
object SelectorValidator {

    sealed interface Result {
        data object Ok : Result
        data class Miss(val findings: List<Finding>) : Result
    }

    data class Finding(
        val selector: String,
        val suggestions: List<String>,
    )

    fun validate(yaml: String, snapshot: HierarchySnapshotStore.Snapshot): Result {
        val selectors = extractTextSelectors(yaml).distinct()
        if (selectors.isEmpty()) return Result.Ok

        val nodes = snapshot.root.aggregate()
        val findings = selectors
            .filterNot { selectorMatches(it, nodes) }
            .map { Finding(selector = it, suggestions = suggestFor(it, nodes)) }

        return if (findings.isEmpty()) Result.Ok else Result.Miss(findings)
    }

    private fun extractTextSelectors(yaml: String): List<String> {
        val root = try {
            YAML.readTree(yaml)
        } catch (e: Exception) {
            // Malformed YAML will surface a better error from YamlCommandReader
            // downstream — don't second-guess it here.
            return emptyList()
        }
        val out = mutableListOf<String>()
        collect(root, out)
        return out
    }

    private fun collect(node: JsonNode?, out: MutableList<String>) {
        if (node == null || node.isNull) return
        when {
            node.isObject -> node.fields().forEach { (key, value) ->
                if (key == "text" && value.isTextual) {
                    value.asText().takeIf { it.isNotBlank() }?.let(out::add)
                } else {
                    collect(value, out)
                }
            }
            node.isArray -> node.forEach { collect(it, out) }
        }
    }

    private fun selectorMatches(selector: String, nodes: List<TreeNode>): Boolean {
        val regex = try {
            Regex(selector)
        } catch (e: Exception) {
            // Selector isn't a valid regex. Filters.textMatches already handles
            // this via its `regex.pattern == value` literal-equality branch, but
            // we can't construct a Regex to hand it — fall back to a plain
            // equality check on the same three attributes.
            return nodes.any { node ->
                TEXT_ATTRIBUTES.any { attr -> node.attributes[attr] == selector }
            }
        }
        return Filters.textMatches(regex).invoke(nodes).isNotEmpty()
    }

    private fun suggestFor(selector: String, nodes: List<TreeNode>): List<String> {
        val candidates = linkedSetOf<String>()
        nodes.forEach { node ->
            TEXT_ATTRIBUTES.forEach { attr ->
                node.attributes[attr]?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
            }
        }
        if (candidates.isEmpty()) return emptyList()

        val lowered = selector.lowercase()
        val partial = candidates
            .filter { it.lowercase().contains(lowered) || lowered.contains(it.lowercase()) }
            .take(MAX_SUGGESTIONS)

        if (partial.isNotEmpty()) return partial

        return candidates.take(FALLBACK_PEEK).toList()
    }

    // Mirrors the attributes Filters.textMatches inspects. Keep in sync with
    // maestro-client/Filters.kt::textMatches.
    private val TEXT_ATTRIBUTES = listOf("text", "hintText", "accessibilityText")

    private const val MAX_SUGGESTIONS = 5
    private const val FALLBACK_PEEK = 10

    private val YAML = YAMLMapper()
}
