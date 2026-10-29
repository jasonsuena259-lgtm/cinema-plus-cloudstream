package com.cinelite

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.util.concurrent.TimeUnit

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
        suspend fun add(link: ExtractorLink): Boolean {
            if (link.url.isBlank()) return false
            val key = link.url + "\n" + link.headers.toSortedMap().toString()
            if (key in emitted || !validate(link)) return false
            emitted += key
            callback(link)
            return true
        }
        suspend fun emitCaptured(request: LiteMediaRequest, label: String, referer: String) {
            val headers = request.headers.filterKeys {
                !it.equals("Referer", true) && !it.equals("Range", true)
            }
            val ref = request.headers.entries.firstOrNull {
                it.key.equals("Referer", true)
            }?.value ?: referer
            val type = if (request.url.substringBefore('?').endsWith(".m3u8", true))
                ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            add(newExtractorLink("CineLite", label, request.url, type) {
                this.referer = ref
                this.headers = headers
            })
        }
        // Try each actual iframe/player independently, including alternate servers.
        for (source in sources.take(24)) {
            withTimeoutOrNull(26000) {
                if (source.direct) {
                    val type = if (source.url.substringBefore('?').endsWith(".m3u8", true))
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
                        browser.media(source.url, contentUrl)?.let {
                            emitCaptured(it, source.name, source.url)
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

    companion object {
        suspend fun validate(link: ExtractorLink): Boolean = withContext(Dispatchers.IO) {
            try {
                val builder = Request.Builder().url(link.url)
                link.headers.forEach { (name, value) -> builder.header(name, value) }
                if (link.referer.isNotBlank()) builder.header("Referer", link.referer)
                builder.header("Range", "bytes=0-2047")
                val client = app.baseClient.newBuilder()
                    .callTimeout(12, TimeUnit.SECONDS).build()
                client.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) return@withContext false
                    val body = response.body ?: return@withContext false
                    val stream = body.byteStream()
                    val buffer = ByteArray(2048)
                    var total = 0
                    while (total < buffer.size) {
                        val n = stream.read(buffer, total, buffer.size - total)
                        if (n < 0) break
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
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
    }
}
