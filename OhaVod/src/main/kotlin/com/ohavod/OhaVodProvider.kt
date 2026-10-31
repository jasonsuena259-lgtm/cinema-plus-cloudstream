package com.ohavod

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.concurrent.CancellationException
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
    private val metaHosts = listOf("https://mhub.mx", "https://oha.to", "https://www.oha.to")
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
                ?: seasonId.substringAfterLast("_").toIntOrNull() ?: continue
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

    private fun resolvedType(url: String, format: String?): ExtractorLinkType? {
        val f = format?.lowercase()?.trim()
        return when {
            f == "hls" || f == "m3u8" || f == "mpegurl" -> ExtractorLinkType.M3U8
            f == "dash" || f == "mpd" -> ExtractorLinkType.DASH
            f == "mp4" || f == "video" -> ExtractorLinkType.VIDEO
            else -> videoType(url)
        }
    }

    private fun quality(label: String): Int =
        if (label.contains("4k", ignoreCase = true)) 2160
        else Regex("(2160|1440|1080|720|480|360)", RegexOption.IGNORE_CASE)
            .find(label)?.value?.toIntOrNull() ?: Qualities.Unknown.value

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val m = Regex("^(video|series):(\\d+)(?::(\\d+):(\\d+))?$").matchEntire(data)
            ?: throw ErrorLoadingException("OHA: invalid movie/episode data")
        val type = m.groupValues[1]
        if (type == "series" && m.groupValues[3].isEmpty()) return false
        val mediaId = "tmdb:" + m.groupValues[2] + if (type == "series")
            ":" + m.groupValues[3] + ":" + m.groupValues[4] else ""
        val paths = if (type == "video") listOf(
            "/vod/source/movie/" + mediaId + ".json",
            "/vod/source/video/" + mediaId + ".json"
        ) else listOf("/vod/source/series/" + mediaId + ".json")

        var sources: JsonNode = mapper.createArrayNode()
        var loaded = false
        for (path in paths) {
            try {
                val next = rows(vod(path))
                loaded = true
                if (next.isArray && next.size() > 0) {
                    sources = next
                    break
                }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) {}
        }
        if (!loaded) throw ErrorLoadingException(
            "OHA: فشل الاتصال بواجهة مصادر المشاهدة"
        )
        if (sources.size() == 0) throw ErrorLoadingException(
            "OHA: الموقع لم يرجع سيرفرات لهذا العمل"
        )

        // OHA gives many alternate country/quality versions of the same host.
        // Prioritize host variety so one broken host cannot block every result.
        val distinct = linkedMapOf<String, JsonNode>()
        for (source in sources) {
            val url = field(source, "url") ?: continue
            if (!(url.startsWith("https://") || url.startsWith("http://"))) continue
            distinct.putIfAbsent(url, source)
        }
        val candidates = distinct.values.take(30)
        val resolver = OhaVodResolver()
        val usedMedia = mutableSetOf<String>()
        var links = 0

        for (source in candidates) {
            val embed = field(source, "url") ?: continue
            val languages = source.path("languages").takeIf { it.isArray }
                ?.map { it.asText() }?.filter { it.isNotBlank() }
                ?: listOfNotNull(field(source, "language"))
            val label = listOfNotNull(
                field(source, "name") ?: "OHA",
                field(source, "quality"),
                languages.takeIf { it.isNotEmpty() }?.joinToString(", ")
            ).joinToString(" • ")

            val direct = videoType(embed)
            if (direct != null) {
                if (usedMedia.add(embed)) {
                    callback(newExtractorLink(name, label, embed, type = direct) {
                        referer = mainUrl
                        quality = quality(label)
                    })
                    links++
                }
                continue
            }

            // Most OHA embeds return {clientFetch: {...}} from /vod/resolve.
            // Complete that state machine instead of discarding the server.
            val resolved = try {
                resolver.resolve(embed)
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { null }

            if (resolved != null) {
                val typeOfLink = resolvedType(resolved.url, resolved.format)
                    ?: ExtractorLinkType.VIDEO
                if (usedMedia.add(resolved.url)) {
                    callback(newExtractorLink(name, label, resolved.url, type = typeOfLink) {
                        referer = resolved.referer ?: embed
                        headers = resolved.headers
                        quality = quality(label)
                    })
                    links++
                }
                continue
            }

            // Fallback to CloudStream's own host extractors for Dood, VOE,
            // Filemoon, Mixdrop, Vidsonic and other supported hosts.
            try {
                loadExtractor(embed, subtitleCallback) { extracted ->
                    if (usedMedia.add(extracted.url)) {
                        callback(extracted)
                        links++
                    }
                }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) {}
        }
        if (links == 0) throw ErrorLoadingException(
            "OHA: تم العثور على " + distinct.size +
                " سيرفر، لكن تعذّر تحويل روابطها إلى فيديو قابل للتشغيل. " +
                "تتطلب المستضيفات التحقق من اتصالها أو من خدمة clientFetch."
        )
        return links > 0
    }
}
