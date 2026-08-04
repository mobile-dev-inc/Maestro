package maestro.android.chromedevtools

import maestro.TreeNode
import java.io.Closeable

// Reads on-screen WebView DOMs via Chrome DevTools. Seam so the fetch can be faked in tests.
interface ChromeDevToolsClient : Closeable {
    fun getWebViewTreeNodes(): List<TreeNode>
}
