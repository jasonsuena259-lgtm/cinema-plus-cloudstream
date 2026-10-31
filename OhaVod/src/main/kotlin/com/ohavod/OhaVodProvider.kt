package com.ohavod

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder

/**
 * OHA Movies/Series: TMDB metadata catalogs + OHA VOD source listings.
 * HTML/embed pages are never advertised as a directly playable media stream.
 */
class OhaVodProvider : MainAPI() {
    override var mainUrl = "https://oha.to"
    override var name = "OHA Movies & Series | أوها"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val hasDownloadSupport = false

    override val mainPage = mainPageOf(
        "series:trending" to "Trending Series | المسلسلات الرائجة",
        "series:popular" to "Popular Series | المسلسلات الشائعة",
        "video:trending" to "Trending Movies | الأفلام الرائجة",
        "video:popular" to "Popular Movies | الأفلام الشائعة"
    )

    private val mapper = jacksonObjectMapper()
    private val metaHosts = listOf("https://oha.to", "https://www.oha.to", "https://mhub.mx")
    private val vodHosts = listOf("https://oha.to", "https://www.oha.to", "https://oha.cx", "https://oha.online")
    private val headers = mapOf(
        "Accept" to "application/json",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36"
    )

    private data class Media(val id: String, val type: String, val title: String,
                             val poster: String?, val year: Int?)
    private data class Page(val items: List<Media>, val next: String?)

