package com.cimanowvip

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import android.widget.*
import android.view.ViewGroup
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlin.coroutines.resume
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

data class VipCapturedMedia(val url:String,val headers:Map<String,String>,val referer:String)
data class VipPlaybackCapture(val sources:List<String>,val media:List<VipCapturedMedia>)

/** Account entry is visible and user-operated. No password, cookie or token is exported. */
class VipSession(private val context:Context){
    private val ui=Handler(Looper.getMainLooper());private val pages=Mutex()
    private val mapper=jacksonObjectMapper()
    private val homeLock=Mutex()
    private var homeCache:Triple<Int,Long,Document>?=null
    suspend fun home():Document = homeLock.withLock {
        val key=headers(VipParser.BASE+"/home/").hashCode()
        homeCache?.takeIf{it.first==key&&android.os.SystemClock.elapsedRealtime()-it.second<20000}?.let{return@withLock it.third.clone()}
        val doc=page(VipParser.BASE+"/home/")
        homeCache=Triple(key,android.os.SystemClock.elapsedRealtime(),doc)
        doc.clone()
    }
    fun headers(url:String):Map<String,String> {
        if(VipParser.site(url)==null)return emptyMap()
        return buildMap{put("User-Agent",WebSettings.getDefaultUserAgent(context));CookieManager.getInstance().getCookie(url)?.let{put("Cookie",it)}}
    }
    @SuppressLint("SetJavaScriptEnabled")
    fun login(ctx:Context){ui.post{
        val dialog=Dialog(ctx);val layout=LinearLayout(ctx).apply{orientation=LinearLayout.VERTICAL}
        val title=TextView(ctx).apply{text="سجّل الدخول بنفسك في موقع CimaNow. الجلسة تبقى على الجهاز؛ ثم اضغط إغلاق وارجع للإضافة.";setPadding(16,16,16,16)}
        val view=WebView(ctx).apply{
            settings.javaScriptEnabled=true;settings.domStorageEnabled=true;settings.allowFileAccess=false;settings.allowContentAccess=false
            webViewClient=object:WebViewClient(){
                override fun shouldOverrideUrlLoading(v:WebView,r:WebResourceRequest):Boolean = r.isForMainFrame&&VipParser.site(r.url.toString())==null
                override fun onReceivedSslError(v:WebView?,h:SslErrorHandler?,e:android.net.http.SslError?){h?.cancel()}
            }
        }
        val close=Button(ctx).apply{text="إغلاق والعودة للإضافة";setOnClickListener{CookieManager.getInstance().flush();dialog.dismiss()}}
        layout.addView(title);layout.addView(view,LinearLayout.LayoutParams(-1,0,1f));layout.addView(close)
        dialog.setContentView(layout);dialog.setOnDismissListener{view.stopLoading();view.destroy()};dialog.show();dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
        view.loadUrl(VipParser.BASE+"/home/")
    }}
    suspend fun page(raw:String, render:Boolean=false, playback:Boolean=false):Document {
        val url=VipParser.site(raw) ?: throw ErrorLoadingException("CimaNow VIP: رابط خارج الموقع")
        val r=app.get(url,headers=headers(url),timeout=12)
        if(r.url.contains("/signin/"))throw ErrorLoadingException("CimaNow VIP: افتح إعدادات الإضافة ⚙ وسجّل الدخول بحسابك، ثم أعد المحاولة")
        if(r.code !in 200..299)throw ErrorLoadingException("CimaNow VIP: الموقع أعاد HTTP ${r.code}")
        val doc=Jsoup.parse(r.text,r.url)
        if(!render&&doc.selectFirst("h1,article,ul#eps,iframe")!=null)return doc
        return pages.withLock { rendered(url,playback) }
    }
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun rendered(url:String,playback:Boolean=false):Document=suspendCancellableCoroutine{cont->
        var view:WebView?=null;var done=false
        fun finish(html:String?,base:String){ui.post{
            if(!done){done=true;view?.stopLoading();view?.destroy();view=null
                if(cont.isActive){if(html!=null)cont.resume(Jsoup.parse(html,base))else cont.resumeWith(Result.failure(ErrorLoadingException("CimaNow VIP: تعذر قراءة الصفحة؛ افتح إعدادات الإضافة وسجّل الدخول أو أكمل تحقق الموقع يدويًا")))}}
        }}
        cont.invokeOnCancellation{finish(null,url)}
        ui.post{
            if(!cont.isActive)return@post
            try{
                view=WebView(context).apply{
                    settings.javaScriptEnabled=true;settings.domStorageEnabled=true;settings.allowFileAccess=false;settings.allowContentAccess=false
                    webViewClient=object:WebViewClient(){
                        override fun shouldOverrideUrlLoading(v:WebView,r:WebResourceRequest):Boolean = r.isForMainFrame&&VipParser.site(r.url.toString())==null
                        override fun onReceivedSslError(v:WebView?,h:SslErrorHandler?,e:android.net.http.SslError?){h?.cancel();finish(null,url)}
                        override fun onPageFinished(v:WebView?,u:String?){
                            if(u==null||VipParser.site(u)==null||u.contains("/signin/")){finish(null,url);return}
                            fun read(attempt:Int){ui.postDelayed({
                                if(!done)v?.evaluateJavascript("document.documentElement.outerHTML"){raw->
                                    val html=runCatching{mapper.readValue(raw,String::class.java)}.getOrNull()
                                    if(html!=null&&(if(playback)VipParser.sources(Jsoup.parse(html,u)).isNotEmpty() else Jsoup.parse(html).selectFirst("h1,article,ul#eps")!=null))finish(html,u)
                                    else if(attempt<5)read(attempt+1)
                                }
                            },700)}
                            read(0)
                        }
                    }
                    loadUrl(url)
                }
                ui.postDelayed({finish(null,url)},15000)
            }catch(_:Exception){finish(null,url)}
        }
    }
    /** Visible, user-operated route for gates/tabs. Never clicks a challenge or spoofs a browser identity. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun capturePlayback(item:String):VipPlaybackCapture = suspendCancellableCoroutine { cont ->
        val embeds=ConcurrentHashMap.newKeySet<String>()
        val media=ConcurrentHashMap<String,VipCapturedMedia>()
        var dialog:Dialog?=null;var view:WebView?=null;var done=false
        val current=java.util.concurrent.atomic.AtomicReference(item)
        fun finish(accept:Boolean){ui.post{
            if(!done){done=true;view?.stopLoading();view?.destroy();view=null;dialog?.dismiss();dialog=null
                if(cont.isActive){
                    if(accept&&(embeds.isNotEmpty()||media.isNotEmpty()))cont.resume(VipPlaybackCapture(embeds.toList(),media.values.toList()))
                    else cont.resumeWith(Result.failure(ErrorLoadingException("CimaNow VIP: لم يُلتقط سيرفر؛ افتح الحلقة واضغط تشغيل داخل نافذة الموقع ثم متابعة التشغيل")))
                }
            }
        }}
        cont.invokeOnCancellation{finish(false)}
        ui.post{
            if(!cont.isActive)return@post
            val activity=CommonActivity.activity?.takeIf{!it.isFinishing&&!it.isDestroyed}
            if(activity==null){finish(false);return@post}
            try {
                val layout=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
                val label=TextView(activity).apply{text="افتح الحلقة، اختر سيرفرًا واضغط تشغيل، ثم «متابعة التشغيل». أكمل تحقق الموقع يدويًا إن ظهر.";setPadding(16,12,16,12)}
                val location=TextView(activity).apply{setPadding(16,4,16,4)}
                val button=Button(activity).apply{text="متابعة التشغيل";setOnClickListener{finish(true)}}
                val back=Button(activity).apply{text="رجوع داخل الموقع";setOnClickListener{view?.let{if(it.canGoBack())it.goBack()}}}
                view=WebView(activity).apply {
                    settings.javaScriptEnabled=true;settings.domStorageEnabled=true
                    settings.allowFileAccess=false;settings.allowContentAccess=false
                    settings.mediaPlaybackRequiresUserGesture=true
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this,true)
                    webViewClient=object:WebViewClient(){
                        override fun shouldOverrideUrlLoading(v:WebView,r:WebResourceRequest):Boolean = r.isForMainFrame&&r.url.scheme!="https"
                        override fun onReceivedSslError(v:WebView?,h:SslErrorHandler?,e:android.net.http.SslError?){h?.cancel()}
                        override fun onPageStarted(v:WebView?,u:String?,icon:android.graphics.Bitmap?){
                            if(u!=null){current.set(u);location.text=u.toHttpUrlOrNull()?.host.orEmpty()}
                        }
                        override fun shouldInterceptRequest(v:WebView,r:WebResourceRequest):WebResourceResponse? {
                            val top=current.get();val raw=r.url.toString()
                            if(VipParser.site(top)!=null&&top.contains("/watching/")&&!VipParser.trailer(raw)){
                                if(VipParser.hostSource(raw)&&!VipParser.directMedia(raw)){
                                    val path=r.url.path.orEmpty()
                                    if(path.contains("embed")||path.startsWith("/e/")||path.contains("video")||path.startsWith("/doc")||path=="/")embeds.add(raw)
                                }
                                if(VipParser.directMedia(raw)){
                                    val ref=r.requestHeaders.entries.firstOrNull{it.key.equals("referer",true)}?.value.orEmpty()
                                    // Capture media only from the site/player context, never YouTube or an ad frame.
                                    if((VipParser.site(ref)!=null&&ref.contains("/watching/"))||VipParser.hostSource(ref)){
                                        val headers=r.requestHeaders.filterKeys{it.lowercase() in setOf("user-agent","origin","accept")}.toMutableMap()
                                        CookieManager.getInstance().getCookie(raw)?.takeIf{it.isNotBlank()}?.let{headers["Cookie"]=it}
                                        media[raw]=VipCapturedMedia(raw,headers,ref)
                                    }
                                }
                            }
                            return null
                        }
                        override fun onPageFinished(v:WebView?,u:String?){
                            if(u==null||VipParser.site(u)==null||!u.contains("/watching/"))return
                            fun read(attempt:Int){ui.postDelayed({
                                if(!done)v?.evaluateJavascript("document.documentElement.outerHTML"){result->
                                    val html=runCatching{mapper.readValue(result,String::class.java)}.getOrNull()
                                    if(html!=null)embeds.addAll(VipParser.sources(Jsoup.parse(html,u)))
                                    if(attempt<12)read(attempt+1)
                                }
                            },800)}
                            read(0)
                        }
                    }
                }
                layout.addView(label);layout.addView(location);layout.addView(view,LinearLayout.LayoutParams(-1,0,1f));layout.addView(back);layout.addView(button)
                dialog=Dialog(activity).apply{setContentView(layout);setOnCancelListener{finish(false)};show();window?.setLayout(-1,-1)}
                view?.loadUrl(item)
                ui.postDelayed({finish(false)},100000)
            }catch(_:Exception){finish(false)}
        }
    }

}
