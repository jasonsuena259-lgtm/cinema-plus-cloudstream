package com.akwcam
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
class AkwCamResolver(private val context:Context?=null){
    private fun canonical(raw:String):String {
        val u=raw.toHttpUrlOrNull() ?: return raw
        return if(u.host.startsWith("mixdrop.") && u.encodedPath.startsWith("/f/"))u.newBuilder().encodedPath(u.encodedPath.replaceFirst("/f/","/e/")).build().toString() else raw
    }
    suspend fun expand(source:CamSource,referer:String):List<CamSource> {
        val host=source.url.toHttpUrlOrNull()?.host.orEmpty()
        if(host !in setOf("share4max.org","q7v3k8m2p1.top"))return listOf(source)
        val page=app.get(source.url,referer=referer,headers=AkwCamRepository.headers,timeout=8)
        if(page.code !in 200..299)return listOf(source)
        val state=page.document.selectFirst("script[data-page][type=application/json]")?.data() ?: return listOf(source)
        val mapper=jacksonObjectMapper();val initial=mapper.readTree(state)
        if(initial.path("component").asText()!="files/mirror/video")return listOf(source)
        val response=app.get(page.url,referer=referer,headers=AkwCamRepository.headers+mapOf("X-Inertia" to "true","X-Inertia-Partial-Component" to "files/mirror/video","X-Inertia-Partial-Data" to "streams","X-Inertia-Version" to initial.path("version").asText(),"X-Requested-With" to "XMLHttpRequest"),timeout=8)
        if(response.code !in 200..299)return listOf(source)
        val mirrors=AkwCamParser.mirrors(response.text,page.url)
        return mirrors.ifEmpty{listOf(source)}
    }
    suspend fun native(source:CamSource,referer:String,subtitles:MutableList<SubtitleFile>):List<ExtractorLink>{
        val result=mutableListOf<ExtractorLink>()
        val input=source.url.toHttpUrlOrNull() ?: return emptyList()
        if(input.encodedPath.endsWith(".mp4",true)||input.encodedPath.endsWith(".m3u8",true))return listOf(newExtractorLink("AkwCam",source.name,source.url,if(input.encodedPath.endsWith(".mp4",true))ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8){this.referer=referer;headers=AkwCamRepository.headers})
        var page=app.get(canonical(source.url),headers=AkwCamRepository.headers,referer=referer,timeout=8)
        if(page.code !in 200..299 || page.text.contains("cf-chl-") || page.text.contains("Sorry, you have been blocked"))return emptyList()
        val origin=page.url.toHttpUrlOrNull()?.let{"${it.scheme}://${it.host}/"} ?: return emptyList()
        suspend fun add(raw:String){
            val u=page.url.toHttpUrlOrNull()?.resolve(raw) ?: return
            val type=if(u.encodedPath.endsWith(".m3u8",true))ExtractorLinkType.M3U8 else if(u.encodedPath.endsWith(".mp4",true))ExtractorLinkType.VIDEO else return
            result+=newExtractorLink("AkwCam",source.name,u.toString(),type){this.referer=origin;headers=AkwCamRepository.headers;quality=getQualityFromName(source.name)}
        }
        for(track in page.document.select("track[src]")) {
            val label=track.attr("label").ifBlank{track.attr("srclang")}
            if(label.isNotBlank())subtitles+=SubtitleFile(label,track.absUrl("src"))
        }
        for(video in page.document.select("video[src],source[src]"))add(video.absUrl("src"))
        for(script in page.document.select("script:not([src])")){
            val raw=script.data()
            val unpacked=if(raw.contains("eval(function(p,a,c,k,e"))runCatching{JsUnpacker(raw).unpack()}.getOrNull() ?: raw else raw
            for(url in AkwCamParser.media(unpacked,page.url))add(url)
        }
        if(result.isEmpty() && input.host in setOf("vidaraa.cc","vidara.so")) {
            val code=page.url.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty()
            val response=app.post(origin+"api/stream",referer=page.url,cookies=page.cookies,headers=AkwCamRepository.headers,json=mapOf("filecode" to code,"device" to "android","codecs" to emptyList<String>()),timeout=8)
            if(response.code in 200..299){val node=jacksonObjectMapper().readTree(response.text);node.path("streaming_url").asText().takeIf{it.startsWith("http")}?.let{add(it)}}
        }
        if(result.isEmpty())try{loadExtractor(page.url,referer,{subtitles.add(it)},{result.add(it)})}
            catch(e:CancellationException){throw e}catch(_:Exception){}catch(_:LinkageError){}
        return result.distinctBy{it.url to it.headers}
    }
    suspend fun resolve(sources:List<CamSource>,referer:String,subtitle:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean{
        if(sources.isEmpty())throw ErrorLoadingException("أكوام: لم تظهر سيرفرات في صفحة المشاهدة")
        val lock=Mutex();val browsers=Semaphore(2)
        val sent=mutableSetOf<String>();val sentSubs=mutableSetOf<String>()
        suspend fun emit(link:ExtractorLink) = lock.withLock {
            val key=listOf(link.name,link.url,link.referer,link.headers.toSortedMap().toString()).joinToString("\n")
            if(sent.add(key))callback(link)
        }
        val expanded=coroutineScope{sources.map{source->async{try{expand(source,referer)}catch(e:CancellationException){throw e}catch(_:Exception){listOf(source)}}}.awaitAll().flatten()}
        SourceDispatcher.run(expanded.distinctBy{canonical(it.url)}, totalMillis=120000, sourceMillis=18000) { source ->
            val subs=mutableListOf<SubtitleFile>()
            val links=try{native(source,referer,subs)}catch(e:CancellationException){throw e}catch(_:Exception){emptyList()}catch(_:LinkageError){emptyList()}
            val valid=mutableListOf<ExtractorLink>()
            for(link in links)if(AkwCamLinkValidator.validate(link)){valid+=link;emit(link)}
            if(valid.isEmpty()&&context!=null)browsers.withPermit {
                val request=AkwCamBrowser(context).media(canonical(source.url))
                if(request!=null){
                    val link=newExtractorLink("AkwCam",source.name,request.url,if(request.url.substringBefore('?').endsWith(".mp4",true))ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8){
                        this.referer=request.headers.entries.firstOrNull{it.key.equals("referer",true)}?.value ?: source.url
                        headers=request.headers.filterKeys{!it.equals("referer",true)&&!it.equals("range",true)}
                    }
                    if(AkwCamLinkValidator.validate(link)){valid+=link;emit(link)}
                }
            }
            if(valid.isNotEmpty())lock.withLock {subs.forEach{if(sentSubs.add(it.url))subtitle(it)}}
            // Publish the usable master first; quality enumeration never delays other servers.
            for(link in valid)if(link.type==ExtractorLinkType.M3U8){
                M3u8Helper.generateM3u8(link.name,link.url,link.referer,headers=link.headers).forEach{variant->
                    if(variant.url!=link.url&&AkwCamLinkValidator.validate(variant))emit(variant)
                }
            }
        }
        if(sent.isEmpty())throw ErrorLoadingException("أكوام: لم يُتحقق من فيديو صالح من ${sources.size} سيرفر؛ قد يكون المصدر محجوبًا أو منتهيًا")
        return true
    }
}
