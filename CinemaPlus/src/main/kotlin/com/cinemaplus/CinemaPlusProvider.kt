package com.cinemaplus

import com.lagradost.cloudstream3.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

class CinemaPlusProvider(private val repository: CinemaPlusRepository = CinemaPlusRepository(CinemaPlusParser.BASE)) : MainAPI() {
    override var mainUrl = "https://ffrrx-2000.github.io/cinema-plas-bot"
    override var name = "Cinema Plus"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val mainPage = mainPageOf("الاضافات الأخيرة" to "الإضافات الأخيرة", "movies" to "الأفلام", "series" to "المسلسلات", "الأفلام الجديدة" to "الأفلام الجديدة", "مسلسلات تركية" to "مسلسلات تركية", "افلام عربية" to "أفلام عربية", "افـلام هندية" to "أفلام هندية")
    private fun result(item: CinemaItem): SearchResponse = if (item.series) {
        newTvSeriesSearchResponse(item.title, item.url, TvType.TvSeries) { posterUrl = item.poster; year = item.year }
    } else newMovieSearchResponse(item.title, item.url, TvType.Movie) { posterUrl = item.poster; year = item.year }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val all = repository.catalogue().filter { when (request.data) { "movies" -> !it.series; "series" -> it.series; else -> request.data in it.sections } }.distinctBy { CinemaPlusParser.catalogueKey(it) }
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
        }.distinctBy { CinemaPlusParser.catalogueKey(it) }.map(::result)
    }
    override suspend fun load(url: String): LoadResponse = try {
        loadDetails(url)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: LinkageError) {
        throw ErrorLoadingException("Cinema Plus v6: عدم توافق مع إصدار CloudStream: ${e.javaClass.simpleName}: ${e.message}")
    } catch (e: Exception) {
        throw ErrorLoadingException("Cinema Plus v6: ${e.message ?: e.javaClass.simpleName}")
    }
    private suspend fun loadDetails(rawUrl: String): LoadResponse {
        val url = CinemaPlusParser.canonicalUrl(rawUrl)
            ?: throw ErrorLoadingException("الرابط المحفوظ غير مدعوم. افتح العنصر من بحث Cinema Plus مجددًا؛ روابط go: لا تحتوي مصدر فيديو.")
        val uri = java.net.URI(url)
        val movie = CinemaPlusParser.movieId(url)
        val seriesId = Regex("/series/(\\d+)\\.html$").find(uri.path)?.groupValues?.get(1)
        if (movie == null && seriesId == null) throw ErrorLoadingException("لا يحتوي رابط هذا العنصر على صفحة مسلسل أو معرّف فيديو Mux. أعد البحث عنه داخل الإضافة.")
        val item = CinemaPlusParser.findItem(repository.catalogue(), url)
            ?: CinemaItem(seriesId ?: movie!!.first, url, null, seriesId != null, null, emptyList())
        val id = seriesId ?: movie?.first
        val meta = kotlinx.coroutines.withTimeoutOrNull(4500L) { id?.let { repository.metadata(it, item.series) } }
        fun field(key: String): String? = meta?.get(key)?.takeUnless { it.isNull }?.asText()?.takeIf { it.isNotBlank() }
        val title = field(if (item.series) "name" else "title") ?: item.title
        val poster = field("poster_path")?.let { "https://image.tmdb.org/t/p/w500$it" } ?: item.poster
        val tags = meta?.get("genres")?.mapNotNull { it.get("name")?.asText() } ?: item.genres
        val yearValue = field(if (item.series) "first_air_date" else "release_date")?.take(4)?.toIntOrNull() ?: item.year
        if (!item.series) return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster; plot = field("overview"); year = yearValue; this.tags = tags
        }
        val discovered = repository.seriesEpisodes(url)
        // Season metadata must never hold up the actual episode list. Fetch concurrently
        // with a shared deadline and at most four outstanding optional requests.
        val seasons = java.util.concurrent.ConcurrentHashMap<Int, com.fasterxml.jackson.databind.JsonNode>()
        if (id != null) kotlinx.coroutines.withTimeoutOrNull(4500L) {
            kotlinx.coroutines.coroutineScope {
                val limit = kotlinx.coroutines.sync.Semaphore(4)
                discovered.map { it.season }.distinct().map { n ->
                    async {
                        limit.withPermit { repository.season(id, n)?.let { seasons[n] = it } }
                    }
                }.awaitAll()
            }
        }
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
            posterUrl = poster; plot = field("overview"); year = yearValue; this.tags = tags
        }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val links = SourceResolver(repository).resolve(data)
        links.forEach { callback(newExtractorLink("Mux", "Mux", it, ExtractorLinkType.M3U8) { referer = mainUrl + "/" }) }
        return links.isNotEmpty()
    }
}
