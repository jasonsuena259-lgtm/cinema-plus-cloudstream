package com.ohalive

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.concurrent.CancellationException
import java.net.URLEncoder

/** OHA Watched/Rokkr bridge using October 2026 live catalog and resolver endpoints. */
class OhaLiveProvider : MainAPI() {
    override var mainUrl = "https://oha.to"
    override var name = "OHA Live"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override val hasDownloadSupport = false

    private val mapper = jacksonObjectMapper()
    private val domains = listOf("https://oha.to", "https://www.oha.to", "https://oha.cx", "https://oha.online")
    private val groups = listOf(
        "Arabia", "Germany", "United Kingdom", "France", "Turkey", "Italy", "Spain",
        "Portugal", "Albania", "Balkans", "Poland", "Romania", "Bulgaria",
        "Netherlands", "Russia"
    )
    override val mainPage = mainPageOf(
        "Arabia" to "القنوات العربية", "Germany" to "ألمانيا",
        "United Kingdom" to "المملكة المتحدة", "France" to "فرنسا",
        "Turkey" to "تركيا", "Italy" to "إيطاليا", "Spain" to "إسبانيا",
        "Portugal" to "البرتغال", "Albania" to "ألبانيا",
        "Balkans" to "البلقان", "Poland" to "بولندا",
        "Romania" to "رومانيا", "Bulgaria" to "بلغاريا",
        "Netherlands" to "هولندا", "Russia" to "روسيا"
    )

    data class Channel(
        val id: String, val name: String, val country: String,
        val url: String, val logo: String? = null
    )
    data class Page(val items: List<Channel>, val next: String?)

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")

    private suspend fun json(path: String, params: String = ""): JsonNode {
        var lastFailure: Exception? = null
        for (base in domains) {
            try {
                val response = app.get("$base$path$params", headers = mapOf(
                    "Accept" to "application/json",
                    "User-Agent" to "Mozilla/5.0 (Android; OHA CloudStream)"
                ))
                val result = mapper.readTree(response.text)
                require(result != null && result.isObject) { "Invalid OHA JSON" }
                return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastFailure = e
            }
        }
        throw IllegalStateException("OHA API unreachable", lastFailure)
    }

    private fun String?.usable(): String? = this?.trim()?.takeIf {
        it.isNotEmpty() && it != "null"
    }
    private fun field(node: JsonNode, key: String) =
        node.path(key).asText(null).usable()

    private fun channel(item: JsonNode, defaultGroup: String): Channel? {
        if (field(item, "type") != "live") return null
        val id = field(item.path("ids"), "id") ?: field(item, "id") ?: return null
        val title = field(item, "name") ?: field(item, "title") ?: return null
        val url = field(item, "url") ?: return null
        val image = item.path("logo").takeIf { !it.isMissingNode && !it.isNull }
            ?: item.path("artwork")
        val logo = if (image.isTextual) image.asText() else field(image, "url")
        return Channel(id, title, field(item, "group") ?: defaultGroup, url, logo)
    }

    private suspend fun page(group: String, cursor: String?): Page {
        val query = "?filter[group]=" + encode(group) +
            (cursor?.let { "&cursor=" + encode(it) } ?: "")
        val result = json("/live/catalog/live/channels.json", query)
        val items = result.path("items").takeIf { it.isArray }?.mapNotNull {
            channel(it, group)
        } ?: emptyList()
        return Page(items.distinctBy { it.id }, field(result, "nextCursor"))
    }

    private suspend fun pageAt(group: String, pageNumber: Int): Page {
        // Do not silently repeat page 20 indefinitely when the catalog is large.
        if (pageNumber !in 1..20) return Page(emptyList(), null)
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        for (index in 1..pageNumber) {
            val result = page(group, cursor)
            if (index == pageNumber) return result
            cursor = result.next ?: return Page(emptyList(), null)
            if (!seen.add(cursor)) return Page(emptyList(), null)
        }
        return Page(emptyList(), null)
    }

    private fun searchItem(channel: Channel): SearchResponse =
        newLiveSearchResponse(channel.name, channel.toJson()) {
            posterUrl = channel.logo
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = pageAt(request.data, page)
        return newHomePageResponse(
            HomePageList(request.name, data.items.map(::searchItem), false),
            data.next != null
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val results = linkedMapOf<String, Channel>()

        // The current OHA catalog supports server-side search: avoid 30
        // sequential country requests when the query can be resolved directly.
        try {
            var cursor: String? = null
            val seen = mutableSetOf<String>()
            repeat(4) {
                val params = "?search=" + encode(needle) +
                    (cursor?.let { "&cursor=" + encode(it) } ?: "")
                val data = json("/live/catalog/live/channels.json", params)
                val items = data.path("items")
                if (items.isArray) {
                    items.mapNotNull { item ->
                        val group = field(item, "group")
                            ?: item.path("tags").firstOrNull()?.asText()
                            ?: "International"
                        channel(item, group)
                    }.filter { it.name.contains(needle, ignoreCase = true) }
                        .forEach { results[it.id] = it }
                }
                val next = field(data, "nextCursor")
                if (next.isNullOrBlank() || !seen.add(next) || results.size >= 150)
                    return results.values.take(150).map(::searchItem)
                cursor = next
            }
            if (results.isNotEmpty()) return results.values.take(150).map(::searchItem)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Fallback for mirrors that do not implement server-side search.
        }

        for (group in groups) {
            val firstPage = try {
                page(group, null)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                continue
            }
            firstPage.items.filter { it.name.contains(needle, ignoreCase = true) }
                .forEach { results[it.id] = it }
        }
        return results.values.take(150).map(::searchItem)
    }

    override suspend fun load(url: String): LoadResponse {
        val channel = parseJson<Channel>(url)
        return newLiveStreamLoadResponse(channel.name, url, channel.url) {
            posterUrl = channel.logo
            tags = listOf(channel.country)
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playbackPage = if (data.startsWith("/live/play/")) "$mainUrl$data" else data
        val stream = try {
            field(json("/live/resolve", "?url=" + encode(playbackPage)), "url")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return false
        if (!stream.startsWith("https://") && !stream.startsWith("http://")) return false
        val path = stream.substringBefore('?').lowercase()
        val type = when {
            path.endsWith(".mpd") -> ExtractorLinkType.DASH
            path.endsWith(".mp4") || path.endsWith(".webm") -> ExtractorLinkType.VIDEO
            else -> ExtractorLinkType.M3U8
        }
        callback(newExtractorLink(name, "OHA • مباشر", stream, type = type) {
            referer = mainUrl
            quality = Qualities.Unknown.value
        })
        return true
    }
}
