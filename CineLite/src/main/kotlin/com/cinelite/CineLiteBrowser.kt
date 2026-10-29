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
    val sourceName: String = "CineLite",
    val subtitleLabels: Map<String, String> = emptyMap()
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
                        ) {
                            android.util.Log.w("CineLiteSnapshot", "SSL error in page")
                            handler?.cancel(); finish(null)
                        }
                        override fun onReceivedError(
                            view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true)
                                android.util.Log.w("CineLiteSnapshot",
                                    "Main-frame WebView error code=" + error?.errorCode)
                        }
                        override fun onReceivedHttpError(
                            view: WebView?, request: WebResourceRequest?, error: WebResourceResponse?
                        ) {
                            if (request?.isForMainFrame == true)
                                android.util.Log.w("CineLiteSnapshot",
                                    "Main-frame HTTP status=" + error?.statusCode)
                        }

                        override fun onPageFinished(view: WebView?, page: String?) {
                            if (page?.let { CineLiteParser.siteUrl(it) } == null) {
                                finish(null); return
                            }
                            handler.postDelayed({
                                fun readDom(attempt: Int = 0) {
                                    view?.evaluateJavascript(
                                        """(function(){
                                            const valid=x=>x&&Number.isInteger(x.id)&&x.id>0&&
                                                ['movie','tv','anime'].includes(x.media_type);
                                            function annotate(card,item){
                                                if(!card||!valid(item))return;
                                                card.setAttribute('data-cinelite-id',String(item.id));
                                                card.setAttribute('data-cinelite-type',item.media_type);
                                            }
                                            // Movie/TV cards carry a single React item.
                                            for(const h of document.querySelectorAll('h3')){
                                                const k=Object.keys(h).find(x=>x.startsWith('__reactFiber'));
                                                let f=k?h[k]:null;
                                                for(let i=0;f&&i<14;i++,f=f.return){
                                                    const item=f.memoizedProps?.item;
                                                    if(valid(item)){
                                                        annotate(h.closest('div.cursor-pointer'),item);
                                                        break;
                                                    }
                                                }
                                            }
                                            // Anime carousels expose an items array with AniList IDs.
                                            const norm=x=>String(x||'').trim().toLowerCase()
                                                .replace(/\s+/g,' ');
                                            for(const slider of document.querySelectorAll('.slider-track')){
                                                const key=Object.keys(slider).find(x=>x.startsWith('__reactFiber'));
                                                let f=key?slider[key]:null,items=null;
                                                for(let depth=0;f&&depth<14;depth++,f=f.return)
                                                    if(Array.isArray(f.memoizedProps?.items)){
                                                        items=f.memoizedProps.items;break;
                                                    }
                                                if(!items?.length)continue;
                                                for(const card of slider.children){
                                                    if(!card.querySelector('h3,h4'))continue;
                                                    if(card.hasAttribute('data-cinelite-id'))continue;
                                                    const label=norm(card.querySelector('h3,h4')?.textContent);
                                                    const matches=items.filter(x=>valid(x)&&[
                                                        x.title,x.name,x.displayTitle,x.titleEnglish,
                                                        x.titleRomaji,x.titleUserPreferred
                                                    ].some(name=>norm(name)===label));
                                                    if(matches.length===1)annotate(card,matches[0]);
                                                }
                                            }
                                            // A full React document can exceed Android's
                                            // evaluateJavascript IPC payload limit. Only
                                            // serialize real annotated card metadata.
                                            const esc=x=>String(x||'').replace(/&/g,'&amp;')
                                              .replace(/</g,'&lt;').replace(/>/g,'&gt;')
                                              .replace(/"/g,'&quot;').replace(/'/g,'&#39;');
                                            const seen=new Set(),blocks=[];
                                            const itemHTML=e=>{
                                              const id=Number(e.getAttribute('data-cinelite-id'));
                                              const type=e.getAttribute('data-cinelite-type');
                                              const h=e.querySelector('h3,h4');
                                              const name=(h?.textContent||'').trim();
                                              if(!Number.isInteger(id)||id<1||
                                                !['movie','tv','anime'].includes(type)||!name||
                                                seen.has(type+':'+id))return null;
                                              seen.add(type+':'+id);
                                              const img=e.querySelector('img');
                                              const poster=img?.getAttribute('src')||
                                                img?.getAttribute('data-src')||'';
                                              return '<div data-cinelite-id="'+id+
                                                '" data-cinelite-type="'+esc(type)+'">'+
                                                '<h3>'+esc(name)+'</h3>'+
                                                (poster?'<img src="'+esc(poster)+'">':'')+
                                                '</div>';
                                            };
                                            for(const section of document.querySelectorAll('section')){
                                              if(seen.size>=240)break;
                                              const heading=(section.querySelector('h2')?.textContent||'').trim();
                                              if(!heading)continue;
                                              const items=[...section.querySelectorAll('[data-cinelite-id]')]
                                                .map(itemHTML).filter(Boolean);
                                              if(items.length)blocks.push('<section><h2>'+esc(heading)+
                                                '</h2>'+items.join('')+'</section>');
                                            }
                                            const remaining=[...document.querySelectorAll('[data-cinelite-id]')]
                                              .slice(0,500).map(itemHTML).filter(Boolean);
                                            if(remaining.length)blocks.push(
                                              '<section><h2>More on CineLite</h2>'+
                                              remaining.join('')+'</section>');
                                            return JSON.stringify({
                                                html:blocks.join(''),
                                                count:seen.size
                                            });
                                        })()"""
                                    ) { raw ->
                                        val payload = runCatching {
                                            mapper.readTree(mapper.readTree(raw).asText())
                                        }.getOrNull()
                                        val html = payload?.path("html")?.asText()
                                        val count = payload?.path("count")?.asInt(0) ?: 0
                                        android.util.Log.i("CineLiteSnapshot",
                                            "Cards=" + count + " payloadBytes=" + (html?.length ?: 0))
                                        if (count == 0 && attempt < 3) {
                                            handler.postDelayed({ readDom(attempt + 1) }, 750)
                                        } else finish(html?.takeIf { it.isNotBlank() })
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
            val subtitleLabels = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())
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
                                if (CineLiteParser.isSubtitleFile(target)) {
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
                                        val labels = synchronized(subtitleLabels) {
                                            subtitleLabels.toMap()
                                        }
                                        finish(LiteMediaRequest(target, headers, subtitles,
                                            subtitleLabels = labels))
                                    }, 2100)
                                }
                                return null
                            }

                            override fun onPageFinished(view: WebView?, page: String?) {
                                // Open the player's actual subtitle controls while
                                // the same stream session is alive. No external subtitle
                                // API calls, guessed subtitles or token logging.
                                fun collect(openMenu: Boolean) {
                                    if (done) return
                                    val js = """(function(){
                                        const open=$openMenu;
                                        const label=e=>(e?.getAttribute?.('srclang')||
                                          e?.getAttribute?.('label')||
                                          e?.getAttribute?.('aria-label')||
                                          e?.closest?.('[role=option],li')?.textContent||
                                          e?.textContent||'').trim().slice(0,90);
                                        if(open){
                                          const btn=[...document.querySelectorAll('button,[role=button]')]
                                            .find(x=>/audio\s*&\s*subs|subtitles|captions/i.test(
                                              (x.textContent||'').trim()));
                                          if(btn)btn.click();
                                        }
                                        const tracks=[];
                                        const seen=new Set();
                                        for(const e of document.querySelectorAll(
                                          'track[src],a[href],[data-subtitle-url],[data-subtitle-src]')){
                                          const raw=e.src||e.href||e.getAttribute('data-subtitle-url')||
                                            e.getAttribute('data-subtitle-src');
                                          if(!raw)continue;
                                          try {
                                            const u=new URL(raw,location.href);
                                            if(u.protocol!=='https:'||
                                               !/\.(srt|vtt|ass|ssa)$/i.test(u.pathname))continue;
                                            if(seen.has(u.href))continue;
                                            seen.add(u.href);
                                            tracks.push({url:u.href,name:label(e)});
                                            if(tracks.length===50)break;
                                          }catch(err){}
                                        }
                                        return JSON.stringify(tracks);
                                    })()"""
                                    view?.evaluateJavascript(js) { raw ->
                                        runCatching {
                                            mapper.readTree(mapper.readTree(raw).asText()).forEach { item ->
                                                val link = item.path("url").asText()
                                                if (CineLiteParser.isSubtitleFile(link)) {
                                                    subtitleUrls.add(link)
                                                    val title = item.path("name").asText().trim()
                                                    if (title.isNotBlank()) subtitleLabels[link] = title
                                                }
                                            }
                                        }
                                    }
                                }
                                collect(false)
                                handler.postDelayed({ collect(true) }, 250)
                                handler.postDelayed({ collect(false) }, 1000)
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
                    handler.postDelayed({ finish(null) }, 8500)
                } catch (_: Exception) { finish(null) }
            }
        }

    /**
     * Uses the actual CineLite search result and interactive season chooser.
     * Only episodes rendered in the site's own season cards are returned.
     */
    /**
     * CineLite Anime uses AniList-backed carousel items and an episode grid,
     * NOT the movie/TV search card or Season dropdown. Only episodes whose
     * clickable buttons were actually rendered in the matching Anime modal
     * are returned; catalogue counts and upcoming titles are not playable.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun animeEpisodes(id: Int, title: String): List<LiteEpisode> =
        suspendCancellableCoroutine { cont ->
            if (id <= 0 || title.isBlank()) {
                cont.resume(emptyList()); return@suspendCancellableCoroutine
            }
            val handler = Handler(Looper.getMainLooper())
            var view: WebView? = null
            var done = false
            var clickAttempts = 0
            var episodeAttempts = 0
            var searchRequested = false
            val episodes = linkedMapOf<Int, LiteEpisode>()
            fun finish() {
                handler.post {
                    if (done) return@post
                    done = true
                    handler.removeCallbacksAndMessages(null)
                    view?.stopLoading()
                    view?.destroy()
                    view = null
                    if (cont.isActive) cont.resume(
                        CineLiteParser.orderedEpisodes(episodes.values.toList())
                    )
                }
            }
            cont.invokeOnCancellation { finish() }

            lateinit var readEpisodes: () -> Unit
            readEpisodes = read@{
                if (done || !cont.isActive) return@read
                val script = """(function(){
                    const buttons=[...document.querySelectorAll('button,[role=button]')]
                        .filter(e=>!e.disabled&&e.getClientRects().length>0);
                    const found=new Map();
                    for(const button of buttons){
                        const text=(button.textContent||'').trim().replace(/\s+/g,' ');
                        const m=/^Episode\s*(\d+)(?:\b|~)/i.exec(text) ||
                            /^Play\s*Ep(?:isode)?\s*(\d+)\b/i.exec(text);
                        if(!m)continue;
                        const number=Number(m[1]);
                        if(!Number.isInteger(number)||number<1||number>10000)continue;
                        found.set(number,{number:number,title:'Episode '+number});
                    }
                    return JSON.stringify([...found.values()]);
                })()"""
                view?.evaluateJavascript(script) { raw ->
                    if (done) return@evaluateJavascript
                    val parsed = runCatching {
                        mapper.readTree(mapper.readTree(raw).asText())
                    }.getOrNull()
                    parsed?.forEach { item ->
                        val number = item.path("number").asInt(-1)
                        val url = CineLiteParser.watchAnimeEpisode(id, number)
                        if (url != null) episodes[number] = LiteEpisode(
                            item.path("title").asText("Episode $number"),
                            url, 1, number
                        )
                    }
                    if (episodes.isNotEmpty() || episodeAttempts >= 5) finish()
                    else {
                        episodeAttempts++
                        handler.postDelayed({ readEpisodes() }, 700)
                    }
                }
            }

            lateinit var openCard: () -> Unit
            openCard = open@{
                if (done || !cont.isActive) return@open
                val safeTitle = mapper.writeValueAsString(title)
                val script = """(function(){
                    const id=$id;
                    const requested=$safeTitle;
                    const norm=x=>String(x||'').trim().toLowerCase().replace(/\s+/g,' ');
                    for(const slider of document.querySelectorAll('.slider-track')){
                        const key=Object.keys(slider).find(x=>x.startsWith('__reactFiber'));
                        let fiber=key?slider[key]:null,items=null;
                        for(let depth=0;fiber&&depth<15;depth++,fiber=fiber.return){
                            if(Array.isArray(fiber.memoizedProps?.items)){
                                items=fiber.memoizedProps.items;break;
                            }
                        }
                        const item=items?.find(x=>x?.id===id&&x?.media_type==='anime');
                        if(!item)continue;
                        const names=[item.title,item.name,item.displayTitle,item.titleEnglish,
                            item.titleRomaji,item.titleUserPreferred,requested]
                            .map(norm).filter(Boolean);
                        for(const card of slider.children){
                            const h=card.querySelector('h3,h4');
                            if(h&&names.includes(norm(h.textContent))){
                                const target=card.matches('[class*=cursor-pointer]')?
                                    card:card.closest('[class*=cursor-pointer]');
                                if(target){target.click();return true;}
                            }
                        }
                    }
                    // Independently annotated Anime cards are also valid if
                    // their ID came from a real React item, never from a title guess.
                    const annotated=document.querySelector(
                        '[data-cinelite-id="$id"][data-cinelite-type="anime"]');
                    if(annotated){annotated.click();return true;}
                    // A title may be absent from today's rotating Anime carousels
                    // but present in CineLite's real search results. Open only an
                    // item whose React identity and title both match the request.
                    for(const heading of document.querySelectorAll('h3,h4')){
                        const label=norm(heading.textContent);
                        if(label!==norm(requested))continue;
                        const key=Object.keys(heading).find(x=>x.startsWith('__reactFiber'));
                        let fiber=key?heading[key]:null;
                        for(let depth=0;fiber&&depth<14;depth++,fiber=fiber.return){
                            const item=fiber.memoizedProps?.item;
                            if(item?.id===id&&(item?.media_type==='anime'||item?.media_type==='tv')){
                                const card=heading.closest('[class*=cursor-pointer]');
                                if(card){card.click();return true;}
                            }
                        }
                    }
                    return false;
                })()"""
                view?.evaluateJavascript(script) { result ->
                    if (done) return@evaluateJavascript
                    if (result == "true") handler.postDelayed({ readEpisodes() }, 1250)
                    else if (clickAttempts >= 5 && !searchRequested) {
                        // The section carousel rotates daily; search this
                        // exact title instead of treating every missing carousel
                        // card as an unreleased show.
                        searchRequested = true
                        clickAttempts = 0
                        view?.loadUrl(CineLiteParser.BASE + "/search")
                    } else if (clickAttempts >= 5) finish()
                    else {
                        clickAttempts++
                        handler.postDelayed({ openCard() }, 750)
                    }
                }
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
                                if (searchRequested) {
                                    val query = mapper.writeValueAsString(title)
                                    handler.postDelayed({
                                        val js = """(function(){
                                            const input=document.querySelector('input[type=text]');
                                            if(!input)return false;
                                            const setter=Object.getOwnPropertyDescriptor(
                                                HTMLInputElement.prototype,'value')?.set;
                                            if(!setter)return false;
                                            setter.call(input,$query);
                                            input.dispatchEvent(new Event('input',{bubbles:true}));
                                            input.dispatchEvent(new Event('change',{bubbles:true}));
                                            return true;
                                        })()"""
                                        view?.evaluateJavascript(js) {
                                            handler.postDelayed({ openCard() }, 1600)
                                        }
                                    }, 700)
                                } else handler.postDelayed({ openCard() }, 1600)
                            }
                        }
                        loadUrl(CineLiteParser.BASE + "/anime")
                    }
                    handler.postDelayed({ finish() }, 15500)
                } catch (_: Exception) { finish() }
            }
        }

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
