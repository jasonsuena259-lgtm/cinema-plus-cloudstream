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

    /**
     * Uses the actual CineLite search result and interactive season chooser.
     * Only episodes rendered in the site's own season cards are returned.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun seriesEpisodes(id: Int, title: String): List<LiteEpisode> =
        suspendCancellableCoroutine { cont ->
            if (id <= 0 || title.isBlank()) {
                cont.resume(emptyList()); return@suspendCancellableCoroutine
            }
            val handler = Handler(Looper.getMainLooper())
            var view: WebView? = null
            var done = false
            val found = linkedMapOf<String, LiteEpisode>()
            var seasonOptions = listOf<Int>()
            var seasonIndex = 0
            fun finish() {
                handler.post {
                    if (!done) {
                        done = true
                        handler.removeCallbacksAndMessages(null)
                        view?.stopLoading()
                        view?.destroy()
                        view = null
                        if (cont.isActive) cont.resume(found.values.toList())
                    }
                }
            }
            cont.invokeOnCancellation { finish() }
            lateinit var readSeason: () -> Unit
            readSeason = {
                if (!done && cont.isActive && seasonIndex < seasonOptions.size) {
                    val season = seasonOptions[seasonIndex]
                    val parse = """(function(){
                        const cards=Array.from(document.querySelectorAll('div.ad-trigger-play'));
                        const episodes=cards.map(el=>{
                            const span=Array.from(el.querySelectorAll('span')).find(s=>/^EPISODE\s+\d+$/i.test(s.textContent.trim()));
                            const number=span?Number(span.textContent.match(/\d+/)[0]):0;
                            return {number:number,title:el.querySelector('h4')?.textContent?.trim()||''};
                        }).filter(e=>e.number>0&&e.title);
                        return JSON.stringify(episodes);
                    })()"""
                    view?.evaluateJavascript(parse) { raw ->
                        val parsed = runCatching {
                            mapper.readTree(mapper.readTree(raw).asText())
                        }.getOrNull()
                        parsed?.forEach { episode ->
                            val number = episode.path("number").asInt()
                            val episodeName = episode.path("title").asText()
                            val url = CineLiteParser.embedEpisode(id, season, number)
                            if (url != null && episodeName.isNotBlank()) {
                                found[url] = LiteEpisode(episodeName, url, season, number)
                            }
                        }
                        seasonIndex++
                        if (seasonIndex >= seasonOptions.size || !cont.isActive) {
                            finish()
                        } else {
                            val next = seasonOptions[seasonIndex]
                            val switchScript = """(function(){
                                const buttons=Array.from(document.querySelectorAll('button'));
                                const current=buttons.find(b=>/^Season\s+\d+$/.test(b.textContent.trim()) &&
                                    !b.className.includes('w-full'));
                                if(!current)return false;
                                current.click();
                                setTimeout(()=>{
                                    const option=Array.from(document.querySelectorAll('button'))
                                        .find(b=>b.textContent.trim()==='Season $next'&&b.className.includes('w-full'));
                                    if(option)option.click();
                                },140);
                                return true;
                            })()"""
                            view?.evaluateJavascript(switchScript) {
                                handler.postDelayed({ if (cont.isActive) readSeason() }, 1400)
                            }
                        }
                    }
                } else finish()
            }
            handler.post {
                if (!cont.isActive) return@post
                try {
                    view = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedSslError(
                                v: WebView?, h: SslErrorHandler?, e: android.net.http.SslError?
                            ) { h?.cancel(); finish() }
                            override fun onPageFinished(v: WebView?, page: String?) {
                                if (page?.let { CineLiteParser.siteUrl(it) } == null) {
                                    finish(); return
                                }
                                handler.postDelayed({
                                    val safeQuery = mapper.writeValueAsString(title)
                                    val searchScript = """(function(){
                                        const input=document.querySelector('input[type="text"]');
                                        if(!input)return false;
                                        const setter=Object.getOwnPropertyDescriptor(
                                          window.HTMLInputElement.prototype,'value').set;
                                        setter.call(input,$safeQuery);
                                        input.dispatchEvent(new Event('input',{bubbles:true}));
                                        input.dispatchEvent(new Event('change',{bubbles:true}));
                                        return true;
                                    })()"""
                                    v?.evaluateJavascript(searchScript) {
                                        handler.postDelayed({
                                            val selectScript = """(function(){
                                                for(const h of document.querySelectorAll('h3')){
                                                    const key=Object.keys(h).find(k=>k.startsWith('__reactFiber'));
                                                    let f=key?h[key]:null;
                                                    for(let i=0;f&&i<10;i++,f=f.return){
                                                        const item=f.memoizedProps&&f.memoizedProps.item;
                                                        if(item&&item.id===$id&&item.media_type==='tv'){
                                                            const card=h.closest('div.cursor-pointer');
                                                            if(card){card.click();return true;}
                                                        }
                                                    }
                                                }
                                                return false;
                                            })()"""
                                            v?.evaluateJavascript(selectScript) { selected ->
                                                if (selected != "true") { finish(); return@evaluateJavascript }
                                                handler.postDelayed({
                                                    val openSeasons = """(function(){
                                                        const btn=Array.from(document.querySelectorAll('button'))
                                                          .find(x=>/^Season\s+\d+$/.test(x.textContent.trim()) &&
                                                            !x.className.includes('w-full'));
                                                        if(!btn)return false;
                                                        btn.click();return true;
                                                    })()"""
                                                    v.evaluateJavascript(openSeasons) { opened ->
                                                        if (opened != "true") { finish(); return@evaluateJavascript }
                                                        handler.postDelayed({
                                                            v.evaluateJavascript("""(function(){
                                                                const buttons=Array.from(document.querySelectorAll('button'));
                                                                const options=buttons.filter(x=>/^Season\s+\d+$/.test(x.textContent.trim()))
                                                                    .map(x=>Number(x.textContent.trim().match(/\d+/)[0]));
                                                                return JSON.stringify(Array.from(new Set(options)).filter(x=>x>=0&&x<=100).sort((a,b)=>a-b));
                                                            })()""") { result ->
                                                                val seasons = runCatching {
                                                                    mapper.readTree(mapper.readTree(result).asText())
                                                                        .map { it.asInt(-1) }.filter { it >= 0 }
                                                                }.getOrDefault(emptyList())
                                                                seasonOptions = seasons.take(35)
                                                                // Close dropdown; the page initially displays season 1.
                                                                v.evaluateJavascript(openSeasons) {
                                                                    if (seasonOptions.isEmpty()) finish()
                                                                    else handler.postDelayed({ readSeason() }, 220)
                                                                }
                                                            }
                                                        }, 300)
                                                    }
                                                }, 1600)
                                            }
                                        }, 2300)
                                    }
                                }, 1700)
                            }
                        }
                        loadUrl(CineLiteParser.BASE + "/search")
                    }
                    handler.postDelayed({ finish() }, 65000)
                } catch (_: Exception) { finish() }
            }
        }

}
