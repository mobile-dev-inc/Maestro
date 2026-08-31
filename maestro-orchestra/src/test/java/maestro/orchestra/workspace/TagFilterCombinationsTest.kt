package maestro.orchestra.workspace

import com.google.common.truth.Truth.assertThat
import maestro.orchestra.error.ValidationError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Exhaustive matrix over the three tag filters, run against /workspaces/022_require_tags:
 *
 *   flowAB       -> A, B
 *   flowAC       -> A, C
 *   flowABC      -> A, B, C
 *   flowUntagged -> (no tags)
 *
 * includeTags is a logical OR, requireTags a logical AND, excludeTags a veto. A Flow is
 * kept only when all three predicates hold, so this pins down each filter on its own and
 * every pairing between them.
 */
internal class TagFilterCombinationsTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("combinations")
    fun `tag filter combination`(
        @Suppress("UNUSED_PARAMETER") description: String,
        includeTags: List<String>,
        requireTags: List<String>,
        excludeTags: List<String>,
        expectedFlows: List<String>,
    ) {
        val plan = plan(includeTags, requireTags, excludeTags)

        assertThat(plan.flowsToRun).containsExactlyElementsIn(expectedFlows.map(::flow))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combinationsMatchingNothing")
    fun `tag filter combination that matches no Flows`(
        @Suppress("UNUSED_PARAMETER") description: String,
        includeTags: List<String>,
        requireTags: List<String>,
        excludeTags: List<String>,
    ) {
        assertThrows<ValidationError> { plan(includeTags, requireTags, excludeTags) }
    }

    @Test
    fun `all three filters set in the workspace config`() {
        // config.yaml: includeTags A, requireTags B, excludeTags C.
        // flowUntagged fails the OR, flowAC fails the AND, flowABC is vetoed.
        val plan = WorkspaceExecutionPlanner.plan(
            input = setOf(resource("/workspaces/024_tag_filter_combinations")),
            includeTags = listOf(),
            excludeTags = listOf(),
            config = null,
        )

        assertThat(plan.flowsToRun).containsExactly(
            resource("/workspaces/024_tag_filter_combinations/flowAB.yaml"),
        )
    }

    @Test
    fun `config filters union with parameter filters of every kind`() {
        // config.yaml contributes includeTags A, requireTags B, excludeTags C; the
        // parameters add includeTags B, requireTags A and an exclude that matches nothing.
        val plan = WorkspaceExecutionPlanner.plan(
            input = setOf(resource("/workspaces/024_tag_filter_combinations")),
            includeTags = listOf("B"),
            excludeTags = listOf("unknown"),
            config = null,
            requireTags = listOf("A"),
        )

        assertThat(plan.flowsToRun).containsExactly(
            resource("/workspaces/024_tag_filter_combinations/flowAB.yaml"),
        )
    }

    private fun plan(
        includeTags: List<String>,
        requireTags: List<String>,
        excludeTags: List<String>,
    ) = WorkspaceExecutionPlanner.plan(
        input = setOf(resource("/workspaces/022_require_tags")),
        includeTags = includeTags,
        excludeTags = excludeTags,
        config = null,
        requireTags = requireTags,
    )

    private fun flow(name: String): Path = resource("/workspaces/022_require_tags/$name.yaml")

    private fun resource(path: String): Path =
        Paths.get(TagFilterCombinationsTest::class.java.getResource(path)!!.toURI())

    companion object {

        private val ALL = listOf("flowAB", "flowAC", "flowABC", "flowUntagged")

        @JvmStatic
        fun combinations() = listOf(
            // no filters
            case("no filters keeps every Flow", expected = ALL),

            // includeTags on its own (logical OR)
            case("include a single tag", include = listOf("B"), expected = listOf("flowAB", "flowABC")),
            case("include a tag every tagged Flow carries", include = listOf("A"), expected = listOf("flowAB", "flowAC", "flowABC")),
            case("include two tags is an OR, not an AND", include = listOf("B", "C"), expected = listOf("flowAB", "flowAC", "flowABC")),
            case("include an unknown tag alongside a known one", include = listOf("B", "unknown"), expected = listOf("flowAB", "flowABC")),

            // requireTags on its own (logical AND)
            case("require a single tag behaves like include", require = listOf("B"), expected = listOf("flowAB", "flowABC")),
            case("require two tags is an AND", require = listOf("B", "C"), expected = listOf("flowABC")),
            case("require every tag of the widest Flow", require = listOf("A", "B", "C"), expected = listOf("flowABC")),
            case("require is order independent", require = listOf("C", "B"), expected = listOf("flowABC")),
            case("require a repeated tag is idempotent", require = listOf("B", "B"), expected = listOf("flowAB", "flowABC")),

            // excludeTags on its own
            case("exclude a single tag", exclude = listOf("B"), expected = listOf("flowAC", "flowUntagged")),
            case("exclude keeps untagged Flows", exclude = listOf("A"), expected = listOf("flowUntagged")),
            case("exclude an unknown tag is a no-op", exclude = listOf("unknown"), expected = ALL),

            // include + exclude (the pre-existing pairing, must not regress)
            case("include then exclude", include = listOf("A"), exclude = listOf("C"), expected = listOf("flowAB")),
            case("include and exclude on unrelated tags", include = listOf("C"), exclude = listOf("B"), expected = listOf("flowAC")),

            // include + require
            case("require narrows a wider include", include = listOf("C"), require = listOf("B", "C"), expected = listOf("flowABC")),
            case("include narrows a wider require", include = listOf("B"), require = listOf("A"), expected = listOf("flowAB", "flowABC")),
            case("include and require narrow from opposite sides", include = listOf("B"), require = listOf("A", "C"), expected = listOf("flowABC")),

            // require + exclude
            case("exclude removes a Flow the require kept", require = listOf("B"), exclude = listOf("C"), expected = listOf("flowAB")),
            case("exclude an unknown tag leaves the require untouched", require = listOf("B", "C"), exclude = listOf("unknown"), expected = listOf("flowABC")),

            // all three at once
            case("include, require and exclude together", include = listOf("A"), require = listOf("B"), exclude = listOf("C"), expected = listOf("flowAB")),
            case("all three set, exclude is the only narrowing filter", include = listOf("A"), require = listOf("A"), exclude = listOf("B"), expected = listOf("flowAC")),
        ).map { it.arguments }

        @JvmStatic
        fun combinationsMatchingNothing() = listOf(
            case("require a tag no Flow carries", require = listOf("unknown")),
            case("require a combination no single Flow carries", require = listOf("B", "unknown")),
            case("exclude vetoes everything the require kept", require = listOf("B", "C"), exclude = listOf("A")),
            case("include and require are mutually unsatisfiable", include = listOf("unknown"), require = listOf("B")),
            case("exclude contradicts include on the same tag", include = listOf("B"), exclude = listOf("B")),
            case("exclude contradicts require on the same tag", require = listOf("B"), exclude = listOf("B")),
        ).map { it.argumentsWithoutExpectation }

        private fun case(
            description: String,
            include: List<String> = emptyList(),
            require: List<String> = emptyList(),
            exclude: List<String> = emptyList(),
            expected: List<String> = emptyList(),
        ) = Case(description, include, require, exclude, expected)

        private data class Case(
            val description: String,
            val include: List<String>,
            val require: List<String>,
            val exclude: List<String>,
            val expected: List<String>,
        ) {
            val arguments: Arguments
                get() = Arguments.of(description, include, require, exclude, expected)

            val argumentsWithoutExpectation: Arguments
                get() = Arguments.of(description, include, require, exclude)
        }
    }
}
