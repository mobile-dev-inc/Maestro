package maestro

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import org.junit.jupiter.api.Test

internal class PointTest {

    @Test
    internal fun `Deserialize Point`() {
        // Given
        val json = """
            {
                "x": 1,
                "y": 2
            }
        """.trimIndent()

        // When
        val point = Gson().fromJson(json, Point::class.java)

        // Then
        assertThat(point).isEqualTo(Point(1, 2))
    }

    @Test
    internal fun `Deserialize PointF`() {
        // Given
        val json = """
            {
                "x": 1.5,
                "y": 2.5
            }
        """.trimIndent()

        // When
        val point = Gson().fromJson(json, PointF::class.java)

        // Then
        assertThat(point).isEqualTo(PointF(1.5F, 2.5F))
    }
}