package maestro.conformance.cli

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SelectionTest {
    @Test fun `parses single api`() {
        assertThat(Selection.parseApis("34")).containsExactly(34)
    }
    @Test fun `parses comma list`() {
        assertThat(Selection.parseApis("25,26,27")).containsExactly(25, 26, 27).inOrder()
    }
    @Test fun `parses range and clamps to 24-36`() {
        assertThat(Selection.parseApis("20..40")).isEqualTo((24..36).toList())
    }
    @Test fun `dedupes and sorts`() {
        assertThat(Selection.parseApis("30,24,30")).containsExactly(24, 30).inOrder()
    }
    @Test fun `parses framework list`() {
        assertThat(Selection.parseList("native,compose")).containsExactly("native", "compose").inOrder()
    }
}
