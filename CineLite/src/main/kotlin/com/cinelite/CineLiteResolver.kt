package com.cinelite

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream

/**
 * A source is published only after its bytes match real HLS or MP4 content.
 * WebView is a fallback for JavaScript-only embed players, never a bypass.
 */
class CineLiteResolver(private val context: Context) {
    private val browser = CineLiteBrowser(context)

    suspend fun resolve(
        sources: List<LiteSource>,
        contentUrl: String,
        subtitles: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val emitted = mutableSetOf<String>()
        val subtitleQueue = mutableListOf<SubtitleFile>()
        suspend fun add(link: ExtractorLink, capturedInPlayer: Boolean = false): Boolean {
            if (link.url.isBlank()) return false
            // Different WebViews can observe the same signed URL with slightly
            // different headers. Show one CloudStream option per actual URL.
            val key = link.url
            if (key in emitted) return false
            // Browser-intercepted signed URLs have already been requested by
            // CineLite's native player. Re-fetching them before ExoPlayer tries
            // playback may consume a short-lived token and cause video failure.
            if (!capturedInPlayer && !validate(link)) return false
            emitted += key
            callback(link)
            // No extra HLS probe for signed WebView URLs. The HLS player reads
            // its own embedded subtitle tracks; exposed SRTs are collected below.
            if (!capturedInPlayer) subtitleQueue += sidecarSubtitles(link)
            return true
        }
        suspend fun emitCaptured(request: LiteMediaRequest, label: String, referer: String) {
            val headers = request.headers.filterKeys {
                !it.equals("Referer", true) && !it.equals("Range", true)
            }
            val ref = request.headers.entries.firstOrNull {
                it.key.equals("Referer", true)
            }?.value ?: referer
            val type = if (CineLiteParser.mediaKind(request.url) == "hls")
                ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            val valid = add(newExtractorLink("CineLite", label, request.url, type) {
                this.referer = ref
                this.headers = headers
            }, capturedInPlayer = true)
            if (valid) {
                // Actual SRT/VTT/ASS subtitle requests, not guesses from titles.
                request.subtitleUrls.filter(CineLiteParser::isSubtitleFile)
                    .distinct().sortedBy { subtitleUrl ->
                        val language = CineLiteParser.subtitleLanguage(
                            request.subtitleLabels[subtitleUrl].orEmpty(), subtitleUrl
                        )
                        CineLiteParser.subtitlePriority(language)
                    }.take(40).forEach { subtitleUrl ->
                        val language = CineLiteParser.subtitleLanguage(
                            request.subtitleLabels[subtitleUrl].orEmpty(), subtitleUrl
                        )
                        subtitleQueue += SubtitleFile(language, subtitleUrl)
                    }
            }
        }
        // Try each actual iframe/player independently, including alternate servers.
        for (source in sources.take(8)) {
            val isAnimePlayer = "/anime/" in source.url || "/watch/anime/" in contentUrl
            withTimeoutOrNull(9000) {
                if (source.direct) {
                    val type = if (CineLiteParser.mediaKind(source.url) == "hls")
                        ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    add(newExtractorLink("CineLite", source.name, source.url, type) {
                        referer = contentUrl
                    })
                } else {
                    val found = mutableListOf<ExtractorLink>()
                    val discoveredSubs = mutableListOf<SubtitleFile>()
                    try {
                        withTimeoutOrNull(1100) {
                            loadExtractor(source.url, contentUrl, { discoveredSubs += it }, { found += it })
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    } catch (_: LinkageError) {
                    }
                    var valid = false
                    for (link in found) if (add(link)) valid = true
                    if (!valid) coroutineScope {
                        // Keep the Android-proven first-source path intact. In
                        // parallel, inspect the SAME site's real source switcher
                        // (not fabricated agent URLs) in one extra WebView.
                        // Never serialize six independent player launches.
                        val defaultSource = async {
                            withTimeoutOrNull(7600) { browser.media(source.url, contentUrl) }
                        }
                        val siteSources = async {
                            withTimeoutOrNull(7600) {
                                CineLiteMultiSourceBrowser(context).capture(source.url, contentUrl)
                            }
                        }
                        defaultSource.await()?.let {
                            emitCaptured(it, source.name, source.url)
                        }
                        siteSources.await()?.let { captured ->
                            // Associate each observed HLS/MP4 request with the
                            // actual server that the website had selected.
                            captured.streams
                                .sortedBy { it.sourceName == "CineLite" }
                                .take(16).forEach { stream ->
                                    val label = stream.sourceName
                                        .takeUnless { it.isBlank() || it == "CineLite" }
                                        ?: source.name
                                    emitCaptured(stream, label, source.url)
                                }
                            if (emitted.isNotEmpty()) {
                                captured.subtitles
                                    .filter { CineLiteParser.isSubtitleFile(it.url) }
                                    .take(40).forEach { subtitle ->
                                        subtitleQueue += SubtitleFile(
                                            CineLiteParser.subtitleLanguage(
                                                subtitle.title, subtitle.url
                                            ), subtitle.url
                                        )
                                    }
                            }
                        }
                    }
                    // Additional servers are shown ONLY for HLS/MP4 requests
                    // genuinely observed after selecting their site menu rows.
                    // A site menu with 0 agents cannot yield invented links.
                    if (emitted.isNotEmpty()) subtitleQueue += discoveredSubs
                }
            }
        }
        // A SPA may load its own first-party media without an iframe.
        if (emitted.isEmpty()) {
            withTimeoutOrNull(6000) {
                browser.media(contentUrl, CineLiteParser.BASE + "/")?.let {
                    emitCaptured(it, "CineLite player", contentUrl)
                }
            }
        }
        if (emitted.isEmpty()) throw ErrorLoadingException(
            "CineLite: no verified playable media was discovered from the rendered player"
        )
        subtitleQueue.distinctBy { it.url }.forEach(subtitles)
        return true
    }

    private suspend fun sidecarSubtitles(link: ExtractorLink): List<SubtitleFile> =
        withContext(Dispatchers.IO) {
            if (link.type != ExtractorLinkType.M3U8) return@withContext emptyList()
            try {
                val request = Request.Builder().url(link.url)
                    .header("Range", "bytes=0-65535")
                link.headers.forEach { (name, value) -> request.header(name, value) }
                if (link.referer.isNotBlank()) request.header("Referer", link.referer)
                app.baseClient.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
                    .newCall(request.build()).execute().use { response ->
                        if (!response.isSuccessful) return@withContext emptyList()
                        val stream = response.body?.byteStream() ?: return@withContext emptyList()
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (out.size() < 65536) {
                            val count = stream.read(buffer, 0, minOf(buffer.size, 65536 - out.size()))
                            if (count <= 0) break
                            out.write(buffer, 0, count)
                        }
                        val playlist = out.toString("UTF-8")
                        if (!playlist.trimStart().startsWith("#EXTM3U")) return@withContext emptyList()
                        CineLiteParser.hlsSubtitleTracks(playlist, link.url)
                            .map { (lang, url) -> SubtitleFile(lang, url) }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }

    companion object {
        suspend fun validate(link: ExtractorLink): Boolean = withContext(Dispatchers.IO) {
            try {
                val client = app.baseClient.newBuilder()
                    .callTimeout(12, TimeUnit.SECONDS).build()
                // Some HLS services reject byte-range requests (especially on
                // expiring signed playlists) but allow an ordinary GET. Never
                // emit a link until the returned bytes identify actual media.
                for (range in listOf(true, false)) {
                    val builder = Request.Builder().url(link.url)
                    link.headers.forEach { (name, value) ->
                        if (!name.equals("Range", ignoreCase = true)) builder.header(name, value)
                    }
                    if (link.referer.isNotBlank()) builder.header("Referer", link.referer)
                    if (range) builder.header("Range", "bytes=0-2047")
                    val valid = client.newCall(builder.build()).execute().use { response ->
                        if (!response.isSuccessful) return@use false
                        val stream = response.body?.byteStream() ?: return@use false
                        val buffer = ByteArray(2048)
                        var total = 0
                        while (total < buffer.size) {
                            val n = stream.read(buffer, total, buffer.size - total)
                            if (n <= 0) break
                            total += n
                        }
                        val prefix = buffer.copyOf(total)
                        when (link.type) {
                            ExtractorLinkType.M3U8 -> prefix.toString(Charsets.UTF_8)
                                .trimStart('\uFEFF', '\n', '\r', ' ', '\t')
                                .startsWith("#EXTM3U")
                            ExtractorLinkType.VIDEO -> prefix.size >= 12 &&
                                prefix.copyOfRange(4, 8).toString(Charsets.US_ASCII) in
                                    setOf("ftyp", "styp", "moof")
                            else -> false
                        }
                    }
                    if (valid) return@withContext true
                }
                false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
    }
}
