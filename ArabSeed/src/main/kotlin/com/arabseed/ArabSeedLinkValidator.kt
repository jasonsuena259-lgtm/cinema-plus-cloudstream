package com.arabseed

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Network probe checks that a returned link is media without downloading full
 * segments, keys or the entire movie. We intentionally do not mark a source as
 * 1080p unless the extractor or HLS master advertises it.
 */
internal object ArabSeedLinkValidator {
    internal fun plausibleMedia(type:ExtractorLinkType,status:Int,mime:String,bytes:ByteArray):Boolean {
        if(status !in 200..299)return false
        val content=bytes.toString(Charsets.UTF_8).trimStart('\uFEFF',' ','\r','\n','\t')
        if(type==ExtractorLinkType.M3U8) {
            return content.startsWith("#EXTM3U") &&
                (content.contains("#EXTINF") || content.contains("#EXT-X-STREAM-INF") ||
                 content.contains("#EXT-X-TARGETDURATION"))
        }
        if(type != ExtractorLinkType.VIDEO)return false
        if(bytes.size>=8 && bytes.copyOfRange(4,8).toString(Charsets.US_ASCII) in
            listOf("ftyp","styp","moof","sidx"))return true
        if(bytes.size>=188*3 && bytes[0]==0x47.toByte() && bytes[188]==0x47.toByte()
            && bytes[376]==0x47.toByte()) return true
        // Some MP4 CDNs return bytes from a nonzero range or a moov fragment.
        // A successful response explicitly identified as video is sufficient.
        return mime.lowercase().substringBefore(';').trim() in
            setOf("video/mp4","video/webm","video/x-matroska","video/mp2t","video/quicktime")
    }

    suspend fun validate(link:ExtractorLink):Boolean=withContext(Dispatchers.IO) {
        if(link.type !in setOf(ExtractorLinkType.M3U8,ExtractorLinkType.VIDEO))return@withContext false
        try {
            val builder=Request.Builder().url(link.url).get()
            ArabSeedRepository.headers.forEach{(k,v)->builder.header(k,v)}
            link.headers.forEach{(k,v)->if(!k.equals("host",true))builder.header(k,v)}
            if(link.referer.isNotBlank())builder.header("Referer",link.referer)
            if(link.type==ExtractorLinkType.VIDEO)builder.header("Range","bytes=0-2047")
            val client=app.baseClient.newBuilder()
                .followRedirects(true)
                .callTimeout(7,TimeUnit.SECONDS).build()
            val result=suspendCancellableCoroutine<Triple<Int,String,ByteArray>?> { continuation ->
                val call=client.newCall(builder.build())
                continuation.invokeOnCancellation{call.cancel()}
                call.enqueue(object:Callback {
                    override fun onFailure(call:Call,e:IOException) {
                        if(continuation.isActive)continuation.resume(null)
                    }
                    override fun onResponse(call:Call,response:Response) {
                        val sample=try { response.use{r->
                            val mime=r.header("Content-Type").orEmpty()
                            val limit=if(link.type==ExtractorLinkType.M3U8)131072 else 2048
                            val content=r.body?.byteStream()?.let{input->
                                val bytes=ByteArray(limit)
                                val out=java.io.ByteArrayOutputStream()
                                while(out.size()<limit) {
                                    val n=input.read(bytes,0,minOf(bytes.size,limit-out.size()))
                                    if(n<0)break
                                    if(n==0)break
                                    out.write(bytes,0,n)
                                }
                                out.toByteArray()
                            } ?: ByteArray(0)
                            Triple(r.code,mime,content)
                        }}catch(_:Exception){null}
                        if(continuation.isActive)continuation.resume(sample)
                    }
                })
            }
            result?.let{plausibleMedia(link.type,it.first,it.second,it.third)}==true
        } catch(e:CancellationException){throw e}
          catch(_:Exception){false}
    }
}
