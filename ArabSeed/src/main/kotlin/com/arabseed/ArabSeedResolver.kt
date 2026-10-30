package com.arabseed
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
class ArabSeedResolver(private val context:Context?=null){
    private val mapper=jacksonObjectMapper()
    suspend fun native(source:ArabSeedSource,referer:String,subtitles:MutableList<SubtitleFile>):List<ExtractorLink>{
        val result=mutableListOf<ExtractorLink>()
        // Let CloudStream's host-specific extractors run BEFORE the generic HTTP
        // probe. Previously a 403, anti-bot page, or Dood /d/ landing page
        // aborted this source without ever trying a compatible extractor.
        val candidates=ArabSeedHostCandidates.variants(source.url)
        val dood=source.url.contains("dood",true) || source.url.contains("dsvplay",true)
            || source.url.contains("ds2play",true)
        // Dood's signed public media links expire rapidly. The independent
        // handler must go FIRST; otherwise two timed-out CloudStream handlers
        // can consume the source deadline before /pass_md5 finishes.
        if(dood)withTimeoutOrNull(13500) {
            result.addAll(ArabSeedHostPlayer.extract(source,referer))
        }
        if(result.isNotEmpty())return result.distinctBy{it.url to it.headers}
        for(candidate in candidates) {
            try {
                withTimeoutOrNull(5500) {
                    loadExtractor(candidate,referer,{subtitles.add(it)},{result.add(it)})
                }
            } catch(e:CancellationException){throw e}
              catch(_:Exception){}
              catch(_:LinkageError){}
        }
        // Keep the v3 Mixdrop route, which was confirmed playable on Android.
        if(!dood)withTimeoutOrNull(9500) {
            result.addAll(ArabSeedHostPlayer.extract(source,referer))
        }
        if(result.isNotEmpty())return result.distinctBy{it.url to it.headers}
        var page=try {
            app.get(candidates.last(),headers=ArabSeedRepository.headers,referer=referer,timeout=8)
        } catch(e:CancellationException){throw e}
          catch(_:Exception){return emptyList()}
        if(page.code !in 200..299 || page.text.contains("cf-chl-") || page.text.contains("Sorry, you have been blocked"))return emptyList()
        // Follow only the public same-video redirect explicitly supplied by VOE's page.
        if(source.name.equals("Voe",true)){
            val redirect=Regex("""window\.location\.href\s*=\s*['"](https://[^'"]+)['"]""").find(page.text)?.groupValues?.get(1)
            if(redirect!=null)page=app.get(redirect,headers=ArabSeedRepository.headers,referer=page.url,timeout=8)
        }
        if(source.name.equals("Streamhls",true)&&page.document.selectFirst("form#F1[action=/dl]")!=null){
            val code=page.url.substringAfterLast('/');val target=page.url.toHttpUrlOrNull()?.resolve("/dl") ?: return emptyList()
            page=app.post(target.toString(),headers=ArabSeedRepository.headers,referer=page.url,data=mapOf("op" to "embed","file_code" to code,"auto" to "1","referer" to referer),timeout=8)
        }
        val origin=page.url.toHttpUrlOrNull()?.let{"${it.scheme}://${it.host}/"} ?: return emptyList()
        suspend fun add(raw:String){
            val u=page.url.toHttpUrlOrNull()?.resolve(raw) ?: return
            val type=if(u.encodedPath.endsWith(".m3u8",true))ExtractorLinkType.M3U8 else if(u.encodedPath.endsWith(".mp4",true))ExtractorLinkType.VIDEO else return
            result+=newExtractorLink("ArabSeed",source.name,u.toString(),type){this.referer=origin;headers=ArabSeedRepository.headers}
        }
        for(script in page.document.select("script:not([src])")){
            val raw=script.data()
            val unpacked=if(raw.contains("eval(function(p,a,c,k,e"))runCatching{JsUnpacker(raw).unpack()}.getOrNull() ?: raw else raw
            for(url in ArabSeedParser.media(unpacked,page.url))add(url)
            if(source.name.equals("Upzur",true)&&raw.contains(".reverse().join")){
                // Decode the player's literal reversed hex array, without executing JavaScript.
                val array=Regex("""var\s+\w+\s*=\s*\[([^]]+)\]""").find(raw)?.groupValues?.get(1).orEmpty()
                val decoded=Regex("""\\x([0-9a-fA-F]{2})""").findAll(array).map{it.groupValues[1].toInt(16).toChar()}.toList().reversed().joinToString("")
                for(url in ArabSeedParser.media(decoded,page.url))add(url)
            }
        }
        if(source.name.equals("Vidara",true)){
            val code=page.url.toHttpUrlOrNull()?.pathSegments?.lastOrNull().orEmpty()
            val r=app.post(origin+"api/stream",referer=page.url,cookies=page.cookies,headers=ArabSeedRepository.headers,json=mapOf("filecode" to code,"device" to "android","codecs" to emptyList<String>()),timeout=8)
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
    suspend fun resolve(sources:List<ArabSeedSource>,referer:String,subtitle:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean{
        if(sources.isEmpty())throw ErrorLoadingException("عرب سيد: لم تظهر سيرفرات في صفحة المشاهدة")
        val lock=Mutex();val browsers=Semaphore(3)
        val sent=mutableSetOf<String>();val sentSubs=mutableSetOf<String>()
        suspend fun emit(link:ExtractorLink) = lock.withLock {
            val key=listOf(link.name,link.url,link.referer,link.headers.toSortedMap().toString()).joinToString("\n")
            if(sent.add(key))callback(link)
        }
        SourceDispatcher.run(sources, totalMillis = 85000, sourceMillis = 40000, concurrency = 10) { source ->
            val subs=mutableListOf<SubtitleFile>()
            val links=try{native(source,referer,subs)}catch(e:CancellationException){throw e}catch(_:Exception){emptyList()}catch(_:LinkageError){emptyList()}
            val valid=mutableListOf<ExtractorLink>()
            // Keep extractors separate from HTTP health. The strict v1/v2
            // segment test dropped legitimate signed HLS and 1080 MP4 links.
            for(link in links)if(ArabSeedLinkValidator.validate(link)){valid+=link;emit(link)}
            val browserLinks=mutableListOf<ExtractorLink>()
            if(valid.isEmpty()&&context!=null)withTimeoutOrNull(17000) {
                browsers.withPermit {
                    val embed=ArabSeedHostCandidates.variants(source.url).last()
                    val request=ArabSeedBrowser(context).media(embed,referer)
                    if(request!=null){
                        val link=newExtractorLink("ArabSeed",source.name,request.url,
                            if(request.isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO){
                            this.referer=request.headers.entries.firstOrNull{it.key.equals("referer",true)}?.value ?: embed
                            headers=request.headers.filterKeys{!it.equals("referer",true)&&!it.equals("range",true)}
                        }
                        browserLinks+=link
                        if(ArabSeedLinkValidator.validate(link)){valid+=link;emit(link)}
                    }
                }
            }
            // A recognized extractor may return a playable stream even when
            // its CDN rejects range probes. Surface ONLY an already extracted
            // media URL, explicitly marked as unverified; never publish embeds
            // or fabricate a "1080p" label from the site's download page.
            if(valid.isEmpty()){
                (links+browserLinks).filter{ArabSeedMediaCandidate.canTry(it.url,source.url,it.type)}
                    .distinctBy{it.url}.take(2).forEach{link->
                        val fallback=newExtractorLink("ArabSeed",source.name+" (تجريبي)",link.url,link.type){
                            this.referer=link.referer
                            this.headers=link.headers
                            this.quality=link.quality
                        }
                        emit(fallback)
                    }
            }
            if(valid.isNotEmpty())lock.withLock {subs.forEach{if(sentSubs.add(it.url))subtitle(it)}}
            // 1080p appears only if the HLS master actually advertises a 1080
            // rendition; do not downgrade or relabel any host as 1080.
            for(link in valid)if(link.type==ExtractorLinkType.M3U8){
                try {
                    M3u8Helper.generateM3u8(link.name,link.url,link.referer,headers=link.headers).forEach{variant->
                        if(variant.url!=link.url&&ArabSeedLinkValidator.validate(variant))emit(variant)
                    }
                }catch(e:CancellationException){throw e}catch(_:Exception){}
            }
        }
        if(sent.isEmpty())throw ErrorLoadingException("عرب سيد: لم يُتحقق من فيديو صالح من ${sources.size} سيرفر؛ قد يكون المصدر محجوبًا أو منتهيًا")
        return true
    }
}
