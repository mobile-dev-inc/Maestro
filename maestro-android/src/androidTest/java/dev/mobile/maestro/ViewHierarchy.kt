package dev.mobile.maestro

import android.app.UiAutomation
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.util.Xml
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.GridLayout
import android.widget.GridView
import android.widget.ListView
import android.widget.TableLayout
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.mobile.maestro.AccessibilityNodeInfoExt.getHintOrFallback
import org.xmlpull.v1.XmlSerializer
import java.io.IOException
import java.io.OutputStream

// Logic largely copied from AccessibilityNodeInfoDumper with some modifications
object ViewHierarchy {

    private const val LOGTAG = "Maestro"

    fun dump(
        device: UiDevice,
        uiAutomation: UiAutomation,
        out: OutputStream,
        toastNode: AccessibilityNodeInfo? = null
    ) {
        val windowManager = InstrumentationRegistry.getInstrumentation()
            .context
            .getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val displayRect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
        }


        val serializer = Xml.newSerializer()
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
        serializer.setOutput(out, "UTF-8")
        serializer.startDocument("UTF-8", true)
        serializer.startTag("", "hierarchy")
        serializer.attribute("", "rotation", Integer.toString(device.displayRotation))

        val roots = try {
            device.javaClass
                .getDeclaredMethod("getWindowRoots")
                .apply {
                    isAccessible = true
                }
                .let {
                    @Suppress("UNCHECKED_CAST")
                    it.invoke(device) as Array<AccessibilityNodeInfo>
                }
                .toList()
        } catch (e: Exception) {
            // Falling back to a public method if reflection fails
            Log.e(LOGTAG, "Unable to call getWindowRoots", e)
            listOf(uiAutomation.rootInActiveWindow)
        }

        roots.forEach {
            dumpNodeRec(
                it,
                serializer,
                0,
                displayRect
            )
        }
        addToastNode(toastNode, serializer, displayRect)

