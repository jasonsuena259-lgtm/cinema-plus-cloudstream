package com.arabseed
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class ArabSeedRequest(val url:String,val headers:Map<String,String>,val isHls:Boolean)
class ArabSeedBrowser(private val context:Context) {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun media(url:String, referer:String = ArabSeedParser.BASE + "/"):ArabSeedRequest?=suspendCancellableCoroutine { continuation ->
        val handler=Handler(Looper.getMainLooper())
        var view:WebView?=null;var done=false;var agent=""
        fun finish(result:ArabSeedRequest?){handler.post{
            if(!done){done=true;handler.removeCallbacksAndMessages(null);view?.stopLoading();view?.destroy();view=null
                if(continuation.isActive)continuation.resume(result)}
        }}
        continuation.invokeOnCancellation{finish(null)}
        handler.post{
            if(!continuation.isActive)return@post
            try{
                view=WebView(context).apply{
                    agent=settings.userAgentString
                    settings.javaScriptEnabled=true;settings.domStorageEnabled=true
                    settings.allowFileAccess=false;settings.allowContentAccess=false
                    settings.mediaPlaybackRequiresUserGesture=false
                    webViewClient=object:WebViewClient(){
                        override fun onReceivedSslError(v:WebView?,h:android.webkit.SslErrorHandler?,e:android.net.http.SslError?){h?.cancel();finish(null)}
                        override fun onReceivedHttpError(v:WebView?,r:WebResourceRequest?,response:WebResourceResponse?){if(r?.isForMainFrame==true && (response?.statusCode ?: 0)>=400)finish(null)}
                        override fun shouldInterceptRequest(v:WebView,r:WebResourceRequest):WebResourceResponse?{
                            val u=r.url.toString()
                            if(u.contains("challenges.cloudflare.com") || u.contains("/recaptcha/") || u.contains("hcaptcha.com")){finish(null);return WebResourceResponse("text/plain","UTF-8",null)}
                            val hls = ArabSeedMediaUrl.isHls(u)
                            if(hls || ArabSeedMediaUrl.isMp4(u)){
                                val headers=r.requestHeaders.toMutableMap()
                                if(headers.keys.none{it.equals("user-agent",true)})headers["User-Agent"]=agent
                                CookieManager.getInstance().getCookie(u)?.takeIf{it.isNotBlank()}?.let{headers["Cookie"]=it}
                                finish(ArabSeedRequest(u,headers,hls))
                            }
                            return null
                        }
                        override fun onPageFinished(v:WebView?,u:String?){v?.evaluateJavascript("(()=>{let n=0;const t=setInterval(()=>{document.querySelectorAll('video').forEach(v=>v.play().catch(()=>{}));if(++n>=10)clearInterval(t)},1000)})();",null)}
                    }
                    loadUrl(url,mapOf("Referer" to referer))
                }
                handler.postDelayed({finish(null)},15000)
            }catch(_:Exception){finish(null)}
        }
    }
}
