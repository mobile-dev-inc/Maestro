package maestro.drivers

import org.graalvm.polyglot.Context

/**
 * Runs the bundled maestro-web.js over a minimal fake DOM in a JS engine so
 * resource-id derivation can be pinned without standing up a browser.
 *
 * The fake mirrors the two ways the real DOM exposes data: reflected IDL
 * properties on the node itself, and the attribute store behind
 * Element.prototype.getAttribute. Tests can make the two diverge to simulate
 * form named-property clobbering, where e.g. form.id returns a child
 * `<input name="id">` element instead of the id attribute string.
 */
object MaestroWebJsHarness {

    private val webScript: String by lazy {
        requireNotNull(javaClass.classLoader.getResource("maestro-web.js")) {
            "maestro-web.js was not found on the test classpath"
        }.readText()
    }

    /**
     * Builds a single element under <body>, runs maestro-web.js over it, and
     * returns the resolved resource-id (null when the key is absent, "" when
     * it is present but empty).
     *
     * [id], [name] and [title] are written to the attribute store and
     * reflected as same-named properties, like on a real element.
     * [clobberedProps] replaces the given properties with a child-element
     * object while leaving the attribute store untouched, the way form
     * named-property access does.
     */
    fun resolveResourceId(
        tagName: String = "flt-semantics",
        id: String? = null,
        ariaLabel: String? = null,
        name: String? = null,
        title: String? = null,
        htmlFor: String? = null,
        attributes: Map<String, String> = emptyMap(),
        clobberedProps: Set<String> = emptySet(),
    ): String? {
        Context.newBuilder("js").build().use { context ->
            context.eval("js", domScript(tagName, id, ariaLabel, name, title, htmlFor, attributes, clobberedProps))
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
        clobberedProps: Set<String>,
    ): String {
        val directProps = buildList {
            add("tagName: '$tagName'")
            if ("id" !in clobberedProps) add("id: '${id.orEmpty()}'")
            ariaLabel?.let { add("ariaLabel: '$it'") }
            if ("name" !in clobberedProps) name?.let { add("name: '$it'") }
            if ("title" !in clobberedProps) title?.let { add("title: '$it'") }
            htmlFor?.let { add("htmlFor: '$it'") }
            clobberedProps.forEach { add("$it: { tagName: 'INPUT', name: '$it' }") }
        }.joinToString(",\n          ")

        // id/name/title are reflected attributes: maestro-web.js reads them via
        // Element.prototype.getAttribute, so they must live in the attribute
        // store as well as on the node.
        val attrEntries = buildMap {
            id?.let { put("id", it) }
            name?.let { put("name", it) }
            title?.let { put("title", it) }
            putAll(attributes)
        }.entries.joinToString(",\n            ") { (key, v) ->
            "'$key': { value: '$v' }"
        }

        return """
            globalThis.Node = { TEXT_NODE: 3 };
            globalThis.window = globalThis;
            globalThis.innerWidth = 1024;
            globalThis.innerHeight = 768;

            // maestro-web.js reads id/name/title through Element.prototype.getAttribute
            // (to bypass form named-property clobbering); back it with the fake
            // attribute store.
            globalThis.Element = {
              prototype: {
                getAttribute(attrName) {
                  const attr = this.attributes[attrName];
                  return attr === undefined ? null : attr.value;
                },
              },
            };

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
