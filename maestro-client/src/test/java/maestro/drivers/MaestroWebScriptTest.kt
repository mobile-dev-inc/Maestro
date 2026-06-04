package maestro.drivers

import com.google.common.truth.Truth.assertThat
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Test

class MaestroWebScriptTest {

    @Test
    fun `maps Flutter Web semantics identifier to resource id`() {
        Context.newBuilder("js").build().use { context ->
            context.eval("js", domStubScript())
            context.eval("js", maestroWebScript())

            val resourceId = context.eval(
                "js",
                "maestro.getContentDescription().children[0].attributes['resource-id']",
            ).asString()

            assertThat(resourceId).isEqualTo("admin-login-email-input")
        }
    }

    @Test
    fun `keeps native DOM id behavior without Flutter Web semantics identifier`() {
        Context.newBuilder("js").build().use { context ->
            context.eval(
                "js",
                domStubScript(
                    nodeProperties = "id: 'native-dom-id',",
                    semanticsAttributes = "",
                ),
            )
            context.eval("js", maestroWebScript())

            val resourceId = context.eval(
                "js",
                "maestro.getContentDescription().children[0].attributes['resource-id']",
            ).asString()

            assertThat(resourceId).isEqualTo("native-dom-id")
        }
    }

    @Test
    fun `prefers Flutter Web semantics identifier over generated semantic DOM id and aria label`() {
        Context.newBuilder("js").build().use { context ->
            context.eval(
                "js",
                domStubScript(
                    nodeProperties = "id: 'flt-semantic-node-372', ariaLabel: 'Email',",
                ),
            )
            context.eval("js", maestroWebScript())

            val resourceId = context.eval(
                "js",
                "maestro.getContentDescription().children[0].attributes['resource-id']",
            ).asString()

            assertThat(resourceId).isEqualTo("admin-login-email-input")
        }
    }

    @Test
    fun `keeps empty data-testid resource id behavior without Flutter Web semantics identifier`() {
        Context.newBuilder("js").build().use { context ->
            context.eval(
                "js",
                domStubScript(
                    semanticsAttributes = "'data-testid': { value: '' },",
                ),
            )
            context.eval("js", maestroWebScript())

            val hasResourceId = context.eval(
                "js",
                "Object.prototype.hasOwnProperty.call(maestro.getContentDescription().children[0].attributes, 'resource-id')",
            ).asBoolean()
            val resourceId = context.eval(
                "js",
                "maestro.getContentDescription().children[0].attributes['resource-id']",
            ).asString()

            assertThat(hasResourceId).isTrue()
            assertThat(resourceId).isEqualTo("")
        }
    }

    private fun maestroWebScript(): String {
        return requireNotNull(javaClass.classLoader.getResource("maestro-web.js")) {
            "maestro-web.js resource was not found"
        }.readText()
    }

    private fun domStubScript(
        nodeProperties: String = "",
        semanticsAttributes: String = "'flt-semantics-identifier': { value: 'admin-login-email-input' },",
    ): String {
        return """
            globalThis.Node = { TEXT_NODE: 3 };
            globalThis.window = globalThis;
            globalThis.innerWidth = 800;
            globalThis.innerHeight = 600;

            function createTextNode(text) {
              return {
                nodeType: Node.TEXT_NODE,
                textContent: text,
              };
            }

            function createElement(tagName, properties = {}) {
              const children = properties.children || [];
              const node = {
                tagName,
                id: '',
                attributes: {},
                childNodes: [],
                children,
                selected: false,
                parentElement: null,
                getBoundingClientRect() {
                  return {
                    x: 0,
                    y: 0,
                    width: 100,
                    height: 20,
                  };
                },
                ...properties,
              };

              children.forEach(child => child.parentElement = node);
              return node;
            }

            const semanticsNode = createElement('flt-semantics', {
              $nodeProperties
              attributes: {
                $semanticsAttributes
              },
              childNodes: [createTextNode('Email')],
            });

            const bodyNode = createElement('body', {
              children: [semanticsNode],
            });

            globalThis.document = {
              body: bodyNode,
              readyState: 'complete',
              querySelectorAll() {
                return [];
              },
            };

            globalThis.maestro = {};
        """.trimIndent()
    }
}
