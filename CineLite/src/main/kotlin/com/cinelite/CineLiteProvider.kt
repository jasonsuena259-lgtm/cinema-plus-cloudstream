package com.cinelite

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Content must be present in CineLite's rendered DOM. No synthetic TMDB cards. */
class CineLiteProvider(private val context: Context) : MainAPI() {
    override var mainUrl = CineLiteParser.BASE
    override var name = "CineLite"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    // Main sections are from actual public navigation and observed homepage headings.
    override val mainPage = mainPageOf(
        "/" to "Home",
        "/anime" to "Anime",
        "/discover" to "Discover",
        "section:Top 10 on CineLite" to "Top 10",
        "section:Current & upcoming TV shows" to "Upcoming TV",
        "section:Trending This Week" to "Trending This Week",
        "section:Top Rated Masterpieces" to "Top Rated",
        "section:Sci-Fi & Future Universes" to "Sci-Fi",
        "section:High-Octane Action" to "Action",
        "section:Animated Worlds" to "Animation"
    )
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
        val section = request.data.takeIf { it.startsWith("section:") }
            ?.removePrefix("section:")
        val route = when {
            section != null -> "/"
            request.data in setOf("/", "/anime", "/discover") -> request.data
            else -> throw ErrorLoadingException("CineLite: unknown section")
        }
        val raw = html(mainUrl + route.removeSuffix("/"))
        val list = if (section != null) {
            CineLiteParser.sections(raw, mainUrl).firstOrNull {
                it.first.equals(section, ignoreCase = true)
            }?.second.orEmpty()
        } else {
            CineLiteParser.cards(raw, mainUrl)
        }.map { item ->
            // TV series surfaced in the real Anime category use the Anime TV type.
            if (route == "/anime" && item.type == "tv") item.copy(type = "anime") else item
        }
        if (list.isEmpty()) throw ErrorLoadingException("CineLite: no real cards found in ${request.name}")
        return newHomePageResponse(request.name, list.map(::card), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val term = query.trim()
        if (term.isEmpty()) return@coroutineScope emptyList()
        // CineLite's /search currently returns movie and TV catalogue entries,
        // while actual AniList-backed series are rendered separately on /anime.
        // Query both *real* site catalogues concurrently. Never synthesize cards.
        suspend fun cardsAt(route: String, searchTerm: String?): List<LiteCard> =
            try {
                browser.snapshot(mainUrl + route, searchTerm)
                    ?.let { CineLiteParser.cards(it, mainUrl + route) }.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { emptyList() }
        val regular = async { cardsAt("/search", term) }
        val anime = async {
            cardsAt("/anime", null).filter { item ->
                item.type == "anime" && item.title.contains(term, ignoreCase = true)
            }
        }
        val items = (anime.await() + regular.await())
            .distinctBy { it.url }
        if (items.isEmpty()) return@coroutineScope emptyList()
        items.map(::card)
    }

    override suspend fun load(url: String): LoadResponse {
        val identity = CineLiteParser.identity(url)
        if (identity != null) {
            if (identity.type == "movie") {
                val embed = CineLiteParser.embedMovie(identity.id)
                    ?: throw ErrorLoadingException("CineLite: invalid movie ID")
                return newMovieLoadResponse(identity.title, url, TvType.Movie, CineLiteParser.watchMovie(identity.id) ?: embed) {
                    posterUrl = identity.poster
                }
            }
            val episodes = if (identity.type == "anime") {
                browser.animeEpisodes(identity.id, identity.title)
            } else {
                browser.seriesEpisodes(identity.id, identity.title)
            }
            // CineLite search includes some not-yet-streamable catalogue entries.
            // Do not crash the details page or fabricate playable episode links
            // when the website has not rendered episodes for this series.
            if (episodes.isEmpty()) {
                return newTvSeriesLoadResponse(
                    identity.title, url,
                    if (identity.type == "anime") TvType.Anime else TvType.TvSeries,
                    emptyList()
                ) {
                    posterUrl = identity.poster
                    plot = "CineLite lists this title, but the website did not display " +
                        "a clickable episode for it. Unreleased entries are not playable."
                }
            }
            return newTvSeriesLoadResponse(
                identity.title, url,
                if (identity.type == "anime") TvType.Anime else TvType.TvSeries,
                CineLiteParser.orderedEpisodes(episodes).map { episode ->
                    val mediaUrl = if (identity.type == "anime") {
                        CineLiteParser.watchAnimeEpisode(identity.id, episode.episode ?: 0)
                    } else episode.url
                    newEpisode(mediaUrl ?: episode.url) {
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
        val directEmbed = data.toHttpUrlOrNull()?.takeIf {
            it.scheme == "https" && it.host == "embed.cinelite.xyz" &&
                CineLiteParser.isRecognizedEmbedPath(it.encodedPath)
        }?.toString()
        val url = CineLiteParser.siteUrl(data)
        val firstPartyEmbed = directEmbed ?:
            url?.let(CineLiteParser::embeddedPlayerForWatchUrl)
        // The React watch page only hosts this actual first-party embed. Fetching
        // the entire outer page first delays the player and may change cookies
        // that bind a short-lived signed manifest to an older browser session.
        // Both ordinary CloudStream and KodaStream call provider.loadLinks(data).
        if (firstPartyEmbed != null) {
            return CineLiteResolver(context).resolve(
                listOf(LiteSource("CineLite", firstPartyEmbed, false)),
                url ?: mainUrl, subtitleCallback, callback
            )
        }
        if (url == null) return false
        val rendered = html(url)
        val discovered = CineLiteParser.sources(rendered, url)
        // The verified CineLite watch pages embed first-party players; if the
        // WebView snapshot finishes before the iframe is inserted, use that
        // exact observed embed route, never a guessed media stream.
        val sourceList = if (discovered.isNotEmpty()) discovered else
            CineLiteParser.embeddedPlayerForWatchUrl(url)?.let {
                listOf(LiteSource("CineLite player", it, false))
            }.orEmpty()
        val resolved = CineLiteResolver(context).resolve(
            sourceList, url, subtitleCallback, callback
        )
        if (resolved) CineLiteParser.subtitleTracks(rendered, url).forEach { (language, track) ->
            subtitleCallback(SubtitleFile(language, track))
        }
        return resolved
    }
}
