package com.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit
import org.jsoup.Jsoup

/**
 * Akwam.ss exposes one watch + download route per real quality identifier:
 * /watch/STREAM_ID/TITLE_ID/... and /download/STREAM_ID/TITLE_ID/...
 * Never extrapolate a quality, server name or file URL from an ID.
 */
class AkwamResolver(private val base:String){
    private suspend fun page(url:String,referer:String=base):String?=
        try { app.get(url,referer=referer,timeout=15)
            .takeIf{it.code in 200..299}?.text
        }catch(e:CancellationException){throw e}
        catch(_:Exception){null}

    private fun allowedMedia(url:String):Boolean {
        val parsed=runCatching{URI(url)}.getOrNull()?:return false
        if(parsed.scheme!="https"||parsed.userInfo!=null||parsed.host.isNullOrBlank())return false
        val host=parsed.host.lowercase()
        if(host=="localhost"||host.endsWith(".local")||host.startsWith("127.")||
           host.startsWith("10.")||host.startsWith("192.168.")||host.startsWith("169.254."))
           return false
        return true
    }
    /**
     * Download pages expose direct MP4 files. Validate a short byte range
     * before treating them as playable sources. Do not fetch whole movies.
     */
    private suspend fun verifiedMp4(url:String,referer:String):Boolean=withContext(Dispatchers.IO){
        if(!allowedMedia(url))return@withContext false
        val client=app.baseClient.newBuilder().callTimeout(10,TimeUnit.SECONDS).build()
        try {
            val req=Request.Builder().url(url).header("Range","bytes=0-2047")
                .header("Referer",referer).build()
            client.newCall(req).execute().use{response->
                if(!response.isSuccessful)return@use false
                val mime=response.header("Content-Type").orEmpty().lowercase()
                if("text/html" in mime || "text/plain" in mime || "application/json" in mime)
                    return@use false
                val stream=response.body?.byteStream()?:return@use false
                val first=ByteArray(16);var read=0
                while(read<16){
                    val n=stream.read(first,read,16-read)
                    if(n<=0)break
                    read+=n
                }
                if(read<12)return@use false
                val atom=String(first,4,4,Charsets.US_ASCII)
                atom in setOf("ftyp","styp","moof")
            }
        }catch(e:CancellationException){throw e}
        catch(_:Exception){false}
    }
    private fun subtitleTracks(html:String,url:String):List<SubtitleFile> {
        val doc=Jsoup.parse(html,url)
        return doc.select("track[src],a[href$=.srt],a[href$=.vtt],a[href$=.ass]")
            .mapNotNull{t->
                val raw=t.attr("src").ifBlank{t.attr("href")}
                val link=runCatching{URI(url).resolve(raw).toString()}.getOrNull()
                    ?:return@mapNotNull null
                if(!allowedMedia(link))return@mapNotNull null
                SubtitleFile(t.attr("srclang").ifBlank{t.attr("label").ifBlank{"Arabic"}},link)
            }.distinctBy{it.url}
    }

    suspend fun resolve(
        data:String,
        subtitleCallback:(SubtitleFile)->Unit,
        callback:(ExtractorLink)->Unit
    ):Boolean {
        val url=AkwamParser.site(data) ?:return false
        val path=URI(url).path
        if(!Regex("""^/(movie|episode)/\d+(?:/|$)""").containsMatchIn(path))
            return false
        val detailHtml=page(url)?:throw ErrorLoadingException("أكوام: لم تُفتح صفحة المحتوى")
        val rows=AkwamParser.qualities(detailHtml,url)
        if(rows.isEmpty())throw ErrorLoadingException(
            "أكوام: الصفحة لا تحتوي روابط مشاهدة أو تحميل حقيقية")
        val emitted=mutableSetOf<String>()
        val subSet=mutableSetOf<String>()
        var works=0
        suspend fun publishFile(link:String,name:String,quality:Int,referer:String){
            if(!verifiedMp4(link,referer)||!emitted.add(link))return
            callback(newExtractorLink("Akwam",name,link,ExtractorLinkType.VIDEO){
                this.referer=referer
                this.quality=quality
            })
            works++
        }
        for(row in rows.take(12)){
            val q=row.quality.takeIf{it>0} ?: 0
            // Download routes are a confirmed source of MP4 links, even when
            // a JS-only streaming iframe does not expose its video in HTML.
            row.download?.let{download->
                val raw=page(download,url)
                if(raw!=null){
                    subtitleTracks(raw,download).forEach{
                        if(subSet.add(it.url))subtitleCallback(it)
                    }
                    AkwamParser.downloads(raw,download).take(8).forEach{file->
                        publishFile(file,"Akwam Download • "+(if(q>0)q.toString()+"p" else "MP4"),
                            q,download)
                    }
                }
            }
            row.watch?.let{watchUrl->
                val watchHtml=page(watchUrl,url) ?:return@let
                subtitleTracks(watchHtml,watchUrl).forEach{
                    if(subSet.add(it.url))subtitleCallback(it)
                }
                val embedded=AkwamParser.embeds(watchHtml,watchUrl)
                for(source in embedded.take(12)){
                    if(!allowedMedia(source))continue
                    val p=URI(source).path.lowercase()
                    if(p.endsWith(".mp4")){
                        publishFile(source,"Akwam Watch • "+(if(q>0)q.toString()+"p" else "MP4"),
                            q,watchUrl)
                    } else {
                        // Only registered CloudStream extractors can supply real
                        // links from a player host. Do not invent server URLs.
                        withTimeoutOrNull(14000L) {
                            try {
                                loadExtractor(source,watchUrl,{sub->
                                    if(subSet.add(sub.url))subtitleCallback(sub)
                                },{link->
                                    if(allowedMedia(link.url)&&emitted.add(link.url)){
                                        callback(link);works++
                                    }
                                })
                            }catch(e:CancellationException){throw e}
                            catch(_:Exception){}
                        }
                    }
                }
            }
        }
        if(works==0)throw ErrorLoadingException(
            "أكوام: لم يتم التحقق من ملف فيديو صالح من روابط هذا العنوان. " +
            "ظهور زر المشاهدة أو التحميل لا يثبت عمل السيرفر.")
        return true
    }
}
