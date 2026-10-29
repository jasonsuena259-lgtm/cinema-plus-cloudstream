package com.cinelite

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Content must be present in CineLite's rendered DOM. No synthetic TMDB cards. */
class CineLiteProvider(private val context: Context) : MainAPI() {
    override var mainUrl = CineLiteParser.BASE
    override var name = "CineLite"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    // Only the root URL has been observed; category URL patterns need real-site confirmation.
    override val mainPage = mainPageOf("/" to "CineLite")
    private val browser = CineLiteBrowser(context)

    private fun card(item: LiteCard): SearchResponse = when (item.type) {
        "movie" -> newMovieSearchResponse(item.title, item.url) {
            posterUrl = item.poster
        }
        else -> newTvSeriesSearchResponse(
            item.title, item.url, if (item.type == "anime") TvType.Anime else TvType.TvSeries
        ) { posterUrl = item.poster }
    }

    private suspend fun html(url: String): String =
        browser.snapshot(url) ?: throw ErrorLoadingException("CineLite: page did not render")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Do not claim pagination without a confirmed next-page route.
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val list = CineLiteParser.cards(html(mainUrl), mainUrl)
        if (list.isEmpty()) throw ErrorLoadingException("CineLite: no real cards found in the page DOM")
        return newHomePageResponse(request.name, list.map(::card), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val url = mainUrl + "/search"
        val raw = browser.snapshot(url, query)
            ?: throw ErrorLoadingException("CineLite: search page did not render")
        val list = CineLiteParser.cards(raw, url)
        if (list.isEmpty()) throw ErrorLoadingException(
            "CineLite: no matching real cards were found in search results"
        )
        return list.map(::card)
    }

    override suspend fun load(url: String): LoadResponse {
        val identity = CineLiteParser.identity(url)
        if (identity != null) {
            if (identity.type == "movie") {
                val embed = CineLiteParser.embedMovie(identity.id)
                    ?: throw ErrorLoadingException("CineLite: invalid movie ID")
                return newMovieLoadResponse(identity.title, url, TvType.Movie, embed) {
                    posterUrl = identity.poster
                }
            }
            val episodes = browser.seriesEpisodes(identity.id, identity.title)
            if (episodes.isEmpty()) throw ErrorLoadingException(
                "CineLite: no confirmed episode list for this series"
            )
            return newTvSeriesLoadResponse(
                identity.title, url,
                if (identity.type == "anime") TvType.Anime else TvType.TvSeries,
                episodes.map { episode ->
                    newEpisode(episode.url) {
                        name = episode.title
                        season = episode.season
                        this.episode = episode.episode
                    }
                }
            ) { posterUrl = identity.poster }
        }
        val valid = CineLiteParser.siteUrl(url)
            ?: throw ErrorLoadingException("CineLite: invalid content URL")
        val raw = html(valid)
        val title = CineLiteParser.title(raw, valid)
            ?: throw ErrorLoadingException("CineLite: no title found")
        val poster = CineLiteParser.poster(raw, valid)
        val plot = CineLiteParser.description(raw)
        return newMovieLoadResponse(title, valid, TvType.Movie, valid) {
            posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = data.toHttpUrlOrNull() 
        if (parsed?.scheme == "https" && parsed.host == "embed.cinelite.xyz"
            && parsed.pathSegments.all { it.toIntOrNull() != null }) {
            return CineLiteResolver(context).resolve(
                listOf(LiteSource("CineLite", data, false)),
                mainUrl, subtitleCallback, callback
            )
        }
        val url = CineLiteParser.siteUrl(data) ?: return false
        val rendered = html(url)
        return CineLiteResolver(context).resolve(
            CineLiteParser.sources(rendered, url), url, subtitleCallback, callback
        )
    }
}
