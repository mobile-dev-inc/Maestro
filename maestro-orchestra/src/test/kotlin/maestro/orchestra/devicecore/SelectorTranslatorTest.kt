package maestro.orchestra.devicecore

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.Match
import dev.mobile.devicecore.prototype.api.Relation
import dev.mobile.devicecore.prototype.api.Selector
import maestro.MaestroException
import maestro.orchestra.ElementSelector
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SelectorTranslatorTest {
    @Test
    fun `text maps to PATTERN ignoreCase text selector`() {
        val s = SelectorTranslator.translate(ElementSelector(textRegex = "Form Test"))
        assertThat(s).isInstanceOf(Selector.Text::class.java)
        s as Selector.Text
        assertThat(s.value).isEqualTo("Form Test")
        assertThat(s.match).isEqualTo(Match.PATTERN)
        assertThat(s.ignoreCase).isTrue()
    }

    @Test
    fun `id maps to id selector`() {
        val s = SelectorTranslator.translate(ElementSelector(idRegex = "fabAddIcon"))
        assertThat(s).isEqualTo(Selector.Id("fabAddIcon"))
    }

    @Test
    fun `index wraps in nth`() {
        val s = SelectorTranslator.translate(ElementSelector(textRegex = "Item", index = "2"))
        assertThat(s).isInstanceOf(Selector.Nth::class.java)
        s as Selector.Nth
        assertThat(s.index).isEqualTo(2)
        assertThat(s.target).isInstanceOf(Selector.Text::class.java)
    }

    @Test
    fun `unsupported field throws NotImplemented naming it`() {
        val e = assertThrows<MaestroException.NotImplemented> {
            SelectorTranslator.translate(ElementSelector(textRegex = "x", enabled = true))
        }
        assertThat(e.message).contains("enabled")
    }

    @Test
    fun `below maps to a Relative selector carrying the relation and translated anchor`() {
        val s = SelectorTranslator.translate(
            ElementSelector(idRegex = "field", below = ElementSelector(textRegex = "Header")),
        )
        assertThat(s).isInstanceOf(Selector.Relative::class.java)
        s as Selector.Relative
        assertThat(s.relation).isEqualTo(Relation.BELOW)
        assertThat(s.target).isInstanceOf(Selector.Id::class.java)
        assertThat(s.anchor).isInstanceOf(Selector.Text::class.java)
    }

    @Test
    fun `combined relational fields throw NotImplemented`() {
        val e = assertThrows<MaestroException.NotImplemented> {
            SelectorTranslator.translate(
                ElementSelector(
                    idRegex = "field",
                    below = ElementSelector(textRegex = "H"),
                    above = ElementSelector(textRegex = "F"),
                ),
            )
        }
        assertThat(e.message).contains("combined relational")
    }

    @Test
    fun `neither text nor id throws NotImplemented`() {
        assertThrows<MaestroException.NotImplemented> {
            SelectorTranslator.translate(ElementSelector(index = "0"))
        }
    }

    @Test
    fun `combined text and id throws NotImplemented`() {
        assertThrows<MaestroException.NotImplemented> {
            SelectorTranslator.translate(ElementSelector(textRegex = "x", idRegex = "y"))
        }
    }
}
