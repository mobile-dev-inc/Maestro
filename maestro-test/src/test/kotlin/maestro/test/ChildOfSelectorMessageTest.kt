package maestro.test

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.MaestroException
import maestro.orchestra.ElementSelector
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.TapOnElementCommand
import maestro.test.drivers.FakeDriver
import maestro.test.drivers.FakeLayoutElement
import maestro.test.drivers.FakeLayoutElement.Bounds
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A failed `childOf` lookup can mean two different things - the parent never matched, or the parent
 * matched and the target isn't inside it - and the error has to say which. Before these tests the
 * only assertion on an ElementNotFound anywhere in the suite checked the exception type, so every
 * message below could regress silently.
 */
class ChildOfSelectorMessageTest {

    private fun elementNotFound(
        selector: ElementSelector,
        layout: FakeLayoutElement.() -> Unit,
    ): MaestroException.ElementNotFound {
        val driver = FakeDriver()
        driver.setLayout(FakeLayoutElement().apply { layout() })
        driver.open()

        return Maestro(driver).use { maestro ->
            assertThrows<MaestroException.ElementNotFound> {
                runBlocking {
                    Orchestra(maestro, lookupTimeoutMs = 0L, optionalLookupTimeoutMs = 0L).runFlow(
                        listOf(MaestroCommand(tapOnElement = TapOnElementCommand(selector = selector)))
                    )
                }
            }
        }
    }

    /** A card containing one child with [childText], and optionally a stray "Edit" outside the card. */
    private fun screen(childText: String, editOutsideCard: Boolean = false): FakeLayoutElement.() -> Unit = {
        element {
            id = "address_card"
            bounds = Bounds(0, 0, 200, 200)
            element {
                text = childText
                bounds = Bounds(10, 10, 190, 50)
            }
        }
        if (editOutsideCard) {
            element {
                text = "Edit"
                bounds = Bounds(0, 300, 200, 340)
            }
        }
    }

    private val editInCard = ElementSelector(
        textRegex = "Edit",
        childOf = ElementSelector(idRegex = "address_card"),
    )

    // The parent id is misspelled - the screen has address_card, not adress_card.
    private val editInMisspelledCard = ElementSelector(
        textRegex = "Edit",
        childOf = ElementSelector(idRegex = "adress_card"),
    )

    @Test
    fun `child missing under a matching parent names the parent it searched`() {
        val error = elementNotFound(editInCard, screen("Something else"))

        assertThat(error.message).isEqualTo("Element not found: Text matching regex: Edit, Child of: id: address_card")
        assertThat(error.debugMessage)
            .contains("The childOf parent (Id matching regex: address_card) matched, but Text matching regex: Edit was not found inside it.")
    }

    @Test
    fun `a parent that never matches is reported as a parent failure`() {
        val error = elementNotFound(editInMisspelledCard, screen("Edit"))

        assertThat(error.message).isEqualTo(
            "Parent element not found: Id matching regex: adress_card (looking for Text matching regex: Edit inside it)"
        )
        assertThat(error.debugMessage)
            .contains("The childOf parent (Id matching regex: adress_card) matched no element, so its children were never searched.")
    }

    @Test
    fun `the two childOf failures do not share a message`() {
        // The whole point of distinguishing them: a typo'd parent and an absent child must not look alike.
        val missingParent = elementNotFound(editInMisspelledCard, screen("Edit"))
        val missingChild = elementNotFound(editInCard, screen("Something else"))

        assertThat(missingParent.message).isNotEqualTo(missingChild.message)
        assertThat(missingParent.debugMessage).isNotEqualTo(missingChild.debugMessage)
    }

    @Test
    fun `a target that exists outside the parent is counted and called out`() {
        val error = elementNotFound(editInCard, screen("Something else", editOutsideCard = true))

        assertThat(error.debugMessage)
            .contains("1 element(s) matched Text matching regex: Edit elsewhere on screen, outside that parent.")
    }

    @Test
    fun `a missing parent says whether the target exists elsewhere at all`() {
        val existsElsewhere = elementNotFound(editInMisspelledCard, screen("Edit"))
        assertThat(existsElsewhere.debugMessage).contains(
            "1 element(s) matched Text matching regex: Edit elsewhere on screen, so the childOf parent is the likely problem."
        )

        val existsNowhere = elementNotFound(
            ElementSelector(textRegex = "Nope", childOf = ElementSelector(idRegex = "adress_card")),
            screen("Edit"),
        )
        assertThat(existsNowhere.debugMessage)
            .contains("Nothing matched Text matching regex: Nope anywhere on screen either.")
    }

    @Test
    fun `a target with no criteria of its own omits the target clause and the count`() {
        // Such a target filters to every node, so a count would just report the size of the hierarchy.
        val error = elementNotFound(
            ElementSelector(childOf = ElementSelector(idRegex = "adress_card")),
            screen("Edit"),
        )

        assertThat(error.message).isEqualTo("Parent element not found: Id matching regex: adress_card")
        assertThat(error.debugMessage).doesNotContain("element(s) matched")
        assertThat(error.debugMessage).doesNotContain("anywhere on screen")
    }

    @Test
    fun `selectors without childOf are unaffected`() {
        val error = elementNotFound(ElementSelector(textRegex = "Missing")) {
            element {
                text = "Something else"
                bounds = Bounds(10, 10, 190, 50)
            }
        }

        assertThat(error.message).isEqualTo("Element not found: Text matching regex: Missing")
        assertThat(error.debugMessage).doesNotContain("childOf")
    }

    @Test
    fun `an out-of-range index is named in the message`() {
        // Two elements match the text, so index 2 is one past the end. Reporting only the text would
        // send the reader looking for a missing element when the index is what failed.
        val error = elementNotFound(ElementSelector(textRegex = "Row", index = "2")) {
            element {
                text = "Row"
                bounds = Bounds(0, 0, 200, 50)
            }
            element {
                text = "Row"
                bounds = Bounds(0, 60, 200, 110)
            }
        }

        assertThat(error.message).isEqualTo("Element not found: Text matching regex: Row, Index: 2")
    }

    @Test
    fun `a childOf selector reports both the parent and an out-of-range index`() {
        val error = elementNotFound(
            ElementSelector(
                textRegex = "Row",
                index = "2",
                childOf = ElementSelector(idRegex = "address_card"),
            )
        ) {
            element {
                id = "address_card"
                bounds = Bounds(0, 0, 200, 200)
                element {
                    text = "Row"
                    bounds = Bounds(10, 10, 190, 50)
                }
            }
        }

        assertThat(error.message)
            .isEqualTo("Element not found: Text matching regex: Row, Child of: id: address_card, Index: 2")
    }
}
