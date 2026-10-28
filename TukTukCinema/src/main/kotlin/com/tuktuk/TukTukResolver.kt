package com.tuktuk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import java.net.URI

class TukTukResolver {
    companion object { val headers = mapOf("user-agent" to "Mozilla/5.0") }
    private val mapper = jacksonObjectMapper()
    suspend fun resolve(data: String, subtitle: (SubtitleFile)->Unit, callback: (ExtractorLink)->Unit): Boolean {
        val pageUrl=TukTukParser.safeUrl(data) ?: return false
        if(URI(pageUrl).host!="tuktukcinemas.com") return false
        val response=app.get(pageUrl,headers=headers,timeout=15)
        if(response.code !in 200..299) throw ErrorLoadingException("توك توك: صفحة التشغيل أعادت HTTP ${response.code}")
        val sources=TukTukParser.sources(Jsoup.parse(response.text,response.url))
        val mirrors=mutableListOf<TukSource>()
        val errors=java.util.concurrent.ConcurrentLinkedQueue<String>()
        for(source in sources) {
            try {
                if(URI(source.url).host=="megatuktuk.store") {
                    val embed=app.get(source.url,headers=headers,referer=pageUrl,timeout=15)
                    if(embed.code !in 200..299) { errors.add("TukTuk Vip: HTTP ${embed.code}"); continue }
                    val script=Jsoup.parse(embed.text).selectFirst("script[data-page=app]")?.data() ?: continue
                    val page=mapper.readTree(script)
                    if(page.path("component").asText()!="files/mirror/video") continue
                    val streamResponse=app.get(source.url,referer=source.url,cookies=embed.cookies,headers=headers+mapOf(
                        "X-Inertia" to "true", "X-Inertia-Partial-Data" to "streams", "X-Inertia-Partial-Component" to "files/mirror/video",
                        "X-Inertia-Version" to page.path("version").asText(), "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "text/html, application/xhtml+xml"),timeout=15)
                    if(streamResponse.code in 200..299) mirrors+=TukTukParser.mirrors(streamResponse.text,source.url)
                    else errors.add("TukTuk Vip streams: HTTP ${streamResponse.code}")
                } else mirrors+=source
            } catch(e: CancellationException) { throw e } catch(e: Exception) { errors.add("${source.name}: ${e.javaClass.simpleName}") }
        }
        val limit=Semaphore(4)
        val results=coroutineScope { mirrors.distinctBy { it.url }.map { source -> async {
            limit.withPermit {
                withTimeoutOrNull(25000L) {
                    val found=mutableListOf<ExtractorLink>()
                    val subs=mutableListOf<SubtitleFile>()
                    try {
                        val hostPage=app.get(source.url,headers=headers,referer="https://megatuktuk.store/",timeout=12)
                        if(hostPage.code !in 200..299) { errors.add("${source.name}: HTTP ${hostPage.code}"); return@withTimeoutOrNull emptyList<ExtractorLink>() }
                        if(Regex("(?i)recaptcha|hcaptcha|cf-turnstile").containsMatchIn(hostPage.text)) { errors.add("${source.name}: يتطلب تحققًا"); return@withTimeoutOrNull emptyList<ExtractorLink>() }
                        val host=URI(hostPage.url).host
                        val origin="https://$host/"
                        val doc=Jsoup.parse(hostPage.text,hostPage.url)
                        suspend fun add(raw: String, sourceQuality: Int = source.quality) {
                            val url=TukTukParser.safeUrl(raw,hostPage.url) ?: return
                            found.add(newExtractorLink(name,source.name,url,if(url.contains(".m3u8") || url.contains("master.txt")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                                referer=origin; this.headers=TukTukResolver.headers; quality=if(sourceQuality>0) sourceQuality else Qualities.Unknown.value
                            })
                        }
                        if(source.name.equals("Firestream",true)) {
                            val blob=doc.selectFirst("#token-blob")?.data()?.trim().orEmpty()
                            if(blob.isNotBlank()) {
                                val slug=URI(hostPage.url).path.substringAfterLast('/')
                                val r=app.post(origin+"api/videos/$slug/resolve",referer=hostPage.url,cookies=hostPage.cookies,headers=headers,json=mapOf("blob" to blob),timeout=12)
                                if(r.code in 200..299) { val node=mapper.readTree(r.text); for(key in listOf("signedVideoUrl","signedVideoSdUrl")) node.path(key).asText().takeIf { it.startsWith("http") }?.let { add(it, if(key=="signedVideoSdUrl") 0 else source.quality) } }
                            }
                        } else if(source.name.equals("vidara",true)) {
                            val slug=URI(hostPage.url).path.substringAfterLast('/')
                            val r=app.post(origin+"api/stream",referer=hostPage.url,cookies=hostPage.cookies,headers=headers,json=mapOf("filecode" to slug,"device" to "android","codecs" to emptyList<String>()),timeout=12)
                            if(r.code in 200..299) { val node=mapper.readTree(r.text);node.path("streaming_url").asText().takeIf { it.startsWith("http") }?.let { add(it) } }
                        } else {
                            for(script in doc.select("script")) {
                                val raw=script.data()
                                val unpacked=if(raw.contains("eval(function(p,a,c,k,e")) JsUnpacker(raw).unpack() ?: continue else raw
                                TukTukParser.media(unpacked,hostPage.url).forEach { add(it) }
                            }
                            if(found.isEmpty()) loadExtractor(hostPage.url,"https://megatuktuk.store/",{subs.add(it)},{found.add(it)})
                        }
                        val valid=found.distinctBy { Triple(it.url,it.referer,it.headers) }.filter { validate(it) }
                        if(valid.isNotEmpty()) subs.distinctBy { it.url }.forEach(subtitle)
                        if(valid.isEmpty()) errors.add("${source.name}: لا يوجد ملف صالح")
                        valid
                    } catch(e: CancellationException) { throw e }
                    catch(e: LinkageError) { errors.add("${source.name}: مستخرج غير متوافق"); emptyList<ExtractorLink>() }
                    catch(e: Exception) { errors.add("${source.name}: ${e.javaClass.simpleName}"); emptyList<ExtractorLink>() }
                }.orEmpty()
            }
        } }.awaitAll().flatten() }
        val unique=results.distinctBy { Triple(it.url,it.referer,it.headers) }
        unique.forEach(callback)
        if(unique.isEmpty()) throw ErrorLoadingException("توك توك: لم ينجح أي رابط من ${mirrors.size} مصدر. "+errors.take(6).joinToString("؛ "))
        return true
    }
    private val name="TukTuk Cinema"
    private suspend fun validate(link: ExtractorLink): Boolean { return try {
        if(link.type==ExtractorLinkType.M3U8) {
            var url=link.url
            var res=app.get(url,referer=link.referer,headers=link.headers,timeout=10)
            var text=res.text
            if(res.code !in 200..299 || !text.trimStart().startsWith("#EXTM3U") || text.contains("METHOD=SAMPLE-AES")) false else {
                if(text.contains("#EXT-X-STREAM-INF:")) {
                    val lines=text.lines();val i=lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF:") }
                    val path=lines.drop(i+1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                    if(path==null) return false
                    url=URI(url).resolve(path.trim()).toString();res=app.get(url,referer=link.referer,headers=link.headers,timeout=10);text=res.text
                }
                if(res.code !in 200..299 || !text.startsWith("#EXTM3U") || !text.contains("#EXTINF:") || text.contains("METHOD=SAMPLE-AES")) false else {
                    val part=text.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                    part!=null && probe(URI(url).resolve(part.trim()).toString(),link,false)
                }
            }
        } else probe(link.url,link,true)
    } catch(e: CancellationException) { throw e } catch(e: Exception) { false }
    }
    private suspend fun probe(url: String,link: ExtractorLink,video: Boolean): Boolean {
        val res=app.get(url,referer=link.referer,headers=link.headers+mapOf("Range" to "bytes=0-1023"),timeout=10)
        return res.body.byteStream().use { input ->
            val buf=ByteArray(1024);val n=input.read(buf)
            res.code in 200..299 && n>0 && (if(video) res.headers["content-type"].orEmpty().contains("video",true) || (n>12 && String(buf,0,minOf(n,32)).contains("ftyp")) else res.headers["content-type"]?.contains("text/html",true)!=true)
        }
    }
}
