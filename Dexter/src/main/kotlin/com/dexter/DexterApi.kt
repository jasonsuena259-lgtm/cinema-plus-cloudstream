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
        fun collect(container:JsonNode) {
            for(key in listOf("subtitles","tracks","captions")) {
                val rows=container.path(key)
                if(!rows.isArray)continue
                for(item in rows) {
                    val raw=if(item.isTextual)item.asText() else
                        item.field("url").ifBlank { item.field("file") }
                            .ifBlank { item.field("src") }
                    val url=DexterParser.externalUrl(raw,DexterParser.BASE)?:continue
                    val path=runCatching { URI(url).path.lowercase() }.getOrNull()
                        ?:continue
                    if(listOf("thumbs.vtt","thumbnails.vtt","storyboard","sprite")
                            .any(path::contains))continue
                    if(!listOf(".srt",".vtt",".ass",".ssa").any(path::endsWith))
                        continue
                    val language=item.field("lang").ifBlank { item.field("language") }
                        .ifBlank { item.field("srclang") }
                        .ifBlank { item.field("label") }
                        .ifBlank { "Unknown" }
                    subs.putIfAbsent(url,DexterApiSubtitle(url,language))
                }
            }
        }
        collect(root)
        for(source in items.takeIf { it.isArray } ?: emptyList<JsonNode>())
            collect(source)
        collect(root.path("episode"))
        return DexterApiResult(candidates.values.toList(),subs.values.toList(),playable)
    }

    /** External WebVTT/SRT captions explicitly listed in an HLS master. */
    fun playlistSubtitles(master:String,source:String):List<DexterApiSubtitle> {
        if(!DexterParser.isHls(master))return emptyList()
        val found=linkedMapOf<String,DexterApiSubtitle>()
        for(line in master.lines()) {
            if(!line.startsWith("#EXT-X-MEDIA:",ignoreCase=true))continue
            val values=mutableMapOf<String,String>()
            // HLS attributes can be quoted and contain commas in quoted names.
            val pattern=Regex("""([A-Z-]+)=(?:"([^"]*)"|([^,]*))(?:,|$)""")
            for(match in pattern.findAll(line.substringAfter(':'))) {
                values[match.groupValues[1]]=match.groupValues[2]
                    .ifBlank { match.groupValues[3] }.trim()
            }
            if(values["TYPE"]?.uppercase()!="SUBTITLES")continue
            val url=DexterParser.externalUrl(values["URI"].orEmpty(),source)?:continue
            val path=runCatching { URI(url).path.lowercase() }.getOrNull()?:continue
            if(listOf("thumbs.vtt","thumbnails.vtt","storyboard")
                    .any(path::contains))continue
            // A .m3u8 subtitle media playlist is handled as an embedded
            // timed-text track by the HLS player, not as an external VTT.
            if(!listOf(".srt",".vtt",".ass",".ssa").any(path::endsWith))
                continue
            val language=values["LANGUAGE"].orEmpty().ifBlank {
                values["NAME"].orEmpty().ifBlank { "Unknown" }
            }
            found.putIfAbsent(url,DexterApiSubtitle(url,language))
        }
        return found.values.toList()
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
