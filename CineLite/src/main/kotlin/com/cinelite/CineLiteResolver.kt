package com.cinelite

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    /**
     * Do not replace the earlier working resolver. Run genuine dynamic server
     * discovery next to it: a bad or empty source menu must never discard the
     * original validated media link. Only publish actual HLS/MP4 bytes.
     */
    suspend fun resolve(
        sources: List<LiteSource>,
        contentUrl: String,
        subtitles: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val published = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val deliveredSubs = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val onLink: (ExtractorLink) -> Unit = { link ->
            if (published.add(link.url)) callback(link)
        }
        val onSubtitle: (SubtitleFile) -> Unit = { sub ->
            if (deliveredSubs.add(sub.url)) subtitles(sub)
        }
        val player = sources.firstOrNull { !it.direct && it.url.startsWith("https://") }
        val dynamic = player?.let { p ->
            async {
                withTimeoutOrNull(8600L) {
                    CineLiteMultiSourceBrowser(context).capture(p.url, contentUrl)
                }
            }
        }
        val fallback = async {
            try {
                resolveLegacy(sources, contentUrl, onSubtitle, onLink)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
        val observed = dynamic?.await()
        if (observed != null && observed.streams.isNotEmpty()) {
            val semaphore = Semaphore(4)
            val verified = observed.streams.distinctBy { it.url }.take(18).map { request ->
                async(Dispatchers.IO) {
                    semaphore.withPermit { verifyLiveCapture(request, player?.url ?: contentUrl) }
                }
            }.awaitAll().filterNotNull()
            for (link in verified) onLink(link)
            // Subtitles must be links actually present in the player; Arabic
            // metadata is not fabricated and unavailable languages are omitted.
            if (verified.isNotEmpty()) observed.subtitles.distinctBy { it.url }
                .forEach { onSubtitle(SubtitleFile(it.title, it.url)) }
        }
        val fallbackSuccess = fallback.await()
        if (!fallbackSuccess && published.isEmpty()) throw ErrorLoadingException(
            "CineLite: no reachable server URL was returned by the site's player"
        )
        published.isNotEmpty()
    }

    private suspend fun verifyLiveCapture(
        request: LiteMediaRequest,
        pageUrl: String
    ): ExtractorLink? = withContext(Dispatchers.IO) {
        val mediaType = when (CineLiteParser.mediaKind(request.url)) {
            "hls" -> ExtractorLinkType.M3U8
            "mp4" -> ExtractorLinkType.VIDEO
            else -> return@withContext null
        }
        try {
            val builder = Request.Builder().url(request.url)
            request.headers.forEach { (name, value) ->
                if (!name.equals("Range", true) && !name.equals("Referer", true))
                    builder.header(name, value)
            }
            val ref = request.headers.entries.firstOrNull {
                it.key.equals("Referer", true)
            }?.value ?: pageUrl
            if (ref.isNotBlank()) builder.header("Referer", ref)
            if (mediaType == ExtractorLinkType.VIDEO)
                builder.header("Range", "bytes=0-2047")
            val client = app.baseClient.newBuilder()
                .callTimeout(2600, TimeUnit.MILLISECONDS).build()
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val stream = response.body?.byteStream() ?: return@withContext null
                val buffer = ByteArray(2048)
                var size = 0
                while (size < buffer.size) {
                    val count = stream.read(buffer, size, buffer.size - size)
                    if (count <= 0) break
                    size += count
                }
                val prefix = buffer.copyOf(size)
                val good = if (mediaType == ExtractorLinkType.M3U8)
                    prefix.toString(Charsets.UTF_8).trimStart('\uFEFF', '\n', '\r', ' ', '\t')
                        .startsWith("#EXTM3U")
                else prefix.size >= 12 && prefix.copyOfRange(4, 8)
                    .toString(Charsets.US_ASCII) in setOf("ftyp", "styp", "moof")
                if (!good) return@withContext null
                newExtractorLink("CineLite", request.sourceName.ifBlank { "CineLite" },
                    request.url, mediaType) {
                    referer = ref
                    headers = request.headers.filterKeys {
                        !it.equals("Referer", true) && !it.equals("Range", true)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    // Kept intact as a backwards-compatible fallback after the v5 regression.
    private suspend fun resolveLegacy(
        sources: List<LiteSource>,
        contentUrl: String,
        subtitles: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val emitted = mutableSetOf<String>()
        val subtitleQueue = mutableListOf<SubtitleFile>()
        suspend fun add(link: ExtractorLink): Boolean {
            if (link.url.isBlank()) return false
            val key = link.url + "\n" + link.headers.toSortedMap().toString()
            if (key in emitted || !validate(link)) return false
            emitted += key
            callback(link)
            // If a valid HLS master playlist exposes *direct text* subtitle tracks,
            // publish those as extra choices; nested HLS subtitle streams stay in the
            // HLS manifest for the player itself.
            subtitleQueue += sidecarSubtitles(link)
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
            })
            if (valid) {
                // Actual SRT/VTT/ASS subtitle requests, not guesses from titles.
                request.subtitleUrls.distinct().take(40).forEach { url ->
                    val path = url.substringBefore('?').substringAfterLast('/')
                    val title = path.substringBeforeLast('.').replace('.', ' ')
                        .replace('_', ' ').take(90).ifBlank { "Subtitle" }
                    subtitleQueue += SubtitleFile(title, url)
                }
            }
        }
        // Try each actual iframe/player independently, including alternate servers.
        for (source in sources.take(24)) {
            val isAnimePlayer = "/anime/" in source.url || "/watch/anime/" in contentUrl
            withTimeoutOrNull(if (isAnimePlayer) 60000 else 26000) {
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
                        loadExtractor(source.url, contentUrl, { discoveredSubs += it }, { found += it })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    } catch (_: LinkageError) {
                    }
                    var valid = false
                    for (link in found) if (add(link)) valid = true
                    if (!valid) {
                        withTimeoutOrNull(12000) {
                            browser.media(source.url, contentUrl)?.let {
                                emitCaptured(it, source.name, source.url)
                            }
                        }
                    }
                    if (isAnimePlayer) {
                        // Six labels were observed in CineLite's real anime player UI.
                        // Visit actual buttons individually; return only HLS/MP4 bytes
                        // that pass validate(). Never reuse a user's temporary token.
                        for (agent in listOf(
                            "Reyna Sub", "Reyna Dub", "Chamber Sub",
                            "Chamber Dub", "Neon Sub", "Neon Dub"
                        )) {
                            withTimeoutOrNull(7000) {
                                browser.media(source.url, contentUrl, agent)?.let {
                                    emitCaptured(it, agent, source.url)
                                }
                            }
                        }
                    }
                    if (emitted.isNotEmpty()) subtitleQueue += discoveredSubs
                }
            }
        }
        // A SPA may load its own first-party media without an iframe.
        if (emitted.isEmpty()) {
            withTimeoutOrNull(20000) {
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
