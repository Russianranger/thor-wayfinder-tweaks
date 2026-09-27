package app.wayfinder

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.security.MessageDigest

/**
 * 1.2 — a pinned guide page works offline: a copy of it (a single-file web archive, .mht) is saved
 * in the app's private storage when it's pinned, and refreshed every time it loads online. With no
 * connection — or when the page fails to load (a Wi-Fi login page, a dead site) — the copy opens.
 * Used by both the Guide page and the Home + Y deck's Guide tab.
 */
object GuideCache {
    private fun dir(ctx: Context) = File(ctx.filesDir, "guides").apply { mkdirs() }
    private fun name(key: String) = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(32) + ".mht"
    fun file(ctx: Context, key: String) = File(dir(ctx), name(key))
    fun has(ctx: Context, key: String) = file(ctx, key).length() > 0
    fun drop(ctx: Context, key: String) { file(ctx, key).delete() }

    /** Save what [web] shows as [key]'s offline copy (not a copy of a copy). */
    fun save(ctx: Context, key: String, web: WebView) {
        if (web.url?.startsWith("http") != true) return
        runCatching { web.saveWebArchive(file(ctx, key).absolutePath, false) { } }
    }

    fun online(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(true)

    /** A page showing the saved copy, not the live one. */
    fun isOffline(web: WebView?) = web?.url?.startsWith("file:") == true

    /**
     * Wire [web] for the guide of [key]: load [start] (the pinned page when [pinned], else a search),
     * the saved copy when offline, fall back to it on a failed load, keep it fresh when online.
     * [onFailed] = nothing to show (no copy either) → the caller shows a message.
     */
    fun setup(web: WebView, ctx: Context, key: String, pinned: String?, start: String, onFailed: (Boolean) -> Unit) {
        fun loadCopy(): Boolean {
            if (pinned == null || !has(ctx, key)) return false
            web.settings.allowFileAccess = true   // only for our own saved copy
            web.loadUrl("file://" + file(ctx, key).absolutePath)
            return true
        }
        web.webViewClient = object : WebViewClient() {   // links stay in the guide
            override fun onPageStarted(v: WebView?, url: String?, favicon: android.graphics.Bitmap?) { onFailed(false) }
            override fun onPageFinished(v: WebView?, url: String?) {
                // the pinned page loaded live → refresh its offline copy
                if (pinned != null && url == pinned) save(ctx, key, web)
            }
            override fun onReceivedError(v: WebView?, req: WebResourceRequest?, err: WebResourceError?) {
                if (req?.isForMainFrame != true) return
                if (isOffline(web) || !loadCopy()) onFailed(true)
            }
            override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: android.net.http.SslError?) {
                h?.cancel()   // never proceed past a bad certificate
                // only the page itself failing counts (a broken ad or image doesn't)
                val main = e?.url?.let { it == v?.url || it == start || it == pinned } ?: false
                if (main && (isOffline(web) || !loadCopy())) onFailed(true)
            }
        }
        if (pinned != null && start == pinned && !online(ctx) && loadCopy()) return
        web.loadUrl(start)
    }
}
