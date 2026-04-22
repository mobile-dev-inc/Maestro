package maestro.cli.mcp.hierarchy

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import maestro.Filters
import maestro.TreeNode

object SelectorValidator {

    sealed interface Result {
        data object Ok : Result
        data class Miss(val findings: List<Finding>) : Result {
            init { require(findings.isNotEmpty()) }
        }
    }

    data class Finding(val selector: String, val suggestions: List<String>)

    fun validate(yaml: String, snapshot: HierarchySnapshotStore.Snapshot): Result {
        val selectors = textSelectorsIn(yaml)
            .filterNot { ENV_INTERPOLATION.containsMatchIn(it) }
            .distinct()
        if (selectors.isEmpty()) return Result.Ok

        val nodes = snapshot.root.aggregate()
        val findings = selectors
            .filterNot { it.matchesAny(nodes) }
            .map { Finding(it, it.suggestionsFrom(nodes)) }

        return if (findings.isEmpty()) Result.Ok else Result.Miss(findings)
    }

    private fun textSelectorsIn(yaml: String): List<String> {
        val root = try { YAML.readTree(yaml) } catch (e: Exception) { return emptyList() }
        val out = mutableListOf<String>()
        root.walkTextSelectors(out)
        return out
    }

    private fun JsonNode.walkTextSelectors(out: MutableList<String>) {
        if (isNull) return
        when {
            isObject -> fields().forEach { (key, value) ->
                if (key == "text" && value.isTextual) {
                    value.asText().takeIf { it.isNotBlank() }?.let(out::add)
                } else {
                    value.walkTextSelectors(out)
                }
            }
            isArray -> forEach { it.walkTextSelectors(out) }
        }
    }

    private fun String.matchesAny(nodes: List<TreeNode>): Boolean {
        val regex = try {
            Regex(this, REGEX_OPTIONS)
        } catch (e: Exception) {
            return nodes.any { n -> TEXT_ATTRIBUTES.any { n.attributes[it] == this } }
        }
        return Filters.textMatches(regex).invoke(nodes).isNotEmpty()
    }

    private fun String.suggestionsFrom(nodes: List<TreeNode>): List<String> {
        val candidates = linkedSetOf<String>()
        nodes.forEach { n -> TEXT_ATTRIBUTES.forEach { n.attributes[it]?.takeIf(String::isNotBlank)?.let(candidates::add) } }
        if (candidates.isEmpty()) return emptyList()

        val lowered = lowercase()
        val overlap = candidates.filter { it.lowercase().contains(lowered) || lowered.contains(it.lowercase()) }
        return (if (overlap.isNotEmpty()) overlap.take(MAX_SUGGESTIONS) else candidates.take(FALLBACK_PEEK)).toList()
    }

    // Mirrors maestro-client/Filters.kt::textMatches and
    // maestro-orchestra/Orchestra.kt::REGEX_OPTIONS — keep in sync.
    private val TEXT_ATTRIBUTES = listOf("text", "hintText", "accessibilityText")
    private val REGEX_OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)

    private val ENV_INTERPOLATION = Regex("""\$\{[^}]+}""")
    private const val MAX_SUGGESTIONS = 5
    private const val FALLBACK_PEEK = 10
    private val YAML = YAMLMapper()
}
