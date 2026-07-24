package dev.mobile.maestro.fixture.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import dev.mobile.maestro.fixture.FixtureEmitter

/**
 * Loads the EXACT markup of a real-world payment page (captured via CDP from a production
 * incident where the page's form subtree — including its plain submit button — was
 * persistently absent from the Android accessibility projection while sibling static
 * content serialized). Served from assets so the page arrives via a URL load (closer to
 * the incident's conditions than loadData).
 *
 * The page is untouched; the consuming-element oracle is wired from the WebView side:
 * onPageFinished injects a click listener that calls the exported JS bridge, so a tap
 * that actually reaches the Confirm button emits WEB_CONFIRM_TAP.
 */
object WebViewScreen {
    @SuppressLint("SetJavaScriptEnabled")
    fun install(activity: Activity) {
        val root = FrameLayout(activity)
        val webView = WebView(activity)
        webView.settings.javaScriptEnabled = true
        webView.addJavascriptInterface(Bridge, "MaestroFixtureBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(
                    """
                    (function() {
                      var buttons = document.getElementsByTagName('button');
                      for (var i = 0; i < buttons.length; i++) {
                        var b = buttons[i];
                        if (b.textContent.trim() === 'Confirm') {
                          b.addEventListener('click', function(e) {
                            e.preventDefault();
                            MaestroFixtureBridge.confirmTapped();
                          });
                        }
                      }
                    })();
                    """.trimIndent(),
                    null,
                )
                FixtureEmitter.emit("WEB_PAGE_LOADED")
            }
        }
        webView.loadUrl("file:///android_asset/signin.html")
        // Same idiom as the native screens (raw-px top margin): keep the page clear of the
        // window decor — API 35+ edge-to-edge lays the content view out under the app bar.
        root.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply { topMargin = 250 },
        )
        activity.setContentView(root)
    }

    private object Bridge {
        @JavascriptInterface
        fun confirmTapped() {
            FixtureEmitter.emit("WEB_CONFIRM_TAP", mapOf("target" to "confirm"))
        }
    }
}
