package maestro.drivers

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import maestro.TreeNode
import org.openqa.selenium.NoSuchFrameException
import org.openqa.selenium.NoSuchWindowException
import org.openqa.selenium.StaleElementReferenceException
import org.slf4j.LoggerFactory

internal data class IframeViewportParams(
    val viewportX: Double,
    val viewportY: Double,
    val viewportWidth: Double,
    val viewportHeight: Double,
)

internal object WebHierarchy {

    private const val CROSS_ORIGIN_IFRAME_ATTRIBUTE = "__crossOriginIframe"

    private val objectMapper = jacksonObjectMapper()
    private val logger = LoggerFactory.getLogger(WebHierarchy::class.java)

    fun normalizeDomNode(value: Any?, context: String): Map<String, Any>? {
        val node = value as? Map<*, *> ?: return malformed(context, "expected object")
        val attrs = normalizeAttributes(node["attributes"], context) ?: return null
        val rawChildren = node["children"] as? List<*> ?: return malformed(context, "missing children")
        val children = rawChildren.mapIndexedNotNull { index, child ->
            normalizeDomNode(child, "$context child $index")
        }

        return mapOf(
            "attributes" to attrs,
            "children" to children,
        )
    }

    fun parseDomJson(json: String?, context: String): Map<String, Any>? {
        val decoded = parseJson(json, context) ?: return null
        return normalizeDomNode(decoded, context)
    }

    fun parseIframeViewportParams(json: String?, iframeSrc: String): IframeViewportParams? {
        val context = "iframe viewport params for $iframeSrc"
        val params = parseJson(json, context) as? Map<*, *> ?: return malformed(context, "expected object")

        val viewportX = (params["viewportX"] as? Number)?.toDouble()
            ?: return malformed(context, "missing viewportX")
        val viewportY = (params["viewportY"] as? Number)?.toDouble()
            ?: return malformed(context, "missing viewportY")
        val viewportWidth = (params["viewportWidth"] as? Number)?.toDouble()
            ?: return malformed(context, "missing viewportWidth")
        val viewportHeight = (params["viewportHeight"] as? Number)?.toDouble()
            ?: return malformed(context, "missing viewportHeight")

        return IframeViewportParams(
            viewportX = viewportX,
            viewportY = viewportY,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        )
    }

    fun injectCrossOriginIframes(
        node: Map<String, Any>,
        fetchIframeContent: (String) -> Map<String, Any>?,
    ): Map<String, Any> {
        val attrs = node["attributes"] as? Map<*, *> ?: return node
        val iframeSrc = attrs[CROSS_ORIGIN_IFRAME_ATTRIBUTE] as? String

        if (iframeSrc != null) {
            val iframeContent = try {
                fetchIframeContent(iframeSrc)
            } catch (e: Exception) {
                logIframeFetchFailure("Failed to fetch cross-origin iframe $iframeSrc", e)
                null
            }
            if (iframeContent != null) {
                normalizeDomNode(iframeContent, "cross-origin iframe $iframeSrc")?.let {
                    return it
                }
            }

            return mapOf(
                "attributes" to copyAttributes(attrs, includeIframeMarker = false),
                "children" to emptyList<Map<String, Any>>(),
            )
        }

        val children = (node["children"] as? List<*>)
            .orEmpty()
            .mapIndexedNotNull { index, child -> normalizeDomNode(child, "web hierarchy child $index") }
            .map { injectCrossOriginIframes(it, fetchIframeContent) }

        return mapOf(
            "attributes" to copyAttributes(attrs),
            "children" to children,
        )
    }

    fun parseDomAsTreeNodes(domRepresentation: Map<String, Any>): TreeNode {
        val attrs = domRepresentation["attributes"] as? Map<*, *> ?: emptyMap<String, Any>()

        val attributes = mutableMapOf(
            "text" to ((attrs["text"] as? String) ?: ""),
        )
        (attrs["bounds"] as? String)?.let {
            attributes["bounds"] = it
        }
        (attrs["resource-id"] as? String)?.let {
            attributes["resource-id"] = it
        }
        (attrs["selected"] as? Boolean)?.let {
            attributes["selected"] = it.toString()
        }
        (attrs["synthetic"] as? Boolean)?.let {
            attributes["synthetic"] = it.toString()
        }
        (attrs["ignoreBoundsFiltering"] as? Boolean)?.let {
            attributes["ignoreBoundsFiltering"] = it.toString()
        }

        val children = (domRepresentation["children"] as? List<*>)
            .orEmpty()
            .mapIndexedNotNull { index, child -> normalizeDomNode(child, "web hierarchy child $index") }
            .map { parseDomAsTreeNodes(it) }

        return TreeNode(attributes = attributes, children = children)
    }

    fun isTransientBrowserContextError(e: Throwable): Boolean {
        return e is StaleElementReferenceException ||
            e is NoSuchFrameException ||
            e is NoSuchWindowException
    }

    private fun logIframeFetchFailure(message: String, e: Exception) {
        if (isTransientBrowserContextError(e)) {
            logger.debug(message, e)
        } else {
            logger.warn(message, e)
        }
    }

    private fun parseJson(json: String?, context: String): Any? {
        if (json == null) {
            return malformed(context, "empty JSON")
        }

        return try {
            objectMapper.readValue(json, Any::class.java)
        } catch (e: Exception) {
            logger.debug("Skipping malformed web hierarchy data from $context", e)
            null
        }
    }

    private fun normalizeAttributes(value: Any?, context: String): Map<String, Any>? {
        val attrs = value as? Map<*, *> ?: return malformed(context, "missing attributes")
        val text = attrs["text"] as? String ?: return malformed(context, "missing text")
        val bounds = attrs["bounds"] as? String ?: return malformed(context, "missing bounds")

        val normalized = mutableMapOf<String, Any>(
            "text" to text,
            "bounds" to bounds,
        )

        (attrs["resource-id"] as? String)?.let {
            normalized["resource-id"] = it
        }
        (attrs["selected"] as? Boolean)?.let {
            normalized["selected"] = it
        }
        (attrs["synthetic"] as? Boolean)?.let {
            normalized["synthetic"] = it
        }
        (attrs["ignoreBoundsFiltering"] as? Boolean)?.let {
            normalized["ignoreBoundsFiltering"] = it
        }
        (attrs[CROSS_ORIGIN_IFRAME_ATTRIBUTE] as? String)?.let {
            normalized[CROSS_ORIGIN_IFRAME_ATTRIBUTE] = it
        }

        return normalized
    }

    private fun copyAttributes(attrs: Map<*, *>, includeIframeMarker: Boolean = true): Map<String, Any> {
        return attrs.entries
            .mapNotNull { (key, value) ->
                if (key !is String || value == null) return@mapNotNull null
                if (!includeIframeMarker && key == CROSS_ORIGIN_IFRAME_ATTRIBUTE) return@mapNotNull null
                key to value
            }
            .toMap()
    }

    private fun <T> malformed(context: String, reason: String): T? {
        logger.debug("Skipping malformed web hierarchy data from $context: $reason")
        return null
    }
}
