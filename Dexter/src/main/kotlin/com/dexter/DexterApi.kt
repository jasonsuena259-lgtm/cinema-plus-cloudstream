package com.dexter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI

/** Public Dexter API mapping, independently implemented from observed URL contracts. */
data class DexterApiSource(
    val url:String,
    val label:String,
    val quality:Int,
    val format:String
)
data class DexterApiSubtitle(val url:String,val language:String)
data class DexterApiResult(
    val sources:List<DexterApiSource>,
    val subtitles:List<DexterApiSubtitle>,
    val playable:Boolean
)
data class DexterHlsVariant(val url:String,val quality:Int)

object DexterApi {
    private val mapper=ObjectMapper()
    private val movie=Regex("""^/watch/(\d+)-[^/]+/?$""")
    private val episode=Regex("""^/watch/show/(\d+)-[^/]+/(\d+)/(\d+)(?:/|$)""")

    /**
     * Only derive API addresses from a parsed TMDB identifier in a genuine
     * Dexter movie/episode URL. Never use guesswork or the display title.
     */
    fun endpoint(page:String):String? {
        val safe=DexterParser.site(page)?:return null
        val path=runCatching{URI(safe).path}.getOrNull()?:return null
        movie.matchEntire(path)?.let{m->
            return DexterParser.BASE+"/api/movie/"+m.groupValues[1]+"?site=dexter"
        }
        episode.find(path)?.let{m->
            return DexterParser.BASE+"/api/tv/"+m.groupValues[1]+
                "/episode/"+m.groupValues[2]+"/"+m.groupValues[3]+"?site=dexter"
        }
        return null
    }

    private fun JsonNode.field(name:String):String =
        path(name).takeIf { it.isTextual || it.isNumber }?.asText().orEmpty().trim()

    fun parse(body:String):DexterApiResult {
        val root=runCatching { mapper.readTree(body) }.getOrNull()
            ?:return DexterApiResult(emptyList(),emptyList(),false)
        if(!root.isObject)return DexterApiResult(emptyList(),emptyList(),false)
        val playable=!root.path("playable").isBoolean || root.path("playable").asBoolean()
        val candidates=linkedMapOf<String,DexterApiSource>()
        val items=root.path("sources")
        if(items.isArray && playable)for(item in items) {
            val raw=item.field("url")
            val url=DexterParser.externalUrl(raw,DexterParser.BASE)?:continue
            val kind=item.field("kind").lowercase()
            val path=runCatching { URI(url).path.lowercase() }.getOrNull()?:continue
            val format=when {
                kind in setOf("hls","m3u8") || path.endsWith(".m3u8")->"hls"
                kind=="dash" || path.endsWith(".mpd")->"dash"
                kind in setOf("mp4","direct","video") ||
                    listOf(".mp4",".mkv",".webm",".mov").any(path::endsWith)->"direct"
                kind in setOf("embed","player","html")->"embed"
                else->"unknown"
            }
            if(format=="unknown")continue
            val declared=item.field("quality")
            val q=Regex("""(?i)(2160|1600|1440|1080|720|480|360|240)""")
                .find(declared)?.groupValues?.get(1)?.toIntOrNull()
                ?:if(declared.equals("4K",true))2160
                else DexterParser.quality(url)
            val label=item.field("label").ifBlank { "Dexter" }.take(75)
            candidates.putIfAbsent(url,DexterApiSource(url,label,q,format))
        }
        val subs=linkedMapOf<String,DexterApiSubtitle>()
        val subtitles=root.path("subtitles").takeIf { it.isArray }
            ?:root.path("tracks")
        if(subtitles.isArray)for(item in subtitles) {
            val url=DexterParser.externalUrl(item.field("url"),DexterParser.BASE)?:continue
            val path=runCatching { URI(url).path.lowercase() }.getOrNull()?:continue
            if(listOf("thumbs.vtt","thumbnails.vtt","storyboard").any(path::contains))
                continue
            if(!listOf(".srt",".vtt",".ass",".ssa").any(path::endsWith))continue
            val language=item.field("lang").ifBlank { item.field("language") }
                .ifBlank { item.field("label") }.ifBlank { "Unknown" }
            subs.putIfAbsent(url,DexterApiSubtitle(url,language))
        }
        return DexterApiResult(candidates.values.toList(),subs.values.toList(),playable)
    }

    /**
     * Parse only the actual rendition URLs declared in an HLS master playlist.
     * A master and four qualities are still streams from one origin/host.
     */
    fun variants(master:String,source:String):List<DexterHlsVariant> {
        if(!DexterParser.isHls(master))return emptyList()
        val lines=master.lines().map(String::trim)
        val out=linkedMapOf<String,DexterHlsVariant>()
        for(i in lines.indices) {
            val header=lines[i]
            if(!header.startsWith("#EXT-X-STREAM-INF",true))continue
            val height=Regex("""RESOLUTION=\d+x(\d+)""",RegexOption.IGNORE_CASE)
                .find(header)?.groupValues?.get(1)?.toIntOrNull()
                ?:continue
            var j=i+1
            while(j<lines.size && (lines[j].isBlank() || lines[j].startsWith("#")))
                j++
            if(j>=lines.size)continue
            val uri=DexterParser.externalUrl(lines[j],source)?:continue
            if(!runCatching{URI(uri).path.lowercase().endsWith(".m3u8")}
                    .getOrDefault(false))continue
            out.putIfAbsent(uri,DexterHlsVariant(uri,height))
        }
        return out.values.take(12)
    }
}
