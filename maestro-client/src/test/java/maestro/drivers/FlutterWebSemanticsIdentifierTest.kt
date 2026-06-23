package maestro.drivers

import com.google.common.truth.Truth.assertThat
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Test

/**
 * Exercises the bundled maestro-web.js directly in a JS engine so the
 * resource-id derivation is pinned without standing up a browser.
 *
 * Flutter web puts a developer's Semantics(identifier:) on the DOM attribute
 * flt-semantics-identifier, while node.id is an internal handle of the form
 * flt-semantic-node-N that is reassigned between frames. resource-id must
 * therefore prefer the stable identifier, and must leave every existing
 * (non-Flutter) resolution path untouched.
 */
class FlutterWebSemanticsIdentifierTest {

    @Test
    fun `resource-id is taken from flt-semantics-identifier when present`() {
        val resourceId = resolveResourceId(
            attributes = mapOf("flt-semantics-identifier" to "login_button"),
        )
        assertThat(resourceId).isEqualTo("login_button")
    }

    @Test
    fun `flt-semantics-identifier wins over the unstable node id and aria-label`() {
        val resourceId = resolveResourceId(
            id = "flt-semantic-node-7",
            ariaLabel = "Log in",
            attributes = mapOf("flt-semantics-identifier" to "login_button"),
        )
        assertThat(resourceId).isEqualTo("login_button")
    }

    @Test
    fun `node id is still used when there is no flt-semantics-identifier`() {
        val resourceId = resolveResourceId(id = "native-dom-id")
        assertThat(resourceId).isEqualTo("native-dom-id")
    }

    @Test
    fun `data-testid is still used when present and no flt identifier exists`() {
        val resourceId = resolveResourceId(
            attributes = mapOf("data-testid" to "submit"),
        )
        assertThat(resourceId).isEqualTo("submit")
    }

    @Test
    fun `an empty flt-semantics-identifier falls through to the next candidate`() {
        val resourceId = resolveResourceId(
            id = "native-dom-id",
            attributes = mapOf("flt-semantics-identifier" to ""),
        )
        assertThat(resourceId).isEqualTo("native-dom-id")
    }

    @Test
    fun `an empty data-testid still yields an empty resource-id (unchanged)`() {
        val resourceId = resolveResourceId(
            attributes = mapOf("data-testid" to ""),
        )
        assertThat(resourceId).isEqualTo("")
    }

    @Test
    fun `a node with no identifying attributes gets no resource-id`() {
        val resourceId = resolveResourceId()
        assertThat(resourceId).isNull()
    }

    // --- harness -------------------------------------------------------------

    private val webScript: String by lazy {
        requireNotNull(javaClass.classLoader.getResource("maestro-web.js")) {
            "maestro-web.js was not found on the test classpath"
        }.readText()
    }

    /**
     * Builds a single element under <body>, runs maestro-web.js over it, and
     * returns the resolved resource-id (null when the key is absent, "" when
     * it is present but empty).
     */
    private fun resolveResourceId(
        tagName: String = "flt-semantics",
        id: String? = null,
        ariaLabel: String? = null,
        name: String? = null,
        title: String? = null,
        htmlFor: String? = null,
        attributes: Map<String, String> = emptyMap(),
    ): String? {
        Context.newBuilder("js").build().use { context ->
            context.eval("js", domScript(tagName, id, ariaLabel, name, title, htmlFor, attributes))
            context.eval("js", webScript)

            val value = context.eval(
                "js",
                "maestro.getContentDescription().children[0].attributes['resource-id'] ?? null",
            )
            return if (value.isNull) null else value.asString()
        }
    }

    private fun domScript(
        tagName: String,
        id: String?,
        ariaLabel: String?,
        name: String?,
        title: String?,
        htmlFor: String?,
        attributes: Map<String, String>,
    ): String {
        val directProps = buildList {
            add("tagName: '$tagName'")
            add("id: '${id.orEmpty()}'")
            ariaLabel?.let { add("ariaLabel: '$it'") }
            name?.let { add("name: '$it'") }
            title?.let { add("title: '$it'") }
            htmlFor?.let { add("htmlFor: '$it'") }
        }.joinToString(",\n          ")

        val attrEntries = attributes.entries.joinToString(",\n            ") { (key, v) ->
            "'$key': { value: '$v' }"
        }

        return """
            globalThis.Node = { TEXT_NODE: 3 };
            globalThis.window = globalThis;
            globalThis.innerWidth = 1024;
            globalThis.innerHeight = 768;

            const element = {
              $directProps,
              attributes: {
                $attrEntries
              },
              childNodes: [{ nodeType: Node.TEXT_NODE, textContent: 'label' }],
              children: [],
              selected: false,
              parentElement: null,
              getBoundingClientRect() {
                return { x: 0, y: 0, width: 100, height: 20 };
              },
            };

            const body = {
              tagName: 'body',
              id: '',
              attributes: {},
              childNodes: [],
              children: [element],
              selected: false,
              parentElement: null,
              getBoundingClientRect() {
                return { x: 0, y: 0, width: 1024, height: 768 };
              },
            };
            element.parentElement = body;

            globalThis.document = {
              body: body,
              readyState: 'complete',
              querySelectorAll() { return []; },
            };

            globalThis.maestro = {};
        """.trimIndent()
    }
}
