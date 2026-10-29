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

/** Account entry is visible and user-operated. No password, cookie or token is exported. */
class VipSession(private val context:Context){
    private val ui=Handler(Looper.getMainLooper());private val pages=Mutex()
    private val mapper=jacksonObjectMapper()
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
    suspend fun page(raw:String, render:Boolean=false):Document {
        val url=VipParser.site(raw) ?: throw ErrorLoadingException("CimaNow VIP: رابط خارج الموقع")
        val r=app.get(url,headers=headers(url),timeout=12)
        if(r.url.contains("/signin/"))throw ErrorLoadingException("CimaNow VIP: افتح إعدادات الإضافة ⚙ وسجّل الدخول بحسابك، ثم أعد المحاولة")
        if(r.code !in 200..299)throw ErrorLoadingException("CimaNow VIP: الموقع أعاد HTTP ${r.code}")
        val doc=Jsoup.parse(r.text,r.url)
        if(!render&&doc.selectFirst("h1,article,ul#eps,iframe")!=null)return doc
        return pages.withLock { rendered(url) }
    }
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun rendered(url:String):Document=suspendCancellableCoroutine{cont->
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
                                    if(html!=null&&Jsoup.parse(html).selectFirst("h1,article,ul#eps,iframe")!=null)finish(html,u)
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
}
