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
            val path = u.encodedPath.lowercase()
            path.endsWith(".srt") || path.endsWith(".vtt") ||
                path.endsWith(".ass") || path.endsWith(".ssa") ||
                (u.host.endsWith("subtitlecat.com") && path.startsWith("/subs/"))
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
                                  const wait=ms=>new Promise(ok=>setTimeout(ok,ms));
                                  const visible=e=>e.getClientRects().length>0;
                                  const text=e=>(e.textContent||'').trim().replace(/\s+/g,' ').slice(0,65);
                                  const eligible=e=>{
                                    const t=text(e);
                                    return visible(e)&&t.length>1&&t.length<52&&
                                      !/^(exit|cast|play|pause|auto|10|settings|audio & subs|subtitles)$/i.test(t);
                                  };
                                  function harvestSubs(){
                                    [...document.querySelectorAll('track[src],a[href],a[data-href]')]
                                      .forEach(el=>{
                                        const raw=el.src||el.href||el.getAttribute('data-href');
                                        if(!raw)return;
                                        try{
                                          const u=new URL(raw,location.href);
                                          if(u.protocol==='https:'&&
                                           (/\.(srt|vtt|ass|ssa)$/i.test(u.pathname) ||
                                            (u.hostname.endsWith('subtitlecat.com')&&u.pathname.startsWith('/subs/')))){
                                            window.CineLiteSubtitles?.collect(text(el),u.href);
                                          }
                                        }catch(e){}
                                      });
                                  }
                                  async function walk(){
                                    harvestSubs();
                                    const open=[...document.querySelectorAll('button,[role=button]')]
                                      .filter(eligible).find(e=>/^(clove|reyna|chamber|neon)|sources|servers|agents/i.test(text(e)));
                                    if(open)open.click();
                                    await wait(280);
                                    harvestSubs();
                                    const candidates=[...document.querySelectorAll('[role=option],button,[role=menuitem],[class*=cursor-pointer]')]
                                      .filter(eligible).filter(e=>{
                                        const t=text(e);
                                        return /^(clove|reyna|chamber|neon|vault|viper|server|source)/i.test(t) ||
                                          /\b(?:sub|dub|server|source)\b/i.test(t) ||
                                          e.matches('[role=option],[role=menuitem]');
                                      });
                                    const seen=new Set(), options=[];
                                    for(const e of candidates){
                                      const t=text(e);
                                      if(!seen.has(t.toLowerCase())){
                                        seen.add(t.toLowerCase());options.push(t);
                                      }
                                    }
                                    for(const label of options.slice(0,9)){
                                      const el=[...document.querySelectorAll('button,[role=option],[role=menuitem],[class*=cursor-pointer]')]
                                        .find(e=>visible(e)&&text(e)===label);
                                      if(!el)continue;
                                      bridge?.select(label);
                                      el.click();
                                      document.querySelectorAll('video').forEach(v=>{v.muted=true;v.play().catch(()=>{});});
                                      await wait(530);
                                      harvestSubs();
                                      const reopen=[...document.querySelectorAll('button')]
                                        .find(e=>visible(e)&&text(e)===label);
                                      if(reopen)reopen.click();
                                      await wait(110);
                                    }
                                    const captions=[...document.querySelectorAll('button')]
                                      .find(e=>/audio\s*&\s*subs|subtitles|captions/i.test(text(e)));
                                    if(captions){captions.click();await wait(200);harvestSubs();}
                                    document.querySelectorAll('video').forEach(v=>{v.muted=true;v.play().catch(()=>{});});
                                  }
                                  walk().catch(()=>{});
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
                    handler.postDelayed({ complete() }, 7300)
                } catch (_: Exception) { complete() }
            }
        }
}
