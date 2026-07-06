package maestro.android.chromedevtools

import maestro.TreeNode
import java.io.Closeable

/**
 * Reads the DOM of on-screen WebViews via the Chrome DevTools Protocol. Extracted so the
 * hierarchy-augmentation path can be driven with a fake in tests (the production impl talks
 * to a live device over ADB sockets). See [DadbChromeDevToolsClient].
 */
interface ChromeDevToolsClient : Closeable {
    fun getWebViewTreeNodes(): List<TreeNode>
}
