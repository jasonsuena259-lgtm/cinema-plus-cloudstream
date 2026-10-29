package com.dexter

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI

data class DexterSubtitleTrack(val language:String,val url:String)

/**
 * Independent subtitle lookup keyed to the actual TMDB ID on a Dexter URL.
 * Only return real file URLs from a successful JSON response; no fake Arabic.
 */
object DexterSubtitles {
    private val mapper=ObjectMapper()
    private val movie=Regex("""^/watch/(\d+)-[^/]+/?$""")
    private val episode=Regex("""^/watch/show/(\d+)-[^/]+/(\d+)/(\d+)(?:/|$)""")
    const val BASE="https://sub.vdrk.site"
    fun endpoint(page:String):String? {
        val safe=DexterParser.site(page)?:return null
        val path=runCatching { URI(safe).path }.getOrNull() ?:return null
        movie.matchEntire(path)?.let { m ->
            return BASE+"/v1/movie/"+m.groupValues[1]
        }
        episode.matchEntire(path)?.let { m ->
            return BASE+"/v1/tv/"+m.groupValues[1]+"/"+m.groupValues[2]+"/"+m.groupValues[3]
        }
        return null
    }

    fun parse(body:String):List<DexterSubtitleTrack> {
        val root=runCatching { mapper.readTree(body) }.getOrNull()
            ?:return emptyList()
        if(!root.isArray)return emptyList()
        val tracks=linkedMapOf<String,DexterSubtitleTrack>()
        for(entry in root) {
            val raw=entry.path("file").takeIf { it.isTextual }?.asText()
                ?:entry.path("url").takeIf { it.isTextual }?.asText() ?:continue
            val url=DexterParser.externalUrl(raw,BASE) ?:continue
            val uri=runCatching { URI(url) }.getOrNull() ?:continue
            val path=uri.path.lowercase()
            if(listOf("thumbs.vtt","thumbnails.vtt","storyboard","sprite")
                    .any(path::contains))continue
            // Subtitle providers sometimes return a signed download endpoint
            // without an extension. Only trust this explicitly requested
            // subtitle service's JSON, not similarly shaped arbitrary HTML.
            if(path.endsWith(".m3u8") || path.endsWith(".mp4") ||
                path.endsWith(".mkv") || path.endsWith(".mpd"))continue
            val label=entry.path("label").takeIf{it.isTextual}?.asText()
                ?.trim().orEmpty().ifBlank {
                    entry.path("language").takeIf{it.isTextual}?.asText()
                        ?.trim().orEmpty().ifBlank { "Unknown" }
                }.take(64)
            tracks.putIfAbsent(url,DexterSubtitleTrack(label,url))
        }
        // Keep Arabic versions first, then English and the other actual tracks.
        return tracks.values.sortedWith(compareBy<DexterSubtitleTrack> {
            when {
                it.language.contains("arab",ignoreCase=true) ||
                    it.language.startsWith("ar",ignoreCase=true) -> 0
                it.language.contains("english",ignoreCase=true) ||
                    it.language.startsWith("en",ignoreCase=true) -> 1
                else -> 2
            }
        }).take(40)
    }
}
