package com.myseed
import android.content.Context
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Native public-player extraction first; isolated browser fallback for JS-only hosts. */
class MySeedResolver(private val context:Context?=null){
    private val mapper=jacksonObjectMapper()
    suspend fun native(source:SeedSource,referer:String,subtitles:MutableList<SubtitleFile>):List<ExtractorLink>{
        val result=mutableListOf<ExtractorLink>()
        var page=app.get(source.url,headers=MySeedRepository.headers,referer=referer,timeout=8)
        if(page.code !in 200..299 || page.text.contains("cf-chl-") || page.text.contains("Sorry, you have been blocked"))return emptyList()
        // Follow only the public same-video redirect explicitly supplied by VOE's page.
        if(source.name.equals("Voe",true)){
            val redirect=Regex("""window\.location\.href\s*=\s*['"](https://[^'"]+)['"]""").find(page.text)?.groupValues?.get(1)
            if(redirect!=null)page=app.get(redirect,headers=MySeedRepository.headers,referer=page.url,timeout=8)
        }
        if(source.name.equals("Streamhls",true)&&page.document.selectFirst("form#F1[action=/dl]")!=null){
            val code=page.url.substringAfterLast('/');val target=page.url.toHttpUrlOrNull()?.resolve("/dl") ?: return emptyList()
            page=app.post(target.toString(),headers=MySeedRepository.headers,referer=page.url,data=mapOf("op" to "embed","file_code" to code,"auto" to "1","referer" to referer),timeout=8)
        }
        val origin=page.url.toHttpUrlOrNull()?.let{"${it.scheme}://${it.host}/"} ?: return emptyList()
        suspend fun add(raw:String){
            val u=page.url.toHttpUrlOrNull()?.resolve(raw) ?: return
            val type=if(u.encodedPath.endsWith(".m3u8",true))ExtractorLinkType.M3U8 else if(u.encodedPath.endsWith(".mp4",true))ExtractorLinkType.VIDEO else return
            result+=newExtractorLink("MySeed",source.name,u.toString(),type){this.referer=origin;headers=MySeedRepository.headers}
        }
        for(script in page.document.select("script:not([src])")){
            val raw=script.data()
            val unpacked=if(raw.contains("eval(function(p,a,c,k,e"))runCatching{JsUnpacker(raw).unpack()}.getOrNull() ?: raw else raw
            for(url in MySeedParser.media(unpacked,page.url))add(url)
            if(source.name.equals("Upzur",true)&&raw.contains(".reverse().join")){
                // Decode the player's literal reversed hex array, without executing JavaScript.
                val array=Regex("""var\s+\w+\s*=\s*\[([^]]+)\]""").find(raw)?.groupValues?.get(1).orEmpty()
                val decoded=Regex("""\\x([0-9a-fA-F]{2})""").findAll(array).map{it.groupValues[1].toInt(16).toChar()}.toList().reversed().joinToString("")
                for(url in MySeedParser.media(decoded,page.url))add(url)
            }
        }
        if(source.name.equals("Vidara",true)){
            val code=page.url.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty()
            val r=app.post(origin+"api/stream",referer=page.url,cookies=page.cookies,headers=MySeedRepository.headers,json=mapOf("filecode" to code,"device" to "android","codecs" to emptyList<String>()),timeout=8)
            if(r.code in 200..299){
                val n=mapper.readTree(r.text);n.path("streaming_url").asText().takeIf{it.startsWith("http")}?.let{add(it)}
                for(s in n.path("subtitles")){
                    val u=s.path("file_path").asText();val label=s.path("language").asText().ifBlank{s.path("label").asText()}
                    if(label.isNotBlank() && u.startsWith("http"))subtitles+=SubtitleFile(label,u)
                }
            }
        }
        if(result.isEmpty())try{loadExtractor(page.url,referer,{subtitles.add(it)},{result.add(it)})}
            catch(e:CancellationException){throw e}catch(_:Exception){}catch(_:LinkageError){}
        return result.distinctBy{it.url to it.headers}
    }
    suspend fun resolve(sources:List<SeedSource>,referer:String,subtitle:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean{
        if(sources.isEmpty())throw ErrorLoadingException("ماي سيد: لم تظهر سيرفرات في صفحة المشاهدة")
        val lock=Mutex();val browsers=Semaphore(2)
        val sent=mutableSetOf<String>();val sentSubs=mutableSetOf<String>()
        suspend fun emit(link:ExtractorLink) = lock.withLock {
            val key=listOf(link.name,link.url,link.referer,link.headers.toSortedMap().toString()).joinToString("\n")
            if(sent.add(key))callback(link)
        }
        SourceDispatcher.run(sources) { source ->
            val subs=mutableListOf<SubtitleFile>()
            val links=try{native(source,referer,subs)}catch(e:CancellationException){throw e}catch(_:Exception){emptyList()}catch(_:LinkageError){emptyList()}
            val valid=mutableListOf<ExtractorLink>()
            for(link in links)if(MySeedLinkValidator.validate(link)){valid+=link;emit(link)}
            if(valid.isEmpty()&&context!=null)browsers.withPermit {
                val request=MySeedBrowser(context).media(source.url)
                if(request!=null){
                    val link=newExtractorLink("MySeed",source.name,request.url,ExtractorLinkType.M3U8){
                        this.referer=request.headers.entries.firstOrNull{it.key.equals("referer",true)}?.value ?: source.url
                        headers=request.headers.filterKeys{!it.equals("referer",true)&&!it.equals("range",true)}
                    }
                    if(MySeedLinkValidator.validate(link)){valid+=link;emit(link)}
                }
            }
            if(valid.isNotEmpty())lock.withLock {subs.forEach{if(sentSubs.add(it.url))subtitle(it)}}
            // Publish the usable master first; quality enumeration never delays other servers.
            for(link in valid)if(link.type==ExtractorLinkType.M3U8){
                M3u8Helper.generateM3u8(link.name,link.url,link.referer,headers=link.headers).forEach{variant->
                    if(variant.url!=link.url&&MySeedLinkValidator.validate(variant))emit(variant)
                }
            }
        }
        if(sent.isEmpty())throw ErrorLoadingException("ماي سيد: لم يُتحقق من فيديو صالح من ${sources.size} سيرفر؛ قد يكون المصدر محجوبًا أو منتهيًا")
        return true
    }
}
