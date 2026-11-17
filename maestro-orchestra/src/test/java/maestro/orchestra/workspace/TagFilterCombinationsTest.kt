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
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/**
 * Every test covering the three tag filters lives here. includeTags is a logical OR,
 * requireTags a logical AND, excludeTags a veto; a Flow runs only when all three predicates
 * hold. The matrix pins down each filter alone and every pairing between them; the cases
 * below it cover the same filters arriving from a workspace config instead of parameters.
 *
 * All three fixtures hold the same four Flows and differ only in config.yaml:
 *
 *   flowAB       -> A, B
 *   flowAC       -> A, C
 *   flowABC      -> A, B, C
 *   flowUntagged -> (no tags)
 *
 *   /workspaces/022_require_tags             no config.yaml; filters come from parameters
 *   /workspaces/023_global_require_tags      requireTags: B, C
 *   /workspaces/024_tag_filter_combinations  includeTags: A, requireTags: B, excludeTags: C
 *   /workspaces/025_require_tags_union       requireTags: B - config only, no Flows
 *
 * Fixture invariant - read this before trimming any Flow. A case only proves a filter does
 * something if some Flow passes the other two filters and fails that one. flowA and flowB
 * exist solely to make that possible: without them every Flow carrying B also carries A, and
 * no case combining all three filters can be load-bearing - `include A + require B + exclude
 * C` then yields flowAB whether or not requireTags is applied at all. Removing either Flow
 * silently turns those cases back into decoration, and nothing will fail to tell you.
 * Removing just one of the two is equally fatal; both are needed.
 *
 * The same trap applies to config-versus-parameter cases: a config narrow enough to pin the
 * result to a single Flow makes every parameter unprovable, because any parameter that
 * changes the answer empties it. That is why 025 requires only B.
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
        // Dropping the parameter requireTags, the config requireTags or the config
        // excludeTags each changes the answer. The parameter excludeTags is a deliberate
        // no-op, covering the "unknown tag is harmless" case alongside the rest.
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

    @Test
    fun `requireTags from a config auto-discovered in the workspace`() {
        val plan = WorkspaceExecutionPlanner.plan(
            input = setOf(resource("/workspaces/023_global_require_tags")),
            includeTags = listOf(),
            excludeTags = listOf(),
            config = null,
        )

        // requireTags B, C is picked up from the workspace's own config.yaml.
        assertThat(plan.flowsToRun).containsExactly(
            resource("/workspaces/023_global_require_tags/flowABC.yaml"),
        )
    }

    @Test
    fun `requireTags from an explicit config file union with parameter requireTags`() {
        val plan = WorkspaceExecutionPlanner.plan(
            input = setOf(resource("/workspaces/022_require_tags")),
            includeTags = listOf(),
            excludeTags = listOf(),
            config = resource("/workspaces/025_require_tags_union/config.yaml"),
            requireTags = listOf("C"),
        )

        // B (config) + C (parameter) must both be present. Neither half alone gives this
        // answer - config alone keeps flowAB/flowABC/flowB, the parameter alone keeps
        // flowAC/flowABC - so the test fails if either contribution is dropped.
        assertThat(plan.flowsToRun).containsExactly(flow("flowABC"))
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

    @Test
    fun `ALL lists exactly the Flows in the fixture`() {
        // ALL is a hand-maintained copy of the fixture, and the cases asserting it are the
        // only ones that keep passing when a Flow is added or removed. Without this guard,
        // growing the fixture leaves them quietly asserting a stale set. 022 carries no
        // config.yaml by design, so every .yaml here is a Flow.
        val onDisk = resource("/workspaces/022_require_tags")
            .listDirectoryEntries("*.yaml")
            .map { it.nameWithoutExtension }

        assertThat(onDisk).containsExactlyElementsIn(ALL)
    }

    companion object {

        private val ALL = listOf("flowA", "flowB", "flowAB", "flowAC", "flowABC", "flowUntagged")

        @JvmStatic
        fun combinations() = listOf(
            // no filters
            case("no filters keeps every Flow", expected = ALL),

            // includeTags on its own (logical OR)
            case("include a single tag", include = listOf("B"), expected = listOf("flowAB", "flowABC", "flowB")),
            case("include a broadly shared tag", include = listOf("A"), expected = listOf("flowA", "flowAB", "flowAC", "flowABC")),
            case("include two tags is an OR, not an AND", include = listOf("B", "C"), expected = listOf("flowAB", "flowAC", "flowABC", "flowB")),
            case("include an unknown tag alongside a known one", include = listOf("B", "unknown"), expected = listOf("flowAB", "flowABC", "flowB")),

            // requireTags on its own (logical AND)
            case("require a single tag behaves like include", require = listOf("B"), expected = listOf("flowAB", "flowABC", "flowB")),
            case("require two tags is an AND", require = listOf("B", "C"), expected = listOf("flowABC")),
            case("require every tag of the widest Flow", require = listOf("A", "B", "C"), expected = listOf("flowABC")),
            case("require is order independent", require = listOf("C", "B"), expected = listOf("flowABC")),
            case("require a repeated tag is idempotent", require = listOf("B", "B"), expected = listOf("flowAB", "flowABC", "flowB")),

            // excludeTags on its own
            case("exclude a single tag", exclude = listOf("B"), expected = listOf("flowA", "flowAC", "flowUntagged")),
            case("exclude keeps every Flow lacking that tag", exclude = listOf("A"), expected = listOf("flowB", "flowUntagged")),
            case("exclude an unknown tag is a no-op", exclude = listOf("unknown"), expected = ALL),
            case("exclude tag matching is case sensitive", exclude = listOf("a"), expected = ALL),

            // include + exclude (the pre-existing pairing, must not regress)
            case("include then exclude", include = listOf("A"), exclude = listOf("C"), expected = listOf("flowA", "flowAB")),
            case("include and exclude on unrelated tags", include = listOf("C"), exclude = listOf("B"), expected = listOf("flowAC")),

            // include + require
            case("require narrows a wider include", include = listOf("C"), require = listOf("B", "C"), expected = listOf("flowABC")),
            case("include narrows a wider require", include = listOf("B"), require = listOf("A"), expected = listOf("flowAB", "flowABC")),
            case("include and require narrow from opposite sides", include = listOf("B"), require = listOf("A", "C"), expected = listOf("flowABC")),

            // require + exclude
            case("exclude removes a Flow the require kept", require = listOf("B"), exclude = listOf("C"), expected = listOf("flowAB", "flowB")),
            case("exclude an unknown tag leaves the require untouched", require = listOf("B", "C"), exclude = listOf("unknown"), expected = listOf("flowABC")),

            // all three at once
            case("include, require and exclude together", include = listOf("A"), require = listOf("B"), exclude = listOf("C"), expected = listOf("flowAB")),
            case("all three set, exclude is the only narrowing filter", include = listOf("A"), require = listOf("A"), exclude = listOf("B"), expected = listOf("flowA", "flowAC")),
        ).map { it.arguments }

        @JvmStatic
        fun combinationsMatchingNothing() = listOf(
            case("require a tag no Flow carries", require = listOf("unknown")),
            case("require a combination no single Flow carries", require = listOf("B", "unknown")),
            case("exclude vetoes everything the require kept", require = listOf("B", "C"), exclude = listOf("A")),
            case("an include matching nothing defeats a satisfiable require", include = listOf("unknown"), require = listOf("B")),
            case("include tag matching is case sensitive", include = listOf("a")),
            case("require tag matching is case sensitive", require = listOf("b")),
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
