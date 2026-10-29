package com.cinelite

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class LiteMediaRequest(val url: String, val headers: Map<String, String>)

/**
 * Executes only the website's own JavaScript in an isolated Android WebView.
 * Does not attempt to bypass login, challenges, or protected content.
 */
class CineLiteBrowser(private val context: Context) {
    private val mapper = jacksonObjectMapper()

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun snapshot(url: String, searchQuery: String? = null): String? = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        var done = false
        fun finish(result: String?) {
            handler.post {
                if (!done) {
                    done = true
                    handler.removeCallbacksAndMessages(null)
                    webView?.stopLoading()
                    webView?.destroy()
                    webView = null
                    if (cont.isActive) cont.resume(result)
                }
            }
        }
        cont.invokeOnCancellation { finish(null) }
        handler.post {
            if (!cont.isActive) return@post
            try {
                webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedSslError(
                            view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?
                        ) { handler?.cancel(); finish(null) }

                        override fun onPageFinished(view: WebView?, page: String?) {
                            if (page?.let { CineLiteParser.siteUrl(it) } == null) {
                                finish(null); return
                            }
                            handler.postDelayed({
                                fun readDom() {
                                    view?.evaluateJavascript(
                                        """(function(){
                                            for(const h of document.querySelectorAll('h3')){
                                                const key=Object.keys(h).find(k=>k.startsWith('__reactFiber'));
                                                let f=key?h[key]:null;
                                                for(let i=0;f&&i<10;i++,f=f.return){
                                                    const item=f.memoizedProps&&f.memoizedProps.item;
                                                    if(item&&Number.isInteger(item.id)&&item.id>0&&
                                                        (item.media_type==='movie'||item.media_type==='tv')){
                                                        const card=h.closest('div.cursor-pointer');
                                                        if(card){
                                                            card.setAttribute('data-cinelite-id',String(item.id));
                                                            card.setAttribute('data-cinelite-type',item.media_type);
                                                        }
                                                        break;
                                                    }
                                                }
                                            }
                                            return document.documentElement.outerHTML;
                                        })()"""
                                    ) { raw ->
                                        val html = runCatching { mapper.readTree(raw).asText() }.getOrNull()
                                        finish(html?.takeIf { it.isNotBlank() })
                                    }
                                }
                                if (searchQuery == null) {
                                    readDom()
                                } else {
                                    val safeQuery = mapper.writeValueAsString(searchQuery)
                                    val action = """(function(){
                                        const input=document.querySelector('input[type="text"]');
                                        if(!input)return false;
                                        const setter=Object.getOwnPropertyDescriptor(
                                            window.HTMLInputElement.prototype,'value').set;
                                        setter.call(input,$safeQuery);
                                        input.dispatchEvent(new Event('input',{bubbles:true}));
                                        input.dispatchEvent(new Event('change',{bubbles:true}));
                                        return true;
                                    })()"""
                                    view?.evaluateJavascript(action) {
                                        handler.postDelayed({ readDom() }, 2300)
                                    }
                                }
                            }, 1800)
                        }
                    }
                    loadUrl(url)
                }
                handler.postDelayed({ finish(null) }, 23000)
            } catch (_: Exception) { finish(null) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun media(url: String, referer: String): LiteMediaRequest? =
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            var webView: WebView? = null
            var done = false
            var agent = ""
            fun finish(result: LiteMediaRequest?) {
                handler.post {
                    if (!done) {
                        done = true
                        handler.removeCallbacksAndMessages(null)
                        webView?.stopLoading()
                        webView?.destroy()
                        webView = null
                        if (cont.isActive) cont.resume(result)
                    }
                }
            }
            cont.invokeOnCancellation { finish(null) }
            handler.post {
                if (!cont.isActive) return@post
                try {
                    webView = WebView(context).apply {
                        agent = settings.userAgentString
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedSslError(
                                view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?
                            ) { handler?.cancel(); finish(null) }

                            override fun shouldInterceptRequest(
                                view: WebView?, request: WebResourceRequest
                            ): WebResourceResponse? {
                                val target = request.url.toString()
                                if (Regex("""(?i)\.(?:m3u8|mp4)(?:\?|$)""").containsMatchIn(target)) {
                                    val headers = request.requestHeaders.toMutableMap()
                                    if (headers.keys.none { it.equals("User-Agent", true) })
                                        headers["User-Agent"] = agent
                                    if (headers.keys.none { it.equals("Referer", true) })
                                        headers["Referer"] = url
                                    CookieManager.getInstance().getCookie(target)?.takeIf {
                                        it.isNotBlank()
                                    }?.let { headers["Cookie"] = it }
                                    finish(LiteMediaRequest(target, headers))
                                }
                                return null
                            }

                            override fun onPageFinished(view: WebView?, page: String?) {
                                view?.evaluateJavascript(
                                    "(function(){document.querySelectorAll('video').forEach(v=>{v.muted=true;v.play().catch(()=>{})})})()",
                                    null
                                )
                            }
                        }
                        loadUrl(url, mapOf("Referer" to referer))
                    }
                    handler.postDelayed({ finish(null) }, 18000)
                } catch (_: Exception) { finish(null) }
            }
        }
}
