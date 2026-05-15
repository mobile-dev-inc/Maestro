package maestro.web.input

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class HtmlDateInputFormatterTest {

    @Test
    internal fun `normalizes compact month day year text for html date inputs`() {
        assertThat(HtmlDateInputFormatter.normalize("02111990")).isEqualTo("1990-02-11")
        assertThat(HtmlDateInputFormatter.normalize("12011998")).isEqualTo("1998-12-01")
    }

    @Test
    internal fun `normalizes iso date text for html date inputs`() {
        assertThat(HtmlDateInputFormatter.normalize("1998-12-01")).isEqualTo("1998-12-01")
        assertThat(HtmlDateInputFormatter.normalize("19981201")).isEqualTo("1998-12-01")
    }

    @Test
    internal fun `normalizes separated date text for html date inputs`() {
        assertThat(HtmlDateInputFormatter.normalize("02/11/1990")).isEqualTo("1990-02-11")
        assertThat(HtmlDateInputFormatter.normalize("1998/12/01")).isEqualTo("1998-12-01")
    }

    @Test
    internal fun `falls back to day month year when month day year is invalid`() {
        assertThat(HtmlDateInputFormatter.normalize("31121998")).isEqualTo("1998-12-31")
        assertThat(HtmlDateInputFormatter.normalize("31/12/1998")).isEqualTo("1998-12-31")
    }

    @Test
    internal fun `returns null for text that cannot be normalized`() {
        assertThat(HtmlDateInputFormatter.normalize("not-a-date")).isNull()
        assertThat(HtmlDateInputFormatter.normalize("02301998")).isNull()
        assertThat(HtmlDateInputFormatter.normalize("02/11/90")).isNull()
        assertThat(HtmlDateInputFormatter.normalize("00001201")).isNull()
    }
}
