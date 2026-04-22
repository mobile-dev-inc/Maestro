package maestro.cli.mcp.hierarchy

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper

/**
 * Walks the YAML of a Maestro flow for `text:` selectors and checks whether
 * each appears in the latest hierarchy snapshot. The goal is catching
 * hallucinated strings (e.g. text read off a screenshot that isn't actually
 * in the accessibility tree) before they reach Maestro's runner and produce
 * a less-informative "element not found" at runtime.
 *
 * Per MA-4029, only `text:` is validated in v1. Maestro's `text` matcher is a
 * regex; the validator honours that — partial matches count as hits.
 */
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
        val selectors = extractTextSelectors(yaml)
        if (selectors.isEmpty()) return Result.Ok

        val findings = selectors
            .filterNot { selectorMatchesAnyCandidate(it, snapshot.texts) }
            .map { Finding(selector = it, suggestions = suggestFor(it, snapshot.texts)) }

        return if (findings.isEmpty()) Result.Ok else Result.Miss(findings)
    }

    private fun extractTextSelectors(yaml: String): List<String> {
        val root = try {
            YAML.readTree(yaml)
        } catch (e: Exception) {
            // Invalid YAML will be caught by YamlCommandReader downstream with a
            // better error message; don't try to validate selectors out of it.
            return emptyList()
        }
        val out = mutableListOf<String>()
        collect(root, out)
        return out
    }

    private fun collect(node: JsonNode?, out: MutableList<String>) {
        if (node == null || node.isNull) return
        when {
            node.isObject -> {
                node.fields().forEach { (key, value) ->
                    if (key == "text" && value.isTextual) {
                        val text = value.asText()
                        if (text.isNotBlank()) out.add(text)
                    } else {
                        collect(value, out)
                    }
                }
            }
            node.isArray -> node.forEach { collect(it, out) }
        }
    }

    private fun selectorMatchesAnyCandidate(selector: String, candidates: Set<String>): Boolean {
        val regex = try {
            Regex(selector)
        } catch (e: Exception) {
            // Fall back to a plain substring check so a flow with literal text
            // containing regex metacharacters still validates.
            return candidates.any { it.contains(selector, ignoreCase = false) }
        }
        return candidates.any { regex.containsMatchIn(it) }
    }

    private fun suggestFor(selector: String, candidates: Set<String>): List<String> {
        if (candidates.isEmpty()) return emptyList()

        val lowered = selector.lowercase()
        val partial = candidates
            .filter { it.lowercase().contains(lowered) || lowered.contains(it.lowercase()) }
            .take(MAX_SUGGESTIONS)

        if (partial.isNotEmpty()) return partial

        // No overlap found — surface a short fallback list of what IS on
        // screen so the agent has something concrete to correct against.
        return candidates.take(FALLBACK_PEEK)
    }

    private const val MAX_SUGGESTIONS = 5
    private const val FALLBACK_PEEK = 10

    private val YAML = YAMLMapper()
}
