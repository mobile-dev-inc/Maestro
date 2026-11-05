package maestro

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class BoundsTest {

    @Test
    internal fun `intersect returns the overlap`() {
        val a = Bounds(x = 0, y = 0, width = 100, height = 100)
        val b = Bounds(x = 50, y = 80, width = 100, height = 100)

        assertThat(a.intersect(b)).isEqualTo(Bounds(x = 50, y = 80, width = 50, height = 20))
        assertThat(b.intersect(a)).isEqualTo(a.intersect(b))
    }

    @Test
    internal fun `intersect of rects that only touch is empty`() {
        val a = Bounds(x = 0, y = 0, width = 100, height = 100)
        val below = Bounds(x = 0, y = 100, width = 100, height = 100)

        assertThat(a.intersect(below)).isNull()
    }

    @Test
    internal fun `contains a rect inside it, including its own edges`() {
        val a = Bounds(x = 0, y = 0, width = 100, height = 100)

        assertThat(a.contains(a)).isTrue()
        assertThat(a.contains(Bounds(x = 10, y = 10, width = 50, height = 50))).isTrue()
        assertThat(a.contains(Bounds(x = 10, y = 10, width = 100, height = 50))).isFalse()
    }

    @Test
    internal fun `bounds string round-trips through the attribute format`() {
        val bounds = Bounds(x = 16, y = 787, width = 369, height = 44)

        assertThat(bounds.toBoundsString()).isEqualTo("[16,787][385,831]")
    }
}
