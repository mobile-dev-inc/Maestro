package maestro.drivers

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.common.truth.Truth.assertThat
import maestro.Maestro
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Test

class MaestroWebJsSmokeTest {

    private val mapper = jacksonObjectMapper()
    private val maestroWebScript = Maestro::class.java.getResourceAsStream("/maestro-web.js")?.bufferedReader()?.use {
        it.readText()
    } ?: error("Could not read maestro web script")

    @Test
    fun `maestro web maps aria-label to accessibilityText and keeps resource-id fallback behavior`() {
        val buttons = buttonAttributes(
            """
            {
              tagName: 'body',
              children: [
                createElement({
                  tagName: 'button',
                  ariaLabel: 'Some label',
                  attributes: { 'data-testid': { value: 'some-test-id' } },
                  children: [createElement({ tagName: 'svg' })]
                }),
                createElement({
                  tagName: 'button',
                  ariaLabel: 'Fallback label',
                  children: [createElement({ tagName: 'svg' })]
                })
              ]
            }
            """.trimIndent()
        )

        assertThat(buttons).hasSize(2)

        assertThat(buttons[0]["accessibilityText"]).isEqualTo("Some label")
        assertThat(buttons[0]["resource-id"]).isEqualTo("some-test-id")
        assertThat(buttons[1]["accessibilityText"]).isEqualTo("Fallback label")
        assertThat(buttons[1]["resource-id"]).isEqualTo("Fallback label")
    }

    private fun buttonAttributes(bodyProps: String): List<Map<String, Any?>> {
        val root = contentDescription(bodyProps)
        @Suppress("UNCHECKED_CAST")
        val children = root["children"] as List<Map<String, Any?>>
        return children.map {
            @Suppress("UNCHECKED_CAST")
            it["attributes"] as Map<String, Any?>
        }
    }

    private fun contentDescription(bodyProps: String): Map<String, Any?> {
        val source = """
            const Node = { TEXT_NODE: 3 };
            globalThis.Node = Node;

            function createElement(props = {}) {
              const node = {
                tagName: props.tagName || 'div',
                ariaLabel: props.ariaLabel,
                id: props.id,
                name: props.name,
                title: props.title,
                htmlFor: props.htmlFor,
                value: props.value,
                placeholder: props.placeholder,
                selected: props.selected,
                attributes: props.attributes || {},
                childNodes: props.childNodes || [],
                children: props.children || [],
                getBoundingClientRect: () => ({
                  x: props.x || 0,
                  y: props.y || 0,
                  width: props.width || 40,
                  height: props.height || 20,
                }),
                matches: (selector) => selector === ':focus-within' ? !!props.focusWithin : false,
              };

              node.parentElement = null;
              node.parentNode = null;

              node.children.forEach((child) => {
                child.parentElement = node;
                child.parentNode = node;
              });

              node.childNodes.forEach((child) => {
                if (typeof child === 'object') {
                  child.parentNode = node;
                }
              });

              return node;
            }

            globalThis.window = {
              innerWidth: 100,
              innerHeight: 200,
              maestro: {},
            };

            globalThis.document = {
              readyState: 'complete',
            };

            document.body = createElement($bodyProps);

            $maestroWebScript
        """.trimIndent()

        Context.newBuilder("js").allowAllAccess(true).build().use { context ->
            context.eval("js", source)
            val json = context.eval("js", "JSON.stringify(window.maestro.getContentDescription())").asString()
            return mapper.readValue(json)
        }
    }
}