        serializer.endTag("", "hierarchy")
        serializer.endDocument()
    }

    private fun addToastNode(
        toastNode: AccessibilityNodeInfo?,
        serializer: XmlSerializer,
        displayRect: Rect
    ) {
        if (toastNode != null) {
            serializer.apply {
                startTag("", "node")
                attribute("", "index", "0")
                attribute("", "class", toastNode.className.toString())
                attribute("", "text", toastNode.text.toString())
                attribute("", "visible-to-user", toastNode.isVisibleToUser.toString())
                attribute("", "checkable", toastNode.isCheckable.toString())
                attribute("", "clickable", toastNode.isClickable.toString())
                attribute("", "bounds", getVisibleBoundsInScreen(toastNode, displayRect)?.toShortString())
                endTag("", "node")
            }
        }
    }

    private val NAF_EXCLUDED_CLASSES = arrayOf(
        GridView::class.java.name, GridLayout::class.java.name,
        ListView::class.java.name, TableLayout::class.java.name
    )

    @Suppress("LongParameterList")
    @Throws(IOException::class)
    private fun dumpNodeRec(
        node: AccessibilityNodeInfo,
        serializer: XmlSerializer,
        index: Int,
        displayRect: Rect,
        insideWebView: Boolean = false,
    ) {
        serializer.startTag("", "node")
        if (!nafExcludedClass(node) && !nafCheck(node)) {
            serializer.attribute("", "NAF", java.lang.Boolean.toString(true))
        }
        serializer.attribute("", "index", Integer.toString(index))
        serializer.attribute("", "hintText", safeCharSeqToString(node.getHintOrFallback()))
        serializer.attribute("", "text", safeCharSeqToString(node.text))
        serializer.attribute("", "resource-id", safeCharSeqToString(node.viewIdResourceName))
        serializer.attribute("", "class", safeCharSeqToString(node.className))
        serializer.attribute("", "package", safeCharSeqToString(node.packageName))
        serializer.attribute("", "content-desc", safeCharSeqToString(node.contentDescription))
        serializer.attribute("", "checkable", java.lang.Boolean.toString(node.isCheckable))
        serializer.attribute("", "checked", java.lang.Boolean.toString(node.isChecked))
        serializer.attribute("", "clickable", java.lang.Boolean.toString(node.isClickable))
        serializer.attribute("", "enabled", java.lang.Boolean.toString(node.isEnabled))
        serializer.attribute("", "focusable", java.lang.Boolean.toString(node.isFocusable))
        serializer.attribute("", "focused", java.lang.Boolean.toString(node.isFocused))
        serializer.attribute("", "scrollable", java.lang.Boolean.toString(node.isScrollable))
        serializer.attribute("", "long-clickable", java.lang.Boolean.toString(node.isLongClickable))
        serializer.attribute("", "password", java.lang.Boolean.toString(node.isPassword))
        serializer.attribute("", "selected", java.lang.Boolean.toString(node.isSelected))
        serializer.attribute("", "visible-to-user", java.lang.Boolean.toString(node.isVisibleToUser))
        serializer.attribute(
            "", "bounds", getVisibleBoundsInScreen(node, displayRect)?.toShortString()
        )
        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i)
            if (child != null) {
                // This condition is different from the original.
                // Original implementation has a bug where contents of a WebView sometimes reported as invisible.
                // This is a workaround for that bug.
                if (child.isVisibleToUser || insideWebView) {
                    dumpNodeRec(
                        child,
                        serializer, i,
                        displayRect,
                        insideWebView || child.className == "android.webkit.WebView"
                    )
                    child.recycle()
                } else {
                    Log.i(LOGTAG, "Skipping invisible child: $child")
                }
            } else {
                Log.i(LOGTAG, "Null child $i/$count, parent: $node")
            }
        }
        serializer.endTag("", "node")
    }

    /**
     * The list of classes to exclude my not be complete. We're attempting to
     * only reduce noise from standard layout classes that may be falsely
     * configured to accept clicks and are also enabled.
     *
     * @param node
     * @return true if node is excluded.
     */
    private fun nafExcludedClass(node: AccessibilityNodeInfo): Boolean {
        val className = safeCharSeqToString(node.className)
        for (excludedClassName in NAF_EXCLUDED_CLASSES) {
            if (className.endsWith(excludedClassName)) return true
        }
        return false
    }

    /**
     * We're looking for UI controls that are enabled, clickable but have no
     * text nor content-description. Such controls configuration indicate an
     * interactive control is present in the UI and is most likely not
     * accessibility friendly. We refer to such controls here as NAF controls
     * (Not Accessibility Friendly)
     *
     * @param node
     * @return false if a node fails the check, true if all is OK
     */
    private fun nafCheck(node: AccessibilityNodeInfo): Boolean {
        val isNaf = (node.isClickable && node.isEnabled
            && safeCharSeqToString(node.contentDescription).isEmpty()
            && safeCharSeqToString(node.text).isEmpty())
        return if (!isNaf) true else childNafCheck(node)

        // check children since sometimes the containing element is clickable
        // and NAF but a child's text or description is available. Will assume
        // such layout as fine.
    }

    /**
     * This should be used when it's already determined that the node is NAF and
     * a further check of its children is in order. A node maybe a container
     * such as LinerLayout and may be set to be clickable but have no text or
     * content description but it is counting on one of its children to fulfill
     * the requirement for being accessibility friendly by having one or more of
     * its children fill the text or content-description. Such a combination is
     * considered by this dumper as acceptable for accessibility.
     *
     * @param node
     * @return false if node fails the check.
     */
    @Suppress("ReturnCount")
    private fun childNafCheck(node: AccessibilityNodeInfo): Boolean {
        val childCount = node.childCount
        for (x in 0 until childCount) {
            val childNode = node.getChild(x)
            if (!safeCharSeqToString(childNode.contentDescription).isEmpty()
                || !safeCharSeqToString(childNode.text).isEmpty()
            ) return true
            if (childNafCheck(childNode)) return true
        }
        return false
    }

    private fun safeCharSeqToString(cs: CharSequence?): String {
        return cs?.let { stripInvalidXMLChars(it) } ?: ""
    }

    @Suppress("ComplexCondition")
    private fun stripInvalidXMLChars(cs: CharSequence): String {
        val ret = StringBuffer()
        var ch: Char
        /* http://www.w3.org/TR/xml11/#charsets
        [#x1-#x8], [#xB-#xC], [#xE-#x1F], [#x7F-#x84], [#x86-#x9F], [#xFDD0-#xFDDF],
        [#x1FFFE-#x1FFFF], [#x2FFFE-#x2FFFF], [#x3FFFE-#x3FFFF],
        [#x4FFFE-#x4FFFF], [#x5FFFE-#x5FFFF], [#x6FFFE-#x6FFFF],
        [#x7FFFE-#x7FFFF], [#x8FFFE-#x8FFFF], [#x9FFFE-#x9FFFF],
        [#xAFFFE-#xAFFFF], [#xBFFFE-#xBFFFF], [#xCFFFE-#xCFFFF],
        [#xDFFFE-#xDFFFF], [#xEFFFE-#xEFFFF], [#xFFFFE-#xFFFFF],
        [#x10FFFE-#x10FFFF].
         */for (i in 0 until cs.length) {
            ch = cs[i]
            if (ch.code >= 0x1 && ch.code <= 0x8 || ch.code >= 0xB && ch.code <= 0xC || ch.code >= 0xE && ch.code <= 0x1F ||
                ch.code >= 0x7F && ch.code <= 0x84 || ch.code >= 0x86 && ch.code <= 0x9f ||
                ch.code >= 0xFDD0 && ch.code <= 0xFDDF || ch.code >= 0x1FFFE && ch.code <= 0x1FFFF ||
                ch.code >= 0x2FFFE && ch.code <= 0x2FFFF || ch.code >= 0x3FFFE && ch.code <= 0x3FFFF ||
                ch.code >= 0x4FFFE && ch.code <= 0x4FFFF || ch.code >= 0x5FFFE && ch.code <= 0x5FFFF ||
                ch.code >= 0x6FFFE && ch.code <= 0x6FFFF || ch.code >= 0x7FFFE && ch.code <= 0x7FFFF ||
                ch.code >= 0x8FFFE && ch.code <= 0x8FFFF || ch.code >= 0x9FFFE && ch.code <= 0x9FFFF ||
                ch.code >= 0xAFFFE && ch.code <= 0xAFFFF || ch.code >= 0xBFFFE && ch.code <= 0xBFFFF ||
                ch.code >= 0xCFFFE && ch.code <= 0xCFFFF || ch.code >= 0xDFFFE && ch.code <= 0xDFFFF ||
                ch.code >= 0xEFFFE && ch.code <= 0xEFFFF || ch.code >= 0xFFFFE && ch.code <= 0xFFFFF ||
                ch.code >= 0x10FFFE && ch.code <= 0x10FFFF
            ) ret.append(".") else ret.append(ch)
        }
        return ret.toString()
    }

    // This method is copied from AccessibilityNodeInfoHelper as-is
    private fun getVisibleBoundsInScreen(node: AccessibilityNodeInfo?, displayRect: Rect): Rect? {
        if (node == null) {
            return null
        }
        // targeted node's bounds
        val nodeRect = Rect()
        node.getBoundsInScreen(nodeRect)
        return if (nodeRect.intersect(displayRect)) {
            nodeRect
        } else {
            Rect()
        }
    }

}
