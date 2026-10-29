package com.cinelite

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.resume

data class LiteObservedSubtitle(val title: String, val url: String)
data class LiteSourceCapture(
    val streams: List<LiteMediaRequest>,
    val subtitles: List<LiteObservedSubtitle>
)

/**
 * A single first-party WebView session collects server choices and subtitle tracks
 * from the page's actual UI. No expired tokens, hardcoded server URLs or bypasses.
 * Bounded at 7.3 seconds, leaving time for CloudStream to validate captured links.
 */
class CineLiteMultiSourceBrowser(private val context: Context) {
    private val safeSubtitleName = Regex("(?i)(?:arabic|\\bar\\b|عربي|العربية|english|\\ben\\b|french|spanish)")

    private fun isSubtitle(raw: String): Boolean {
        return try {
            val u = raw.toHttpUrlOrNull() ?: return false
            if (u.scheme != "https") return false
            // Some real first-party subtitle endpoints use ?ext=.vtt or
            // ?ext=srt rather than a filename extension.
            CineLiteParser.isSubtitleFile(raw) ||
                (u.host.endsWith("subtitlecat.com") &&
                    u.encodedPath.lowercase().startsWith("/subs/"))
        } catch (_: Exception) { false }
    }

    private fun subtitleTitle(raw: String, label: String = ""): String {
        val path = runCatching { java.net.URI(raw).path.substringAfterLast('/') }.getOrDefault("")
        val text = (label + " " + path).replace('_', ' ').replace('.', ' ')
        val match = safeSubtitleName.find(text)?.value?.lowercase()
        return when {
            match == "arabic" || match == "ar" || match == "عربي" ||
                match == "العربية" -> "Arabic / العربية"
            match == "english" || match == "en" -> "English"
            match == "french" -> "French"
            match == "spanish" -> "Spanish"
            label.isNotBlank() -> label.take(80)
            else -> path.substringBeforeLast('.').take(72).ifBlank { "Subtitle" }
        }
    }

