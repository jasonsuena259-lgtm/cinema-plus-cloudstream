package com.cinelite

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
            // A network request alone is insufficient: the first request can
            // be an audio-only rendition, which produced a black screen.
            // Inspect the returned HLS playlist and, for media playlists,
            // a small part of the FIRST actual TS segment.
            val hlsSubtitles = verifyObserved(request) ?: return
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
                hlsSubtitles.forEach { (language, url) ->
                    subtitleQueue += SubtitleFile(language, url)
                }
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
            withTimeoutOrNull(31000) {
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
                    for (link in found) {
                        // Generic CloudStream extractors can also return an
                        // audio-only HLS rendition. Recheck video media, not
                        // just the #EXTM3U signature, before publishing it.
                        if (link.type == ExtractorLinkType.M3U8) {
                            val headers = link.headers.toMutableMap()
                            if (link.referer.isNotBlank() &&
                                headers.keys.none { it.equals("Referer", true) })
                                headers["Referer"] = link.referer
                            val tracks = verifyObserved(LiteMediaRequest(link.url, headers))
                                ?: continue
                            if (add(link, capturedInPlayer = true)) {
                                valid = true
                                tracks.forEach { (lang, uri) ->
                                    subtitleQueue += SubtitleFile(lang, uri)
                                }
                            }
                        } else if (add(link)) valid = true
                    }
                    // Capture all other real first-party server selections even
                    // when the generic extractor already returned one link.
                    // Finding one link must not suppress the rest of the menu.
                    coroutineScope {
                        // Scan sources in the original WebView, but forward each
                        // playlist immediately. Waiting for the full 14-agent
                        // scan may let short-lived proxy tokens expire.
                        val pending = Channel<LiteMediaRequest>(Channel.UNLIMITED)
                        val namesAccepted = mutableSetOf<String>()
                        val collector = async {
                            for (request in pending) {
                                val key = request.sourceName.trim().lowercase()
                                if (key in namesAccepted) continue
                                if (CineLiteParser.mediaPlaylistPriority(request.url) >= 10)
                                    continue
                                val before = emitted.size
                                emitCaptured(request,
                                    request.sourceName.takeUnless {
                                        it.isBlank() || it == "CineLite"
                                    } ?: source.name, source.url)
                                if (emitted.size > before) namesAccepted += key
                            }
                        }
                        val siteSources = async {
                            try {
                                withTimeoutOrNull(21500) {
                                    CineLiteMultiSourceBrowser(context).capture(
                                        source.url, contentUrl
                                    ) { request ->
                                        pending.trySend(request)
                                    }
                                }
                            } finally {
                                pending.close()
                            }
                        }
                        val defaultSource = async {
                            if (valid) null else withTimeoutOrNull(7600) {
                                browser.media(source.url, contentUrl)
                            }
                        }
                        defaultSource.await()?.let {
                            if (CineLiteParser.mediaPlaylistPriority(it.url) < 10)
                                emitCaptured(it, source.name, source.url)
                        }
                        val captured = siteSources.await()
                        collector.await()
                        if (captured != null && emitted.isNotEmpty()) {
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

    /**
     * A manifest URL captured by WebView is not automatically a video.
     * Check it with the SAME in-memory WebView request headers/cookies. Media
     * playlists must include a video elementary stream in their first MPEG-TS
     * segment; an audio-only rendition is rejected rather than displayed as a
     * black-screen "working" server. URL/token values are never logged.
     */
    private suspend fun verifyObserved(
        request: LiteMediaRequest
    ): List<Pair<String, String>>? = withContext(Dispatchers.IO) {
        val kind = CineLiteParser.mediaKind(request.url) ?: return@withContext null
        val client = app.baseClient.newBuilder().callTimeout(7, TimeUnit.SECONDS).build()
        fun read(url: String, maxBytes: Int): ByteArray? {
            try {
                val builder = Request.Builder().url(url)
                request.headers.forEach { (key, value) ->
                    if (!key.equals("Range", true) &&
                        !key.equals("Host", true)) builder.header(key, value)
                }
                builder.header("Range", "bytes=0-${maxBytes - 1}")
                return client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val input = response.body?.byteStream() ?: return@use null
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (output.size() < maxBytes) {
                        val count = input.read(buffer, 0,
                            minOf(buffer.size, maxBytes - output.size()))
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { return null }
        }
        if (kind == "mp4") {
            val body = read(request.url, 2048) ?: return@withContext null
            return@withContext if (body.size >= 12 &&
                body.copyOfRange(4, 8).toString(Charsets.US_ASCII) in
                    setOf("ftyp", "styp", "moof")) emptyList() else null
        }
        val data = read(request.url, 65536) ?: return@withContext null
        val playlist = data.toString(Charsets.UTF_8).trimStart('\uFEFF')
        val subtitles = CineLiteParser.hlsSubtitleTracks(playlist, request.url)
        when (CineLiteHlsInspector.kind(playlist)) {
            CineLiteHlsInspector.Kind.VIDEO_MASTER -> subtitles
            CineLiteHlsInspector.Kind.MEDIA -> {
                val relative = CineLiteHlsInspector.firstSegment(playlist)
                    ?: return@withContext null
                val base = request.url.toHttpUrlOrNull() ?: return@withContext null
                val segment = base.resolve(relative) ?: return@withContext null
                // Do not chase arbitrary new hosts from untrusted playlists.
                if (segment.scheme != "https" || segment.host != base.host)
                    return@withContext null
                val bytes = read(segment.toString(), 65536)
                    ?: return@withContext null
                if (CineLiteHlsInspector.tsHasVideo(bytes) == true)
                    subtitles else null
            }
            else -> null
        }
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
