package maestro.drivers

import com.google.common.truth.Truth.assertThat
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Test

/**
 * Exercises the bundled maestro-web.js against the HTML named-control override:
 * HTMLFormElement's named getter shadows the form's own IDL attributes, so a
 * `<form>` containing `<input name="name">` makes `form.name` evaluate to that
 * INPUT ELEMENT rather than a string.
 *
 * Left unguarded, that element becomes the form's resource-id and the
 * hierarchy can no longer be serialized (the element's parentElement
 * back-reference is circular — on real pages React/Vue fibers make it worse).
 * In real runs every subsequent command then fails with
 * "Could not retrieve hierarchy through maestro.getContentDescription()".
 *
 * Forms with a field literally named "name" are common (any "your name" form),
 * so this is easy to hit. See
 * https://github.com/mobile-dev-inc/Maestro/issues/3213.
 */
class WebHierarchyFormNameShadowingTest {

    @Test
    fun `a form whose name property is a child element does not poison the hierarchy`() {
        Context.newBuilder("js").build().use { context ->
            context.eval("js", domScript(nameShadowedByChildElement = true))
            context.eval("js", webScript)

            // Serializability is the property the CDP transport needs, and it is
            // exactly what an element-valued resource-id destroys: the element's
            // parentElement back-reference makes the tree circular.
            val json = context.eval(
                "js",
                "JSON.stringify(maestro.getContentDescription())",
            )
            assertThat(json.isNull).isFalse()

            // The form has no string-valued identifying property, so it must get
            // no resource-id at all — never the element.
            val resourceId = context.eval(
                "js",
                "maestro.getContentDescription().children[0].attributes['resource-id'] ?? null",
            )
            assertThat(resourceId.isNull).isTrue()
        }
    }

    @Test
    fun `a string-valued name is still used as the resource-id (unchanged)`() {
        Context.newBuilder("js").build().use { context ->
            context.eval("js", domScript(nameShadowedByChildElement = false))
            context.eval("js", webScript)

            val resourceId = context.eval(
                "js",
                "maestro.getContentDescription().children[0].attributes['resource-id'] ?? null",
            )
            assertThat(resourceId.asString()).isEqualTo("checkout-form")
        }
    }

    // --- harness (same shape as FlutterWebSemanticsIdentifierTest) ----------

    private val webScript: String by lazy {
        requireNotNull(javaClass.classLoader.getResource("maestro-web.js")) {
            "maestro-web.js was not found on the test classpath"
        }.readText()
    }

    /**
     * body > form > input, where the input's name attribute is "name". With
     * [nameShadowedByChildElement] the form's `name` property IS the input
     * object — exactly what a browser produces for that markup. Without it,
     * the form's `name` stays an ordinary string.
     */
    private fun domScript(nameShadowedByChildElement: Boolean): String {
        val formName = if (nameShadowedByChildElement) "input" else "'checkout-form'"
        return """
            globalThis.Node = { TEXT_NODE: 3 };
            globalThis.window = globalThis;
            globalThis.innerWidth = 1024;
            globalThis.innerHeight = 768;

            const input = {
              tagName: 'input',
              id: '',
              name: 'name',
              attributes: {},
              childNodes: [],
              children: [],
              selected: false,
              parentElement: null,
              getBoundingClientRect() {
                return { x: 0, y: 20, width: 100, height: 20 };
              },
            };

            const form = {
              tagName: 'form',
              id: '',
              attributes: {},
              childNodes: [],
              children: [input],
              selected: false,
              parentElement: null,
              getBoundingClientRect() {
                return { x: 0, y: 0, width: 200, height: 60 };
              },
            };
            // Assigned after construction, the way the browser's named-control
            // getter effectively behaves for <form><input name="name"></form>.
            form.name = $formName;
            input.parentElement = form;

            const body = {
              tagName: 'body',
              id: '',
              attributes: {},
              childNodes: [],
              children: [form],
              selected: false,
              parentElement: null,
              getBoundingClientRect() {
                return { x: 0, y: 0, width: 1024, height: 768 };
              },
            };
            form.parentElement = body;

            globalThis.document = {
              body: body,
              readyState: 'complete',
              querySelectorAll() { return []; },
            };

            globalThis.maestro = {};
        """.trimIndent()
    }
}