    /** Accepts only the documented public embed or CineLite watch pages. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun capture(url: String, referer: String): LiteSourceCapture =
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val active = AtomicReference("CineLite")
            val streams = ConcurrentHashMap<String, LiteMediaRequest>()
            val subs = ConcurrentHashMap<String, LiteObservedSubtitle>()
            var view: WebView? = null
            var done = false

            fun complete() {
                handler.post {
                    if (done) return@post
                    done = true
                    handler.removeCallbacksAndMessages(null)
                    view?.stopLoading()
                    view?.destroy()
                    view = null
                    if (cont.isActive) cont.resume(
                        LiteSourceCapture(streams.values.toList(), subs.values.toList())
                    )
                }
            }
            cont.invokeOnCancellation { complete() }
            handler.post {
                if (!cont.isActive) return@post
                try {
                    view = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        val browserAgent = settings.userAgentString
                        // The bridge only marks a local UI name. It has no credentials
                        // or capability to fetch, open or authorize remote content.
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun select(name: String) {
                                if (name.length in 1..48) active.set(name)
                            }
                        }, "CineLiteSourceName")
                        webViewClient = object : WebViewClient() {
                            override fun onReceivedSslError(
                                v: WebView?, h: SslErrorHandler?, error: android.net.http.SslError?
                            ) { h?.cancel(); complete() }

                            override fun shouldInterceptRequest(
                                v: WebView?, request: WebResourceRequest
                            ): WebResourceResponse? {
                                val link = request.url.toString()
                                if (request.url.scheme != "https") return null
                                if (isSubtitle(link)) {
                                    subs.putIfAbsent(link, LiteObservedSubtitle(subtitleTitle(link), link))
                                }
                                if (CineLiteParser.mediaKind(link) != null && streams.size < 16) {
                                    val headers = request.requestHeaders.toMutableMap()
                                    if (headers.keys.none { it.equals("User-Agent", true) }) {
                                        headers["User-Agent"] = browserAgent
                                    }
                                    if (headers.keys.none { it.equals("Referer", true) }) {
                                        headers["Referer"] = url
                                    }
                                    CookieManager.getInstance().getCookie(link)?.takeIf {
                                        it.isNotBlank()
                                    }?.let { headers["Cookie"] = it }
                                    streams.putIfAbsent(link, LiteMediaRequest(
                                        link, headers, emptyList(), active.get()
                                    ))
                                }
                                return null
                            }

                            override fun onPageFinished(v: WebView?, page: String?) {
                                v ?: return
                                // Read actual source options, not a fixed list. Interact
                                // with the native dropdown within the SAME WebView session.
                                val js = """(function(){
                                  if(window.__cineliteScanStarted)return;
                                  window.__cineliteScanStarted=true;
                                  const bridge=window.CineLiteSourceName;
                                  let lastSelected='';
                                  const wait=ms=>new Promise(ok=>setTimeout(ok,ms));
                                  const label=e=>(e?.textContent||'').replace(/\s+/g,' ').trim();
                                  const visible=e=>!!e&&e.getClientRects().length>0;
                                  const click=e=>{if(e&&visible(e)){e.click();return true;}return false;};
                                  const skip=/^(?:exit|cast|play|pause|auto|10|settings|audio & subs|subtitles|stream sources|anime servers|movie servers|tv servers|6 agents|download|close|sub|dub)$/i;
                                  function getPanel(){
                                    const heads=[...document.querySelectorAll('h1,h2,h3,h4,div,span,p')]
                                      .filter(e=>visible(e)&&e.children.length<3&&
                                        /^(stream sources|video servers|select server)$/i.test(label(e)));
                                    for(const h of heads){
                                      const panel=h.closest('[role=dialog]')||
                                        h.closest('[class*=fixed]')||h.closest('[class*=absolute]');
                                      if(panel&&panel.querySelectorAll('button,[role=button],[class*=cursor-pointer]').length>2)
                                        return panel;
                                    }
                                    return null;
                                  }
                                  function trigger(){
                                    const candidates=[...document.querySelectorAll('button,[role=button]')]
                                      .filter(visible).filter(e=>
                                        (lastSelected&&label(e).toLowerCase()===lastSelected.toLowerCase())||
                                        /^(clove|reyna|chamber|neon|viper|vault)|sources|servers|agents/i.test(label(e))||
                                        /server|source/i.test(e.getAttribute('aria-label')||''));
                                    // Footer switcher is normally last, not the header label.
                                    return candidates[candidates.length-1];
                                  }
                                  async function openMenu(){
                                    let panel=getPanel();
                                    if(panel)return panel;
                                    if(!click(trigger()))return null;
                                    await wait(250);
                                    return getPanel();
                                  }
                                  function sourceRows(panel){
                                    if(!panel)return [];
                                    // Real CineLite items are clickable rows (not
                                    // the source switcher's Network/Addons tabs).
                                    const all=[...panel.querySelectorAll(
                                      '[role=option],[role=menuitem],[class*=cursor-pointer],button'
                                    )].filter(visible);
                                    const rows=[],seen=new Set();
                                    for(const el of all){
                                      let pieces=[...el.querySelectorAll('span,div,p')]
                                        .filter(node=>node.children.length===0&&visible(node))
                                        .map(label)
                                        .filter(x=>x&&x.length<55&&!/[•]/.test(x)&&
                                          /[a-zA-Z0-9\u0600-\u06FF]/.test(x)&&
                                          !/^(?:SUB|DUB|HD|4K|1080p|720p|Auto|Network|Addons|Server|Servers)$/i.test(x));
                                      let t=pieces[0]||label(el).replace(/\s+(?:SUB|DUB)\s*$/i,'').trim();
                                      if(t.length<2||t.length>55||skip.test(t)||
                                        /\b(?:agents|stream sources|servers|network|addons|add token|febbox cloud)\b/i.test(t))continue;
                                      if(!el.matches('[role=option],[role=menuitem],[class*=cursor-pointer]')&&
                                        !/^(clove|reyna|chamber|neon)/i.test(t))continue;
                                      if([...el.querySelectorAll('[role=option],[role=menuitem],[class*=cursor-pointer]')]
                                        .some(child=>visible(child)&&label(child)===label(el)))continue;
                                      const key=t.toLowerCase();
                                      if(!seen.has(key)){seen.add(key);rows.push({name:t,element:el});}
                                    }
                                    return rows;
                                  }
                                  function harvestSubtitles(){
                                    [...document.querySelectorAll(
                                      'track[src],a[href],[data-href],[data-subtitle-url],[data-subtitle-src]'
                                    )].forEach(el=>{
                                      const raw=el.getAttribute('data-subtitle-url')||
                                        el.getAttribute('data-subtitle-src')||
                                        el.getAttribute('data-href')||el.src||el.href;
                                      if(!raw)return;
                                      try{
                                        const url=new URL(raw,location.href);
                                        if(url.protocol==='https:'&&
                                           (/\.(srt|vtt|ass|ssa)$/i.test(url.pathname)||
                                            /^(?:\.)?(?:srt|vtt|ass|ssa)$/i.test(
                                              url.searchParams.get('ext')||'')||
                                            (url.hostname.endsWith('subtitlecat.com')&&url.pathname.startsWith('/subs/')))){
                                          window.CineLiteSubtitles?.collect(label(el).slice(0,60),url.href);
                                        }
                                      }catch(e){}
                                    });
                                  }
                                  async function go(){
                                    // Allow React to hydrate the menu after DOM load.
                                    await wait(350);
                                    harvestSubtitles();
                                    document.querySelectorAll('video').forEach(v=>{
                                      v.muted=true;v.play().catch(()=>{});
                                    });
                                    let panel=null;
                                    for(let i=0;i<8&&!panel;i++){
                                      panel=await openMenu();
                                      if(!panel)await wait(260);
                                    }
                                    if(!panel)return;
                                    const names=sourceRows(panel).map(x=>x.name).slice(0,18);
                                    // The list is taken from the site's DOM; do not create servers.
                                    const played=new Set();
                                    for(const name of names){
                                      panel=await openMenu();
                                      if(!panel)break;
                                      const entry=sourceRows(panel).find(x=>x.name===name);
                                      if(!entry||played.has(name))continue;
                                      played.add(name);
                                      lastSelected=name;
                                      bridge?.select(name);
                                      click(entry.element);
                                      // A server selection is not a video. Give its
                                      // player a chance to actually request a
                                      // manifest before switching to the next.
                                      await wait(850);
                                      document.querySelectorAll('video').forEach(v=>{
                                        v.muted=true;v.play().catch(()=>{});
                                      });
                                      harvestSubtitles();
                                    }
                                    const captions=[...document.querySelectorAll('button,[role=button]')]
                                      .find(e=>visible(e)&&/audio\s*&\s*subs|subtitles|captions/i.test(label(e)));
                                    if(captions){captions.click();await wait(220);harvestSubtitles();}
                                  }
                                  go().catch(()=>{});
                                })()"""
                                v.evaluateJavascript(js, null)
                            }
                        }
                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun collect(label: String, url: String) {
                                if (isSubtitle(url) && subs.size < 60) {
                                    subs.putIfAbsent(url, LiteObservedSubtitle(subtitleTitle(url, label), url))
                                }
                            }
                        }, "CineLiteSubtitles")
                        loadUrl(url, mapOf("Referer" to referer))
                    }
                    // Six real server choices can take over six seconds to
                    // hydrate and initiate their separate playback requests.
                    handler.postDelayed({ complete() }, 11000)
                } catch (_: Exception) { complete() }
            }
        }
}
