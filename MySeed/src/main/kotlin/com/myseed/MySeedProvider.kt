package com.myseed

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The new .cam website uses SEO slugs, /series/... seasons and
 * /watch/{slug}/servers buttons instead of the old .baby PHP pages.
 */
class MySeedProvider(private val context: Context? = null) : MainAPI() {
    override var mainUrl = MySeedCamParser.BASE
    override var name = "MySeed | ماي سيد"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val mainPage = mainPageOf(*MySeedCamParser.sections.toTypedArray())

    private val repo = MySeedRepository()

    private fun card(item: SeedItem): SearchResponse =
        if (item.series) {
            newTvSeriesSearchResponse(item.title, item.url, if (item.anime) TvType.Anime else TvType.TvSeries) {
                posterUrl = item.poster
            }
        } else {
            newMovieSearchResponse(item.title, item.url) { posterUrl = item.poster }
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = MySeedCamParser.pageUrl(request.data, page)
            ?: throw ErrorLoadingException("MySeed: مسار القسم غير صالح")
        val doc = repo.page(url)
        val entries = MySeedCamParser.cards(doc)
        return newHomePageResponse(request.name, entries.map(::card), hasNext = MySeedCamParser.next(doc, page))
    }

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1).items

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(), false)
        val url = (mainUrl + "/search").toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("q", query).apply {
                if (page > 1) addQueryParameter("page", page.toString())
            }.build().toString()
        val doc = repo.page(url)
        return newSearchResponseList(MySeedCamParser.cards(doc).map(::card), MySeedCamParser.next(doc, page))
    }

    override suspend fun load(url: String): LoadResponse {
        val safe = MySeedCamParser.siteUrl(url)
            ?: throw ErrorLoadingException("MySeed: رابط خارج نطاق الموقع")
        val doc = repo.page(safe)
        val title = doc.selectFirst(".tt-series-info h1, .myseed-single-hero h1, h1")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("MySeed: لم يُعثر على العنوان")
        val poster = MySeedCamParser.poster(doc)
        val plotText = MySeedCamParser.story(doc)
        val genres = MySeedCamParser.tags(doc)
        val yearValue = Regex("""(?:19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        val episodes = MySeedCamParser.episodes(doc)
        val seriesPage = safe.toHttpUrlOrNull()?.encodedPath?.startsWith("/series/") == true
        val isEpisode = title.contains("الحلقة") || title.contains("حلقة ")

        if (seriesPage || episodes.isNotEmpty() || isEpisode) {
            val epList = if (episodes.isNotEmpty()) episodes else {
                val epNo = MySeedCamParser.number(title.substringAfterLast("الحلقة", ""))
                    ?: Regex("""-n(\d+)(?:/|$)""").find(safe)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                listOf(SeedEpisode("الحلقة " + epNo, safe, 1, epNo))
            }
            val seriesName = if (seriesPage) title else title.substringBefore("الحلقة").trim()
            val isAnime = title.contains("انمي") || title.contains("أنمي")
            return newTvSeriesLoadResponse(
                seriesName.ifBlank { title }, safe,
                if (isAnime) TvType.Anime else TvType.TvSeries,
                epList.map { ep -> newEpisode(ep.url) {
                    season = ep.season
                    episode = ep.number
                    name = ep.title
                } }
            ) {
                posterUrl = poster
                this.plot = plotText
                tags = genres
                year = yearValue
            }
        }

        val content = if (MySeedCamParser.slug(safe) != null) safe
            else throw ErrorLoadingException("MySeed: الرابط ليس صفحة فيلم")
        return newMovieLoadResponse(title, content, TvType.Movie, content) {
            posterUrl = poster
            this.plot = plotText
            tags = genres
            year = yearValue
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watch = MySeedCamParser.watchUrl(data) ?: return false
        val watchSources = MySeedCamParser.watchSources(repo.page(watch))
        // This is a separate page with its own actual file-host URLs, never
        // invented from labels. Files are only offered if a resolver validates them.
        val downloadSources = MySeedCamParser.downloadUrl(data)?.let { download ->
            try { MySeedCamParser.downloadSources(repo.page(download)) } catch (_: Exception) { emptyList() }
        }.orEmpty()
        val sources = (watchSources + downloadSources).distinctBy { it.url }
        if (sources.isEmpty()) throw ErrorLoadingException("MySeed: لا توجد روابط مشاهدة أو تحميل في صفحة الموقع")
        return MySeedResolver(context).resolve(sources, watch, subtitleCallback, callback)
    }
}
