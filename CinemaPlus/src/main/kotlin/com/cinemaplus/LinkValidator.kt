package com.cinemaplus

import com.lagradost.cloudstream3.app
import java.net.URI

class LinkValidator(private val repository: CinemaPlusRepository) {
    suspend fun hls(url: String): Boolean = runCatching {
        var manifestUrl = url
        var manifest = repository.get(manifestUrl) ?: return false
        if (!manifest.trimStart().startsWith("#EXTM3U") || manifest.contains("#EXT-X-KEY:METHOD=SAMPLE-AES")) return false
        if (manifest.contains("#EXT-X-STREAM-INF:")) {
            val lines = manifest.lines()
            val index = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF:") }
            val next = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return false
            manifestUrl = URI(manifestUrl).resolve(next.trim()).toString()
            manifest = repository.get(manifestUrl) ?: return false
        }
        if (!manifest.trimStart().startsWith("#EXTM3U") || !manifest.contains("#EXTINF:") || manifest.contains("#EXT-X-KEY:METHOD=SAMPLE-AES")) return false
        val segment = manifest.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return false
        val response = app.get(URI(manifestUrl).resolve(segment.trim()).toString(), headers = mapOf("Range" to "bytes=0-1023"), timeout = 15)
        response.code in 200..299
    }.getOrDefault(false)
}
