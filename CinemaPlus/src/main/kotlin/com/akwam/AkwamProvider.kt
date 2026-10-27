package com.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.jsoup.nodes.Document
import java.net.URI

class AkwamProvider : MainAPI() {
    override var mainUrl = "https://akwam.day"
    override var name = "Akwam | أكوام"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val mainPage = mainPageOf("/" to "أحدث الإضافات", "/category/افلام-اجنبي" to "أفلام أجنبية", "/category/افلام-عربي" to "أفلام عربية", "/category/مسلسلات-اجنبي" to "مسلسلات أجنبية", "/category/مسلسلات-عربي" to "مسلسلات عربية", "/category/مسلسلات-تركية" to "مسلسلات تركية")
    private suspend fun page(url: String): String? = try { app.get(url, timeout = 15).takeIf { it.code in 200..299 }?.text } catch (e: Exception) { null }
    private fun result(item: AkwamItem): SearchResponse = if (item.series) newTvSeriesSearchResponse(item.title, item.url) { posterUrl = item.poster } else newMovieSearchResponse(item.title, item.url) { posterUrl = item.poster }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "/" && page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val url = mainUrl + request.data + if (page > 1) "?page=$page" else ""
        val html = page(url) ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val doc = AkwamParser.document(html, url)
        val list = AkwamParser.cards(html, url).map(::result)
        val hasNext = AkwamParser.hasNext(doc, page)
        return newHomePageResponse(request.name, list, hasNext = hasNext)
    }
    override suspend fun search(query: String): List<SearchResponse> = search(query, 1).items
    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), false)
        val url = "$mainUrl/search?s=" + java.net.URLEncoder.encode(query, "UTF-8") + "&page=$page"
        val html = page(url) ?: return newSearchResponseList(emptyList(), false)
        val items = AkwamParser.cards(html, url).map(::result)
        return newSearchResponseList(items, AkwamParser.hasNext(AkwamParser.document(html, url), page))
    }
    override suspend fun load(url: String): LoadResponse? {
        val host = runCatching { URI(url).host }.getOrNull() ?: return null
        if (host != "akwam.day") return null
        var currentUrl = url
        var html = page(url) ?: return null
        var doc = AkwamParser.document(html, url)
        if (url.contains("/episode/")) {
            val seriesUrl = doc.selectFirst("nav.bc a[href*=/series/]")?.absUrl("href")
            if (!seriesUrl.isNullOrBlank()) { val fetched = page(seriesUrl); if (fetched != null) { currentUrl = seriesUrl; html = fetched; doc = AkwamParser.document(fetched, seriesUrl) } }
        }
        val title = doc.selectFirst(".info-side h1.title, .info-side span.title")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: return null
        val plot = doc.selectFirst(".info-side .description")?.text()?.trim()
        val style = doc.selectFirst(".poster-side .poster")?.attr("style").orEmpty()
        val poster = Regex("""url\(([^)]+)\)""").find(style)?.groupValues?.get(1)?.trim('"', '\'')
        val tags = doc.select("a[href*=/genre/]").map { it.text() }.filter { it.isNotBlank() }.distinct()
        if (url.contains("/film/")) return newMovieLoadResponse(title, url, TvType.Movie, url) { posterUrl = poster; this.plot = plot; this.tags = tags }
        val seasons = AkwamParser.seasons(doc)
        val eps = mutableListOf<AkwamEpisode>()
        if (seasons.isEmpty()) eps += AkwamParser.episodes(doc, 1)
        else for ((seasonUrl, number) in seasons) {
            val seasonHtml = page(seasonUrl) ?: continue
            eps += AkwamParser.episodes(AkwamParser.document(seasonHtml, seasonUrl), number)
        }
        val episodes = eps.distinctBy { it.url }.sortedWith(compareBy({ it.season }, { it.number })).map { ep ->
            newEpisode(ep.url) { season = ep.season; episode = ep.number; name = "الحلقة ${ep.number}" }
        }
        return newTvSeriesLoadResponse(title, currentUrl, TvType.TvSeries, episodes) { posterUrl = poster; this.plot = plot; this.tags = tags }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean = AkwamResolver(mainUrl).resolve(data, subtitleCallback, callback)
}
