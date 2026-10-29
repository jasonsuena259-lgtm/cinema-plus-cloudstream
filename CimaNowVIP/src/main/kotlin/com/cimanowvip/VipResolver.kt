package com.cimanowvip

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import kotlin.coroutines.resume

class VipResolver(private val session:VipSession?=null) {
    private val agent="Mozilla/5.0"
    private val client by lazy {app.baseClient.newBuilder().followRedirects(false).followSslRedirects(false).callTimeout(8,java.util.concurrent.TimeUnit.SECONDS).build()}
    private suspend fun request(req:Request):Response=suspendCancellableCoroutine{cont->
        val call=client.newCall(req);cont.invokeOnCancellation{call.cancel()}
        call.enqueue(object:Callback{
            override fun onFailure(call:Call,e:java.io.IOException){if(cont.isActive)cont.resumeWith(Result.failure(e))}
            override fun onResponse(call:Call,response:Response){
                cont.resume(response,onCancellation={_,value,_->value.close()})
            }
        })
    }
    /** Normal free-download form. Stops on a challenge; never downloads the whole file to parse HTML. */
    suspend fun forafile(url:String):String? {
        var current=url;var body:RequestBody?=null
        repeat(5){
            val req=Request.Builder().url(current).header("User-Agent",agent).header("Referer",url).apply{body?.let{post(it)}}.build()
            request(req).use{r->
                if(r.code in 300..399){current=r.header("Location")?.let{r.request.url.resolve(it)}?.takeIf{it.scheme=="https"}?.toString() ?: return null;body=null}
                else if(!r.isSuccessful)return null
                else if(r.header("Content-Type").orEmpty().startsWith("video/"))return r.request.url.toString()
                else{
                    val stream=r.body?.byteStream() ?: return null;val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(bytes.size()<512000){val n=stream.read(buffer,0,minOf(buffer.size,512000-bytes.size()));if(n<0)break;bytes.write(buffer,0,n)}
                    val html=bytes.toString("UTF-8")
                    if(html.contains("h-captcha")||html.contains("g-recaptcha")||html.contains("cf-turnstile"))return null
                    val doc=Jsoup.parse(html,current);val form=doc.selectFirst("form[name=F1]") ?: return null
                    if(body!=null)return null
                    val action=form.absUrl("action").ifBlank{current}.toHttpUrlOrNull() ?: return null
                    if(action.host!="forafile.com")return null
                    val fb=FormBody.Builder();form.select("input[type=hidden][name]").forEach{fb.add(it.attr("name"),it.attr("value"))}
                    body=fb.build();current=action.toString()
                }
            }
        }
        return null
    }
    suspend fun native(raw:String,subtitle:(SubtitleFile)->Unit):List<ExtractorLink>{
        val url=VipParser.unwrap(raw) ?: return emptyList();val u=url.toHttpUrlOrNull() ?: return emptyList()
        val direct=VipParser.jetload(url) ?: if(u.host=="forafile.com")forafile(url) else if(u.encodedPath.endsWith(".mp4")||u.encodedPath.endsWith(".m3u8"))url else null
        if(direct!=null)return listOf(newExtractorLink("CimaNow VIP",u.host,direct,if(direct.substringBefore('?').endsWith(".m3u8"))ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO){referer=url;headers=if(VipParser.site(direct)!=null)session?.headers(direct) ?: mapOf("User-Agent" to agent) else mapOf("User-Agent" to agent)})
        val links=mutableListOf<ExtractorLink>();loadExtractor(url,VipParser.BASE+"/",subtitle,links::add);return links
    }
    suspend fun resolve(urls:List<String>,subtitle:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean {
        val lock=Mutex();val sent=mutableSetOf<String>();val subsSent=mutableSetOf<String>()
        VipDispatcher.run(urls.distinct()){url->
            val subs=mutableListOf<SubtitleFile>()
            val links=native(url,subs::add)
            for(link in links)if(VipLinkValidator.validate(link))lock.withLock{
                if(sent.add(link.url+link.referer+link.headers.toSortedMap()))callback(link)
                subs.forEach{if(subsSent.add(it.url))subtitle(it)}
            }
        }
        if(sent.isEmpty())throw ErrorLoadingException("CimaNow VIP: لم يُتحقق من فيديو صالح؛ قد يتطلب المستضيف تسجيل دخول أو تحققًا يدويًا")
        return true
    }
}
