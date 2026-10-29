package com.dexter

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Pull real public video candidates only; never invent player URLs or CDN variants.
 * A host may expose several resolutions, but those are not separate servers.
 * No auth/CAPTCHA/DRM bypass, media segment download or insecure TLS overrides.
 */
class DexterResolver(private val base:String) {
    private fun allowed(raw:String):Boolean {
        val url=runCatching { URI(raw) }.getOrNull() ?:return false
        if(url.scheme!="https" || url.userInfo!=null || url.host.isNullOrBlank())return false
        val host=url.host.lowercase()
        if(host=="localhost"||host.endsWith(".local")||
           host.startsWith("127.")||host.startsWith("10.")||
           host.startsWith("192.168.")||host.startsWith("169.254."))
            return false
        return true
    }

    private suspend fun page(url:String,referer:String=base):String? {
        if(!allowed(url))return null
        try {
            val response=app.get(url,referer=referer,timeout=13)
            return response.takeIf { it.code in 200..299 }?.text
        } catch(e:CancellationException) { throw e }
        catch(_:Exception) { return null }
    }

    private suspend fun verifyHls(url:String,referer:String):Boolean =
        page(url,referer)?.let(DexterParser::isHls)==true

    /** Read at most 16 content bytes; never download an entire video. */
    private suspend fun verifyVideo(url:String,referer:String):Boolean =
        withContext(Dispatchers.IO) {
            if(!allowed(url))return@withContext false
            val client=app.baseClient.newBuilder()
                .callTimeout(10,TimeUnit.SECONDS).build()
            try {
                val req=Request.Builder().url(url)
                    .header("Referer",referer)
                    .header("Range","bytes=0-2047")
                    .build()
                client.newCall(req).execute().use { response ->
                    if(!response.isSuccessful)return@use false
                    val mime=response.header("Content-Type").orEmpty().lowercase()
                    if("text/html" in mime || "application/json" in mime ||
                        "text/plain" in mime)return@use false
                    val input=response.body?.byteStream()?:return@use false
                    val buffer=ByteArray(16)
                    var count=0
                    while(count<buffer.size) {
                        val n=input.read(buffer,count,buffer.size-count)
                        if(n<=0)break
                        count+=n
                    }
                    val path=URI(url).path.lowercase()
                    if(path.endsWith(".mkv")) {
                        count>=4 && buffer[0]==0x1a.toByte() &&
                            buffer[1]==0x45.toByte() &&
                            buffer[2]==0xdf.toByte() &&
                            buffer[3]==0xa3.toByte()
                    } else {
                        count>=12 && String(buffer,4,4,Charsets.US_ASCII) in
                            setOf("ftyp","styp","moof")
                    }
                }
            } catch(e:CancellationException) { throw e }
            catch(_:Exception) { false }
        }

    suspend fun resolve(
        data:String,
        subtitleCallback:(SubtitleFile)->Unit,
        callback:(ExtractorLink)->Unit
    ):Boolean {
        val detail=DexterParser.site(data)?:return false
        if(!DexterParser.isMovie(detail) && !DexterParser.isEpisode(detail))
            return false
        val html=page(detail) ?:throw ErrorLoadingException(
            "Dexter: صفحة المشاهدة غير متاحة أو محجوبة؛ لا يمكن إثبات السيرفرات")
        val candidates=DexterParser.sources(html,detail)
        val emitted=mutableSetOf<String>()
        val subtitles=mutableSetOf<String>()
        var works=0

        fun publishSubtitles(text:String,sourceUrl:String) {
            DexterParser.subtitles(text,sourceUrl).forEach { (language,url) ->
                if(allowed(url) && subtitles.add(url))subtitleCallback(SubtitleFile(language,url))
            }
        }

        suspend fun publishMedia(url:String,referer:String) {
            if(!allowed(url) || url in emitted)return
            val path=runCatching { URI(url).path.lowercase() }.getOrNull()?:return
            val type=when {
                path.endsWith(".m3u8") && verifyHls(url,referer) ->
                    ExtractorLinkType.M3U8
                (path.endsWith(".mp4") || path.endsWith(".mkv")) &&
                    verifyVideo(url,referer) -> ExtractorLinkType.VIDEO
                else -> null
            } ?:return
            if(!emitted.add(url))return
            val quality=DexterParser.quality(url)
            val host=URI(url).host.removePrefix("www.")
            val caption=host + (if(quality>0)" • "+quality+"p" else " • Auto")
            callback(newExtractorLink("Dexter",caption,url,type) {
                this.referer=referer
                this.quality=quality
            })
            works++
        }

        publishSubtitles(html,detail)
        for(candidate in candidates.take(30)) {
            val url=candidate.url
            if(!allowed(url))continue
            val path=URI(url).path.lowercase()
            if(path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".mkv")) {
                publishMedia(url,detail)
                continue
            }

            // Attempt to read one transparent HTML player level, without
            // executing obfuscated JS or bypassing any access challenge.
            val player=withTimeoutOrNull(12000L) { page(url,detail) }
            if(player!=null) {
                publishSubtitles(player,url)
                DexterParser.sources(player,url).take(15).forEach { source ->
                    val innerPath=URI(source.url).path.lowercase()
                    if(innerPath.endsWith(".m3u8") || innerPath.endsWith(".mp4") ||
                        innerPath.endsWith(".mkv"))publishMedia(source.url,url)
                }
            }
            // Fall back to existing CloudStream host extractors.
            withTimeoutOrNull(10000L) {
                try {
                    loadExtractor(url,detail,{ sub ->
                        if(allowed(sub.url) && subtitles.add(sub.url))
                            subtitleCallback(sub)
                    },{ link ->
                        if(allowed(link.url) && emitted.add(link.url)) {
                            callback(link)
                            works++
                        }
                    })
                } catch(e:CancellationException) { throw e }
                catch(_:Exception) {}
            }
        }
        if(works==0)throw ErrorLoadingException(
            "Dexter: لم أتحقق من فيديو قابل للتشغيل. ظهور المشغّل أو 21 رابطاً لا يثبت وجود 21 سيرفراً")
        return true
    }
}
