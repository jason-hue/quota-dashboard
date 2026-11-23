package com.knifefire.quotaboard.nativeui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 一次性迁移：旧 WebView 版把账号存在 localStorage(key=quota-board-v1, origin=https://localhost)。
 * 切到原生入口后 SharedPreferences 读不到它，这里用一个临时 WebView 以相同 origin 加载空白页，
 * 读出旧数据交给 Repository.importLegacy()，凭证和已缓存额度都不丢。
 *
 * 用法：在 Activity 里持有实例引用（避免 WebView 被 GC），start() 后等待 onDone 回调。
 */
class LegacyMigration(
    private val ctx: Context,
    private val repo: Repository,
    private val onDone: (count: Int) -> Unit
) {
    private var webView: WebView? = null
    private var handled = false
    private val completed = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val timeout = Runnable { finish(0) }

    companion object {
        private const val ORIGIN = "https://localhost/"
        /** 迁移期间保持实例强引用，避免临时 WebView 在异步回调前被 GC */
        private val active = mutableSetOf<LegacyMigration>()

        suspend fun run(ctx: Context, repo: Repository): Int = suspendCancellableCoroutine { cont ->
            val m = LegacyMigration(ctx, repo) { n -> if (cont.isActive) cont.resume(n) }
            active.add(m)
            cont.invokeOnCancellation { active.remove(m) }
            m.start()
            // finish 里统一移除
        }
    }

    fun start() {
        if (repo.isMigrated() || repo.loadAccounts().isNotEmpty()) {
            repo.markMigrated()
            finish(0)
            return
        }
        try {
            val wv = WebView(ctx)
            webView = wv
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.databaseEnabled = true
            wv.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?, request: WebResourceRequest?
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    if (url.startsWith("https://localhost")) {
                        val html = "<!doctype html><html><head><meta charset=\"utf-8\"></head><body></body></html>"
                        return WebResourceResponse(
                            "text/html", "UTF-8",
                            ByteArrayInputStream(html.toByteArray(StandardCharsets.UTF_8))
                        )
                    }
                    return null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (handled) return
                    handled = true
                    view ?: return finish(0)
                    val js = "(function(){try{return localStorage.getItem('${repo.legacyKey}')||'';}" +
                            "catch(e){return '';}})()"
                    view.evaluateJavascript(js) { value -> handleValue(value) }
                }

                override fun onReceivedError(
                    view: WebView?, errorCode: Int, description: String?, failingUrl: String?
                ) {
                    // 主文档被拦截返回时一般不走这里；万一失败，静默结束，不影响新用户
                    if (!handled) { handled = true; finish(0) }
                }
            }
            // 兜底：5 秒内没有回调就放弃迁移，避免卡住界面
            main.postDelayed(timeout, 5000)
            wv.loadUrl(ORIGIN)
        } catch (e: Exception) {
            finish(0)
        }
    }

    private fun handleValue(value: String?) {
        main.removeCallbacks(timeout)
        var raw = value ?: ""
        if (raw.isEmpty() || raw == "null") return finish(0)
        // evaluateJavascript 返回的是 JS 字符串的 JSON 表示，需要解一层引号
        if (raw.startsWith("\"")) {
            raw = try { JSONObject("{\"v\":$raw}").getString("v") } catch (e: Exception) { "" }
        }
        val count = try { repo.importLegacy(raw) } catch (e: Exception) { 0 }
        repo.markMigrated()
        finish(count)
    }

    private fun finish(count: Int) {
        if (!completed.compareAndSet(false, true)) return
        main.removeCallbacks(timeout)
        active.remove(this)
        val wv = webView
        webView = null
        try { wv?.stopLoading(); wv?.destroy() } catch (e: Exception) { /* ignore */ }
        onDone(count)
    }
}
