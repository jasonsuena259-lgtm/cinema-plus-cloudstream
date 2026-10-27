package com.cinemaplus

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

class CinemaPlusProvider : MainAPI() {
    override var mainUrl = "https://ffrrx-2000.github.io/cinema-plas-bot"
    override var name = "Cinema Plus"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val mainPage = mainPageOf("latest" to "مختارات الموقع", "movies" to "الأفلام", "series" to "المسلسلات")
    private val repository = CinemaPlusRepository(mainUrl)
    private fun result(item: CinemaItem): SearchResponse = if (item.series) {
        newTvSeriesSearchResponse(item.title, item.url, TvType.TvSeries) { posterUrl = item.poster; year = item.year }
    } else newMovieSearchResponse(item.title, item.url, TvType.Movie) { posterUrl = item.poster; year = item.year }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val all = repository.catalogue().filter { when (request.data) { "movies" -> !it.series; "series" -> it.series; else -> true } }
        val size = 40
        val start = (page - 1).coerceAtLeast(0) * size
        return newHomePageResponse(request.name, all.drop(start).take(size).map(::result), hasNext = start + size < all.size)
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val translated = repository.translatedMatches(q)
        return repository.catalogue().filter { item ->
            val id = if (item.series) Regex("/series/(\\d+)\\.html").find(item.url)?.groupValues?.get(1) else CinemaPlusParser.movieId(item.url)?.first
            item.title.contains(q, ignoreCase = true) || (id != null && (id to item.series) in translated)
        }.map(::result)
    }
    override suspend fun load(url: String): LoadResponse? {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != "ffrrx-2000.github.io" || !uri.path.startsWith("/cinema-plas-bot/")) return null
        val movie = CinemaPlusParser.movieId(url)
        val seriesId = Regex("/series/(\\d+)\\.html$").find(uri.path)?.groupValues?.get(1)
        if (movie == null && seriesId == null) return null
        val item = CinemaPlusParser.findItem(repository.catalogue(), url)
            ?: CinemaItem(seriesId ?: movie!!.first, url, null, seriesId != null, null, emptyList())
        val id = seriesId ?: movie?.first
        val meta = id?.let { repository.metadata(it, item.series) }
        fun field(key: String): String? = meta?.get(key)?.takeUnless { it.isNull }?.asText()?.takeIf { it.isNotBlank() }
        val title = field(if (item.series) "name" else "title") ?: item.title
        val poster = field("poster_path")?.let { "https://image.tmdb.org/t/p/w500$it" } ?: item.poster
        val tags = meta?.get("genres")?.mapNotNull { it.get("name")?.asText() } ?: item.genres
        val yearValue = field(if (item.series) "first_air_date" else "release_date")?.take(4)?.toIntOrNull() ?: item.year
        if (!item.series) return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster; plot = field("overview"); year = yearValue; this.tags = tags; score = Score.from(meta?.get("vote_average")?.asDouble(), 10)
        }
        val html = repository.get(url) ?: return null
        val discovered = CinemaPlusParser.episodes(html)
        val seasons = mutableMapOf<Int, com.fasterxml.jackson.databind.JsonNode>()
        if (id != null) discovered.map { it.season }.distinct().forEach { n -> repository.season(id, n)?.let { seasons[n] = it } }
        val eps = discovered.map { ep ->
            val details = seasons[ep.season]?.path("episodes")?.firstOrNull { it.path("episode_number").asInt() == ep.episode }
            newEpisode("$url#${ep.season}-${ep.episode}") {
                season = ep.season; episode = ep.episode
                name = details?.path("name")?.asText()?.takeIf { it.isNotBlank() } ?: "الحلقة ${ep.episode}"
                description = details?.path("overview")?.asText()?.takeIf { it.isNotBlank() }
                posterUrl = details?.path("still_path")?.takeUnless { it.isNull }?.asText()?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w300$it" }
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
            posterUrl = poster; plot = field("overview"); year = yearValue; this.tags = tags; score = Score.from(meta?.get("vote_average")?.asDouble(), 10)
        }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val links = SourceResolver(repository).resolve(data)
        links.forEach { callback(newExtractorLink("Mux", "Mux", it, ExtractorLinkType.M3U8) { referer = mainUrl + "/" }) }
        return links.isNotEmpty()
    }
}
