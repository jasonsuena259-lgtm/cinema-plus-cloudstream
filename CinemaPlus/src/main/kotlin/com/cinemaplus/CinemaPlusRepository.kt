package com.cinemaplus

import com.lagradost.cloudstream3.app
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

class CinemaPlusRepository(
    private val base: String,
    private val fetch: suspend (String, Int) -> String? = { url, seconds ->
        app.get(url, timeout = seconds.toLong()).takeIf { it.code in 200..299 }?.text
    }
) {
    private var cached = emptyList<CinemaItem>()
    private var cachedAt = 0L
    suspend fun get(url: String, timeout: Int = 10, attempts: Int = 2): String? {
        repeat(attempts) {
            try { fetch(url, timeout)?.let { text -> return text } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* One retry for network errors; metadata is optional. */ }
        }
        return null
    }
    suspend fun seriesEpisodes(url: String): List<EpisodeKey> {
        val html = get(url) ?: throw IllegalStateException("تعذر جلب صفحة المسلسل من Cinema Plus. تحقق من الاتصال بالموقع وأعد المحاولة.")
        return CinemaPlusParser.episodes(html).ifEmpty {
            throw IllegalStateException("صفحة المسلسل لا تحتوي حلقات بروابط تشغيل قابلة للاستخراج من الموقع.")
        }
    }
    suspend fun catalogue(refresh: Boolean = false): List<CinemaItem> {
        if (!refresh && cached.isNotEmpty() && System.currentTimeMillis() - cachedAt < 300_000) return cached
        val htmlItems = listOf("/index.html", "/discover.html").flatMap { path -> get(base + path)?.let { CinemaPlusParser.cards(it, base + path) }.orEmpty() }
        val jsonItems = get("$base/movies.json")?.let { json -> runCatching {
            jacksonObjectMapper().readTree(json).mapNotNull { node ->
                val id = node.path("id").asText().takeIf { it.matches(Regex("[0-9]+")) } ?: return@mapNotNull null
                val mux = node.path("mux").asText().takeIf { it.matches(Regex("[A-Za-z0-9]+")) } ?: return@mapNotNull null
                val title = node.path("title").asText().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                CinemaItem(title, "$base/movie.html?tmdb=$id&mux=$mux", node.path("poster").asText().ifBlank { null }, false, node.path("year").asText().toIntOrNull(), emptyList())
            }
        }.getOrNull() }.orEmpty()
        val items = CinemaPlusParser.mergeSections(htmlItems + jsonItems)
        if (items.isNotEmpty()) { cached = items; cachedAt = System.currentTimeMillis() }
        return items.ifEmpty { cached }
    }
    suspend fun translatedMatches(query: String): Set<Pair<String, Boolean>> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://api.themoviedb.org/3/search/multi?api_key=06f120992cfacd7c118f6e7086d23544&language=ar&query=$encoded"
        return get(url, timeout = 4, attempts = 1)?.let { json -> runCatching {
            jacksonObjectMapper().readTree(json).path("results").mapNotNull { node ->
                val kind = node.path("media_type").asText()
                val id = node.path("id").asText()
                if (kind != "movie" && kind != "tv") null else id to (kind == "tv")
            }.toSet()
        }.getOrNull() }.orEmpty()
    }
    suspend fun season(id: String, number: Int): JsonNode? {
        val url = "https://api.themoviedb.org/3/tv/$id/season/$number?api_key=06f120992cfacd7c118f6e7086d23544&language=ar"
        return get(url, timeout = 4, attempts = 1)?.let { runCatching { jacksonObjectMapper().readTree(it) }.getOrNull() }
    }
    suspend fun metadata(id: String, series: Boolean): JsonNode? {
        val kind = if (series) "tv" else "movie"
        val url = "https://api.themoviedb.org/3/$kind/$id?api_key=06f120992cfacd7c118f6e7086d23544&language=ar"
        return get(url, timeout = 4, attempts = 1)?.let { runCatching { jacksonObjectMapper().readTree(it) }.getOrNull() }
    }
}
