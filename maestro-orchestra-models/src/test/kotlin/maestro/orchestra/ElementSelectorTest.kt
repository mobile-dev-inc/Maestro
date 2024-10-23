package maestro.orchestra

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.truncate

class ElementSelectorTest {
    @Test
    fun `simple text description`(){
        val command = AssertConditionCommand(
            condition = Condition(
                visible = ElementSelector(
                    textRegex = "Hello"
                )
            )
        )

        assertThat(command.description()).isEqualTo("Assert that \"Hello\" is visible")
    }

    @Test
    fun `simple id description`(){
        val command = AssertConditionCommand(
            condition = Condition(
                visible = ElementSelector(
                    idRegex = "hello_element"
                )
            )
        )

        assertThat(command.description()).isEqualTo("Assert that id: hello_element is visible")
    }

    @Test
    fun `description with optional`(){
        val command = AssertConditionCommand(
            condition = Condition(
                visible = ElementSelector(
                    textRegex = "Hello"
                )
            ),
            optional = true
        )

        assertThat(command.description()).isEqualTo("Assert that (Optional) \"Hello\" is visible")
    }

    @Test
    fun `description with enabled`(){
        val command = AssertConditionCommand(
            condition = Condition(
                visible = ElementSelector(
                    textRegex = "Hello",
                    enabled = false
                )
            )
        )

        assertThat(command.description()).isEqualTo("Assert that \"Hello\", disabled is visible")
    }

    @Test
    fun `complex description`(){
        val command = AssertConditionCommand(
            condition = Condition(
                visible = ElementSelector(
                    textRegex = "Hello",
                    idRegex = "hello_element",
                    enabled = false,
                    below = ElementSelector(
                        textRegex = "World"
                    ),
                    above = ElementSelector(
                        idRegex = "page_break_element"
                    ),
                    leftOf = ElementSelector(
                        textRegex = "Right"
                    ),
                    rightOf = ElementSelector(
                        idRegex = "left_element"
                    ),
                    containsChild = ElementSelector(
                        idRegex = "hello_emoji_container"
                    ),
                    containsDescendants = listOf(
                        ElementSelector(
                            idRegex = "hello_emoji"
                        ),
                        ElementSelector(
                            idRegex = "hello_emoji_text"
                        ),
                        ElementSelector(
                            idRegex = "have_been_greeted",
                            checked = true
                        )
                    ),
                    index = "0",
                    selected = false,
                    focused = false,
                    childOf = ElementSelector(
                        textRegex = "Welcome Screen"
                    )
                )
            )
        )

        assertThat(command.description()).isEqualTo("Assert that \"Hello\", id: hello_element, disabled, Below \"World\", Above id: page_break_element, Left of \"Right\", Right of id: left_element, Contains child: id: hello_emoji_container, Contains descendants: [id: hello_emoji, id: hello_emoji_text, id: have_been_greeted], Index: 0, not selected, not focused, Child of: \"Welcome Screen\" is visible")
    }
}