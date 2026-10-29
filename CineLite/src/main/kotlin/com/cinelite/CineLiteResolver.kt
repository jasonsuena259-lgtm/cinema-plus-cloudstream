package com.cinelite

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Resolve multiple real media requests from one embedded-player WebView.
 *
 * There is a single 9.4 second upper-bound for discovery AND validation.
 * One WebView collects alternate sources and player-declared subtitles;
 * independent network validations run concurrently. No permanent signed URLs.
 */
class CineLiteResolver(private val context: Context) {
    private data class Candidate(
        val name: String,
        val url: String,
        val referer: String,
        val headers: Map<String, String> = emptyMap()
    )
    private data class Verified(val link: ExtractorLink, val subtitles: List<SubtitleFile>)

    suspend fun resolve(
        sources: List<LiteSource>,
        contentUrl: String,
        subtitles: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = false
        withTimeoutOrNull(9400L) {
            val direct = sources.filter { it.direct && CineLiteParser.mediaKind(it.url) != null }
                .map { Candidate(it.name, it.url, contentUrl) }
            // The player is scanned once. Reopening it for every server used to
            // take 40+ seconds and still returned only its first source.
            val player = sources.firstOrNull { !it.direct }
            val observed = if (player != null) {
                CineLiteMultiSourceBrowser(context).capture(player.url, contentUrl)
            } else LiteSourceCapture(emptyList(), emptyList())

            val video = observed.streams.map { request ->
                Candidate(
                    request.sourceName.ifBlank { "CineLite" },
                    request.url,
                    request.headers.entries.firstOrNull {
                        it.key.equals("Referer", true)
                    }?.value ?: player?.url ?: contentUrl,
                    request.headers.filterKeys {
                        !it.equals("Referer", true) && !it.equals("Range", true)
                    }
                )
            }
            val results = (video + direct).distinctBy { it.url }.take(16)
            if (results.isEmpty()) return@withTimeoutOrNull

            val verified = coroutineScope {
                results.map { candidate ->
                    async(Dispatchers.IO) { verify(candidate) }
                }.awaitAll().filterNotNull()
            }
            if (verified.isEmpty()) return@withTimeoutOrNull

            // Report only genuinely available links: a missing server is never
            // represented by a fabricated entry.
            verified.distinctBy { it.link.url }.forEach { item ->
                emitted = true
                callback(item.link)
            }
            val allSubs = (
                observed.subtitles.map { SubtitleFile(it.title, it.url) } +
                    verified.flatMap { it.subtitles }
                ).distinctBy { it.url }
            allSubs.forEach(subtitles)
        }
        if (!emitted) throw ErrorLoadingException(
            "CineLite: no accessible video streams were verified within the 9-second scan"
        )
        return true
    }

    private suspend fun verify(candidate: Candidate): Verified? =
        withContext(Dispatchers.IO) {
            val type = when (CineLiteParser.mediaKind(candidate.url)) {
                "hls" -> ExtractorLinkType.M3U8
                "mp4" -> ExtractorLinkType.VIDEO
                else -> return@withContext null
            }
            try {
                val request = Request.Builder().url(candidate.url)
                candidate.headers.forEach { (key, value) ->
                    if (!key.equals("Range", true)) request.header(key, value)
                }
                if (candidate.referer.isNotBlank()) request.header("Referer", candidate.referer)
                // HLS proxy endpoints commonly reject Range; request full
                // manifest instead, but read only a short prefix.
                if (type == ExtractorLinkType.VIDEO)
                    request.header("Range", "bytes=0-4095")
                val client = app.baseClient.newBuilder()
                    .callTimeout(1850, TimeUnit.MILLISECONDS).build()
                client.newCall(request.build()).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val stream = response.body?.byteStream() ?: return@withContext null
                    val buffer = ByteArray(if (type == ExtractorLinkType.M3U8) 16384 else 4096)
                    var total = 0
                    while (total < buffer.size) {
                        val count = stream.read(buffer, total, buffer.size - total)
                        if (count <= 0) break
                        total += count
                    }
                    val contents = buffer.copyOf(total)
                    val subtitleItems: List<SubtitleFile>
                    if (type == ExtractorLinkType.M3U8) {
                        val master = contents.toString(Charsets.UTF_8)
                            .trimStart('\uFEFF', '\n', '\r', ' ', '\t')
                        if (!master.startsWith("#EXTM3U")) return@withContext null
                        subtitleItems = CineLiteParser.hlsSubtitleTracks(master, candidate.url)
                            .map { (label, url) -> SubtitleFile(label, url) }
                    } else {
                        if (contents.size < 12 || contents.copyOfRange(4, 8)
                                .toString(Charsets.US_ASCII) !in setOf("ftyp", "styp", "moof"))
                            return@withContext null
                        subtitleItems = emptyList()
                    }
                    Verified(newExtractorLink("CineLite", candidate.name,
                        candidate.url, type) {
                        referer = candidate.referer
                        headers = candidate.headers
                    }, subtitleItems)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { null }
        }
}
