package com.arabseed

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Optional independent host extractors for Android forks missing the bundled
 * MixDrop / DoodStream handlers. Uses only public HTML/player endpoints.
 * The returned links still go through the same media health checks.
 */
internal object ArabSeedHostPlayer {
    private val alphabet="abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val md5Path=Regex("""/pass_md5/[a-zA-Z0-9_/?=&%-]+""")
    private val mixUrl=Regex("""(?i)(?:MDCore\.)?wurl\s*=\s*["']([^"']+)["']""")

    fun doodPath(html:String):String?=md5Path.find(html)?.value
    fun mixdropUrl(html:String):String?=mixUrl.find(html)?.groupValues?.getOrNull(1)

    suspend fun extract(source:ArabSeedSource,referer:String):List<ExtractorLink> {
        val parsed=source.url.toHttpUrlOrNull() ?: return emptyList()
        val host=parsed.host.lowercase()
        val isDood=host.contains("dood") || host.contains("dsvplay") || host.contains("ds2play")
        val isMix=host.contains("mixdrop") || host.contains("mxdrop")
        if(!isDood&&!isMix)return emptyList()
        val embed=ArabSeedHostCandidates.variants(source.url).last()
        try {
            val page=app.get(embed,headers=ArabSeedRepository.headers,referer=referer,timeout=6)
            if(page.code !in 200..299)return emptyList()
            if(isDood){
                val path=doodPath(page.text) ?: return emptyList()
                val hostRoot=page.url.toHttpUrlOrNull() ?: return emptyList()
                val url=hostRoot.resolve(path) ?: return emptyList()
                if(url.host!=hostRoot.host)return emptyList()
                val response=app.get(url.toString(),headers=ArabSeedRepository.headers,referer=page.url,timeout=6)
                val prefix=response.text.trim()
                if(response.code !in 200..299 || !prefix.startsWith("https://"))return emptyList()
                val token=path.substringBefore('?').substringAfterLast('/')
                if(token.isBlank())return emptyList()
                val random=buildString { repeat(10){append(alphabet.random())} }
                val sep=if(prefix.contains("?"))"&" else "?"
                val video=prefix+random+sep+"token="+token
                val title=page.document.title()
                val resolution=Regex("""(?:360|480|720|1080|1440|2160)[pP]""").find(title)?.value
                return listOf(newExtractorLink("ArabSeed","DoodStream",video,ExtractorLinkType.VIDEO) {
                    this.referer=hostRoot.toString().substringBefore(hostRoot.encodedPath)+"/"
                    this.quality=getQualityFromName(resolution)
                    this.headers=ArabSeedRepository.headers
                })
            }
            val unpacked=if(page.text.contains("eval(function(p,a,c,k,e"))
                runCatching{JsUnpacker(page.text).unpack()}.getOrNull().orEmpty()
                else ""
            val raw=mixdropUrl(unpacked) ?: mixdropUrl(page.text) ?: return emptyList()
            val target=page.url.toHttpUrlOrNull()?.resolve(raw) ?: return emptyList()
            if(target.scheme!="https" && target.scheme!="http")return emptyList()
            val type=if(target.encodedPath.endsWith(".m3u8",true))ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            return listOf(newExtractorLink("ArabSeed","Mixdrop",target.toString(),type){
                this.referer=page.url
                this.headers=ArabSeedRepository.headers
            })
        }catch(e:CancellationException){throw e}catch(_:Exception){return emptyList()}
    }
}
