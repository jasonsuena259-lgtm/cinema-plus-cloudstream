package com.cinelite

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicBoolean

data class LiteMediaRequest(
    val url: String,
    val headers: Map<String, String>,
    val subtitleUrls: List<String> = emptyList(),
    val sourceName: String = "CineLite"
)

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
    suspend fun media(url: String, referer: String, preferredServer: String? = null): LiteMediaRequest? =
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            var webView: WebView? = null
            var done = false
            var agent = ""
            val captured = AtomicBoolean(false)
            val subtitleUrls = java.util.Collections.synchronizedSet(mutableSetOf<String>())
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
                                val subPath = request.url.encodedPath.orEmpty().lowercase()
                                if (request.url.scheme == "https" &&
                                    listOf(".srt", ".vtt", ".ass", ".ssa").any { subPath.endsWith(it) }
                                ) {
                                    subtitleUrls.add(target)
                                }
                                if (CineLiteParser.mediaKind(target) != null
                                    && captured.compareAndSet(false, true)) {
                                    val headers = request.requestHeaders.toMutableMap()
                                    if (headers.keys.none { it.equals("User-Agent", true) })
                                        headers["User-Agent"] = agent
                                    if (headers.keys.none { it.equals("Referer", true) })
                                        headers["Referer"] = url
                                    CookieManager.getInstance().getCookie(target)?.takeIf {
                                        it.isNotBlank()
                                    }?.let { headers["Cookie"] = it }
                                    // Let the browser's original fetch complete before
                                    // closing its WebView. Some signed HLS sessions are
                                    // registered by that first request, and stopping it
                                    // immediately can invalidate an otherwise usable URL.
                                    handler.postDelayed({
                                        val subtitles = synchronized(subtitleUrls) {
                                            subtitleUrls.take(40).toList()
                                        }
                                        finish(LiteMediaRequest(target, headers, subtitles))
                                    }, 1600)
                                }
                                return null
                            }

                            override fun onPageFinished(view: WebView?, page: String?) {
                                // Track only real subtitle anchors/HTML tracks from the
                                // loaded player. Signed subtitle links remain in memory.
                                view?.evaluateJavascript("""(function(){
                                    return JSON.stringify([...document.querySelectorAll('track[src],a[href]')]
                                        .map(x=>x.src||x.href).filter(Boolean).filter(x=>{
                                            try {
                                                const u=new URL(x,location.href);
                                                return u.protocol==='https:' &&
                                                  /\.(srt|vtt|ass|ssa)$/i.test(u.pathname);
                                            }catch(e){return false;}
                                        }).slice(0,40));
                                })()""") { raw ->
                                    runCatching {
                                        mapper.readTree(mapper.readTree(raw).asText())
                                            .forEach { subtitleUrls.add(it.asText()) }
                                    }
                                }
                                view?.evaluateJavascript(
                                    "(function(){document.querySelectorAll('video').forEach(v=>{v.muted=true;v.play().catch(()=>{})})})()",
                                    null
                                )
                                if (!preferredServer.isNullOrBlank()) handler.postDelayed({
                                    // Select an agent only when its label exists in the
                                    // player's live DOM; never manufacture a media URL.
                                    val name = mapper.writeValueAsString(preferredServer)
                                    view?.evaluateJavascript("""(function(){
                                        const wanted=$name;
                                        const find=t=>[...document.querySelectorAll('button,[role=button],span')]
                                            .find(x=>x.textContent?.trim()===t);
                                        const click=x=>{
                                            const target=x?.closest('button,[role=button],[class*=cursor-pointer]')||x;
                                            if(target){target.click();return true;}return false;
                                        };
                                        const now=[...document.querySelectorAll('button')]
                                            .find(x=>/Reyna|Chamber|Neon|Clove/i.test(x.textContent||''));
                                        if(now)click(now);
                                        setTimeout(()=>{
                                            const selected=find(wanted);
                                            if(selected)click(selected);
                                            document.querySelectorAll('video').forEach(v=>{
                                                v.muted=true;v.play().catch(()=>{});
                                            });
                                        },350);
                                        return true;
                                    })()""", null)
                                }, 1300)
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
            var seasonReadRetries = 0
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
                        // The live CineLite episode cards use a React "group
                        // cursor-pointer" div, not the old "ad-trigger-play" CSS class.
                        // Scan only real EPISODE markers and their actual h4 titles.
                        const episodes=[...document.querySelectorAll('span,[data-episode],[data-episode-number]')]
                          .map(el=>{
                            const text=(el.textContent||'').trim();
                            const m=/^(?:EPISODE|EP\.?|الحلقة)\s*#?\s*(\d+)$/i.exec(text);
                            const no=Number(el.getAttribute('data-episode-number')||
                              el.getAttribute('data-episode')||m?.[1]||0);
                            if(!Number.isInteger(no)||no<1)return null;
                            let card=el.closest('div.group.cursor-pointer')||
                              el.closest('div[class*="cursor-pointer"]')||
                              el.closest('a[href]');
                            const title=card?.querySelector('h4,h3,[data-episode-title]')?.textContent?.trim()||
                              ('Episode '+no);
                            return {number:no,title};
                          }).filter(Boolean);
                        const distinct=[...new Map(episodes.map(e=>[e.number,e])).values()];
                        const btn=[...document.querySelectorAll('button,[role=button]')]
                            .find(x=>/^(?:Season|Saison|الموسم)\s+\d+$/i.test(x.textContent.trim()) &&
                                (x.getAttribute('aria-expanded')!==null ||
                                  !x.closest('[role=menu],[role=listbox]')));
                        const active=btn ? Number(btn.textContent.trim().match(/\d+/)[0]) : -1;
                        return JSON.stringify({season:active,episodes:distinct});
                    })()"""
                    view?.evaluateJavascript(parse) { raw ->
                        val parsed = runCatching {
                            mapper.readTree(mapper.readTree(raw).asText())
                        }.getOrNull()
                        // Match the season actually displayed by the website, not
                        // the requested menu index (which may start with Specials/0).
                        val displayedSeason = parsed?.path("season")?.asInt(-1)
                            ?.takeIf { it >= 0 } ?: season
                        parsed?.path("episodes")?.forEach { episode ->
                            val number = episode.path("number").asInt()
                            val episodeName = episode.path("title").asText()
                            val url = CineLiteParser.embedEpisode(id, displayedSeason, number)
                            if (url != null && episodeName.isNotBlank()) {
                                found[url] = LiteEpisode(episodeName, url, displayedSeason, number)
                            }
                        }
                        // A series overlay often renders its episode carousel
                        // after the season button. Retry the same season while
                        // content hydrates instead of falsely marking it empty.
                        if ((parsed?.path("episodes")?.size() ?: 0) == 0
                            && seasonReadRetries < 3 && cont.isActive) {
                            seasonReadRetries++
                            handler.postDelayed({ readSeason() }, 650)
                            return@evaluateJavascript
                        }
                        seasonReadRetries = 0
                        seasonIndex++
                        if (seasonIndex >= seasonOptions.size || !cont.isActive) {
                            finish()
                        } else {
                            val next = seasonOptions[seasonIndex]
                            val switchScript = """(function(){
                                const buttons=Array.from(document.querySelectorAll('button'));
                                const label=b=>(b.textContent||'').trim();
                                const current=buttons.find(b=>/^(?:Season|الموسم)\s+\d+$/i.test(label(b))&&
                                    (b.getAttribute('aria-expanded')!==null||!b.closest('[role=menu],[role=listbox]')));
                                if(!current)return false;
                                current.click();
                                setTimeout(()=>{
                                    const option=[...document.querySelectorAll('button,[role=option],[role=menuitem]')]
                                        .find(b=>(label(b)==='Season $next'||label(b)==='الموسم $next')&&
                                            b!==current&&b.getClientRects().length>0);
                                    if(option)option.click();
                                },160);
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
                                                        const btn=[...document.querySelectorAll('button,[role=button]')]
                                                          .find(x=>/^(?:Season|Saison|الموسم)\s+\d+$/i.test(x.textContent.trim())&&
                                                            (x.getAttribute('aria-expanded')!==null||
                                                              !x.closest('[role=menu],[role=listbox]')));
                                                        if(!btn)return false;
                                                        btn.click();return true;
                                                    })()"""
                                                    v?.evaluateJavascript(openSeasons) { opened ->
                                                        if (opened != "true") {
                                                            // CineLite sometimes renders only one
                                                            // season without a selector. Still read
                                                            // the actual visible episode cards.
                                                            seasonOptions = listOf(1)
                                                            handler.postDelayed({ readSeason() }, 220)
                                                            return@evaluateJavascript
                                                        }
                                                        handler.postDelayed({
                                                            v?.evaluateJavascript("""(function(){
                                                                const buttons=Array.from(document.querySelectorAll('button'));
                                                                const options=buttons.filter(x=>/^(?:Season|Saison|الموسم)\s+\d+$/i.test(x.textContent.trim()))
                                                                    .map(x=>Number(x.textContent.trim().match(/\d+/)[0]));
                                                                return JSON.stringify(Array.from(new Set(options)).filter(x=>x>=0&&x<=100).sort((a,b)=>a-b));
                                                            })()""") { result ->
                                                                val seasons = runCatching {
                                                                    mapper.readTree(mapper.readTree(result).asText())
                                                                        .map { it.asInt(-1) }.filter { it >= 0 }
                                                                }.getOrDefault(emptyList())
                                                                seasonOptions = seasons.take(35)
                                                                // Close dropdown; the page initially displays season 1.
                                                                v?.evaluateJavascript(openSeasons) {
                                                                    if (seasonOptions.isEmpty())
                                                                        seasonOptions = listOf(1)
                                                                    handler.postDelayed({ readSeason() }, 220)
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