    private fun encode(x: String) = URLEncoder.encode(x, "UTF-8")
    private fun field(node: JsonNode, key: String): String? =
        node.path(key).takeIf { !it.isMissingNode && !it.isNull }?.asText()?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }
    private fun first(vararg s: String?): String? = s.firstOrNull { !it.isNullOrBlank() }
    private fun pic(n: JsonNode, key: String) = first(field(n.path("images"), key), field(n, key))

    private suspend fun json(hosts: List<String>, path: String): JsonNode {
        var failed: Exception? = null
        for (host in hosts) {
            try {
                val r = app.get(host + path, headers = headers + mapOf(
                    "Referer" to (host + "/"), "Origin" to host
                ), timeout = 10)
                if (r.code !in 200..299) continue
                val n = mapper.readTree(r.text)
                if (n != null && (n.isObject || n.isArray)) return n
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = e
            }
        }
        throw ErrorLoadingException("OHA API unavailable: " +
            (failed?.javaClass?.simpleName ?: "HTTP error"))
    }

    private suspend fun meta(path: String) = json(metaHosts, path)
    private suspend fun vod(path: String) = json(vodHosts, path)

    private fun rows(n: JsonNode): JsonNode = when {
        n.isArray -> n
        n.path("items").isArray -> n.path("items")
        n.path("data").isArray -> n.path("data")
        n.path("results").isArray -> n.path("results")
        n.path("sources").isArray -> n.path("sources")
        else -> mapper.createArrayNode()
    }

    private fun media(n: JsonNode, fallback: String): Media? {
        val rawId = first(field(n, "id"), field(n.path("ids"), "tmdb")) ?: return null
        val id = Regex("\\d+").find(rawId.substringAfterLast(":"))?.value ?: return null
        val type = if (first(field(n, "type"), fallback) == "series") "series" else "video"
        val title = first(field(n, "name"), field(n, "title")) ?: return null
        val year = first(field(n, "releaseDate"), field(n, "firstAirDate"))
            ?.take(4)?.toIntOrNull()
        return Media(id, type, title, first(pic(n, "poster"), pic(n, "thumbnail")), year)
    }

    private fun link(m: Media) = mainUrl + "/#/item/tmdb/" + m.type + "/" + m.id
    private fun card(m: Media): SearchResponse = if (m.type == "series")
        newTvSeriesSearchResponse(m.title, link(m)) {
            posterUrl = m.poster
            year = m.year
        } else newMovieSearchResponse(m.title, link(m)) {
            posterUrl = m.poster
            year = m.year
        }

    private fun catalogPath(type: String): String =
        "/tmdb/catalog/" + (if (type == "series") "series/series" else "video/movie") + ".json"

    private suspend fun catalog(type: String, sort: String, cursor: String?): Page {
        val query = "?sort=" + encode(sort) + "&language=en" +
            (cursor?.let { "&cursor=" + encode(it) } ?: "")
        val root = meta(catalogPath(type) + query)
        return Page(rows(root).mapNotNull { media(it, type) }.distinctBy { it.type + it.id },
            first(field(root, "nextCursor"), field(root, "next")))
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page !in 1..15) return newHomePageResponse(
            request.name, emptyList<SearchResponse>(), false
        )
        val type = if (request.data.startsWith("series:")) "series" else "video"
        val sort = if (request.data.endsWith(":popular")) "popular" else "trending"
        var cursor: String? = null
        val used = mutableSetOf<String>()
        var current = Page(emptyList(), null)
        for (index in 1..page) {
            current = catalog(type, sort, cursor)
            if (index != page) {
                cursor = current.next
                if (cursor.isNullOrBlank() || !used.add(cursor)) return newHomePageResponse(
                    request.name, emptyList<SearchResponse>(), false
                )
            }
        }
        return newHomePageResponse(request.name, current.items.map(::card),
            current.next != null && page < 15)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val matches = linkedMapOf<String, Media>()
        var successes = 0
        for (type in listOf("series", "video")) {
            try {
                rows(meta(catalogPath(type) + "?search=" + encode(query.trim()) +
                    "&language=en")).mapNotNull { media(it, type) }.forEach {
                    matches[it.type + ":" + it.id] = it
                }
                successes++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
        if (successes == 0) throw ErrorLoadingException("OHA search unavailable")
        return matches.values.take(150).map(::card)
    }

    private fun parsePage(url: String): Pair<String, String>? {
        val m = Regex("/(series|movie|video)/(?:tmdb:)?(\\d+)", RegexOption.IGNORE_CASE)
            .find(url) ?: return null
        return (if (m.groupValues[1].lowercase() == "series") "series" else "video") to
            m.groupValues[2]
    }

    override suspend fun load(url: String): LoadResponse {
        val (type, id) = parsePage(url) ?: throw ErrorLoadingException("Invalid OHA title URL")
        val endpoint = "/tmdb/item/" + type + "/tmdb:" + id + ".json?language=en"
        val data = meta(endpoint)
        val info = data.path("item").takeIf { it.isObject } ?: data
        val title = first(field(info, "name"), field(info, "title"))
            ?: throw ErrorLoadingException("OHA title metadata missing")
        val poster = first(pic(info, "poster"), pic(info, "thumbnail"))
        val backdrop = first(pic(info, "background"), pic(info, "backdrop"))
        val description = field(info, "description")
        val yearValue = field(info, "releaseDate")?.take(4)?.toIntOrNull()
        val genres = info.path("genres").takeIf { it.isArray }?.mapNotNull {
            if (it.isTextual) it.asText() else field(it, "name")
        } ?: emptyList()

        if (type == "video") return newMovieLoadResponse(
            title, url, TvType.Movie, "video:" + id
        ) {
            posterUrl = poster
            backgroundPosterUrl = backdrop
            plot = description
            year = yearValue
            tags = genres
        }

        val episodes = mutableListOf<Episode>()
        val seasons = info.path("catalogs")
        if (seasons.isArray) for (catalogInfo in seasons.take(40)) {
            val seasonId = field(catalogInfo, "id") ?: continue
            if (!seasonId.startsWith("season_")) continue
            val seasonNumber = field(catalogInfo, "season")?.toIntOrNull()
                ?: Regex("\\d+").find(seasonId)?.value?.toIntOrNull() ?: continue
            val inline = catalogInfo.path("items")
            val episodeRows = if (inline.isArray && inline.size() > 0) inline else try {
                rows(meta("/tmdb/catalog/video/" + encode(seasonId) + ".json?language=en"))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mapper.createArrayNode()
            }
            for (ep in episodeRows) {
                val number = field(ep, "episode")?.toIntOrNull() ?: continue
                val season = field(ep, "season")?.toIntOrNull() ?: seasonNumber
                if (number < 0 || season < 0) continue
                episodes.add(newEpisode("series:" + id + ":" + season + ":" + number) {
                    this.season = season
                    this.episode = number
                    name = first(field(ep, "name"), field(ep, "title")) ?: "Episode " + number
                    posterUrl = first(pic(ep, "thumbnail"), pic(ep, "poster"), poster)
                })
            }
        }
        if (episodes.isEmpty()) throw ErrorLoadingException(
            "OHA: لم يقدم كتالوج المسلسل مواسم وحلقات يمكن عرضها"
        )
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries,
            episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))) {
            posterUrl = poster
            backgroundPosterUrl = backdrop
            plot = description
            year = yearValue
            tags = genres
        }
    }

    private fun videoType(url: String): ExtractorLinkType? {
        val p = url.substringBefore("?").lowercase()
        return when {
            p.endsWith(".m3u8") -> ExtractorLinkType.M3U8
            p.endsWith(".mpd") -> ExtractorLinkType.DASH
            p.endsWith(".mp4") || p.endsWith(".webm") -> ExtractorLinkType.VIDEO
            else -> null
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val m = Regex("^(video|series):(\\d+)(?::(\\d+):(\\d+))?$").matchEntire(data)
            ?: return false
        val type = m.groupValues[1]
        if (type == "series" && m.groupValues[3].isEmpty()) return false
        val mediaId = "tmdb:" + m.groupValues[2] + if (type == "series")
            ":" + m.groupValues[3] + ":" + m.groupValues[4] else ""
        val root = vod("/vod/source/" + type + "/" + mediaId + ".json")
        val sources = rows(root)
        var emitted = 0
        val seen = mutableSetOf<String>()
        for (s in sources.take(100)) {
            val embed = field(s, "url") ?: continue
            if (!embed.startsWith("http://") && !embed.startsWith("https://")) continue
            val langs = s.path("languages").takeIf { it.isArray }
                ?.mapNotNull { it.asText()?.takeIf { lang -> lang.isNotBlank() } }
                ?: emptyList()
            if (!seen.add(embed + "|" + langs.joinToString())) continue
            val label = listOfNotNull(field(s, "name") ?: "OHA",
                field(s, "quality"), langs.takeIf { it.isNotEmpty() }?.joinToString(", "))
                .joinToString(" • ")
            var stream = embed
            var kind = videoType(stream)
            if (kind == null) {
                try {
                    loadExtractor(embed, subtitleCallback, callback = { link ->
                        callback(link)
                        emitted++
                    })
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
                try {
                    val resolved = vod("/vod/resolve?url=" + encode(embed))
                    val url = field(resolved, "url")
                    if (url != null && videoType(url) != null) {
                        stream = url
                        kind = videoType(url)
                    }
                    // A clientFetch descriptor requires multiple client/server hops.
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
            }
            if (kind != null) {
                callback(newExtractorLink(name, label, stream, type = kind) {
                    referer = if (stream == embed) mainUrl else embed
                    quality = Qualities.Unknown.value
                })
                emitted++
            }
        }
        return emitted > 0
    }
}
