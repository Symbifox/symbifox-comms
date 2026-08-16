package com.bluefoxconsultant.sms.ui.mail

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

/**
 * Renders an email body.
 *
 * The server already sanitizes and parks remote images, but this WebView still
 * gets the belt-and-braces treatment, because what it renders is markup an
 * attacker wrote:
 *
 * - **No JavaScript.** Off by default in a WebView; spelled out here so nobody
 *   turns it on for a layout quirk.
 *  - **No file or content access**, so a crafted body can't read local files.
 * - **No navigation.** Any link the reader taps leaves for the real browser
 *   instead of loading inside the message.
 * - **No network at all** when images are blocked: every subresource request is
 *   answered with an empty response. Server-side parking handles `<img src>`,
 *   this also stops CSS `url()` backgrounds, which are the other way a message
 *   phones home.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MailBodyView(
    html: String,
    allowRemoteContent: Boolean,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val textColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    val background = androidx.compose.material3.MaterialTheme.colorScheme.surface

    // AndroidView's update block runs after EVERY recomposition, not only when
    // these inputs change. Reloading the WebView each time would throw the
    // reader back to the top of a long email whenever anything else on screen
    // moved — a snackbar, the theme, a sibling expanding. Load only on a real
    // content change.
    val rendered = remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.loadsImagesAutomatically = true
                settings.builtInZoomControls = false
                isVerticalScrollBarEnabled = false
                setBackgroundColor(Color.Transparent.toArgb())
            }
        },
        update = { view ->
            val key = html to allowRemoteContent
            if (rendered.value != key) {
                rendered.value = key
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        // Hand every tapped link to the system browser.
                        request?.url?.toString()?.let { runCatching { uriHandler.openUri(it) } }
                        return true
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        if (allowRemoteContent) return null
                        val scheme = request?.url?.scheme?.lowercase()
                        if (scheme == "http" || scheme == "https") {
                            return WebResourceResponse(
                                "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)),
                            )
                        }
                        return null
                    }
                }
                view.loadDataWithBaseURL(
                    null,
                    wrap(html, textColor, background),
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        },
    )
}

/**
 * Wraps the body so it reads on a phone: the app's own colours, a viewport that
 * doesn't force a horizontal scroll, and images capped to the screen width —
 * desktop mail routinely embeds 1200px-wide tables and signature banners.
 */
private fun wrap(body: String, textColor: Color, background: Color): String {
    fun hex(color: Color): String = String.format("#%06X", 0xFFFFFF and color.toArgb())
    return """
        <!DOCTYPE html>
        <html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          html, body {
            margin: 0; padding: 0;
            background: ${hex(background)};
            color: ${hex(textColor)};
            font-family: -apple-system, Roboto, sans-serif;
            font-size: 15px; line-height: 1.45;
            word-wrap: break-word; overflow-wrap: break-word;
          }
          img { max-width: 100% !important; height: auto !important; }
          table { max-width: 100% !important; }
          pre { white-space: pre-wrap; word-wrap: break-word; }
          blockquote {
            border-left: 3px solid rgba(128,128,128,0.4);
            margin: 8px 0; padding-left: 10px; color: rgba(128,128,128,1);
          }
          a { color: #29ABE2; }
        </style>
        </head><body>$body</body></html>
    """.trimIndent()
}
