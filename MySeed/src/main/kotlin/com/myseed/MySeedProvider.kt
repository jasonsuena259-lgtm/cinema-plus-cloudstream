package com.myseed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class MySeedProvider : MainAPI() {
    override var mainUrl = "https://myseed.baby"
    override var name = "MySeed"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "topvideos.php" to "الاكثر مشاهدة",
        "newvideos.php" to "الجديد",
        "category.php?cat=cimanow-arabic-tv-series" to "مسلسلات عربية",
        "category.php?cat=cimanow-turkish-series" to "مسلسلات تركية",
        "category.php?cat=cimanow-foreign-tv-series" to "مسلسلات اجنبية",
        "category.php?cat=cimanow-korean-dramas" to "مسلسلات كورية",
        "category.php?cat=cimanow-tv-programs" to "برامج تلفزيونية",
        "category.php?cat=cimanow-anime-and-cartoon-series" to "مسلسلات انمي وكرتون",
        "category.php?cat=cimanow-indian-tv-series" to "مسلسلات هندية",
        "category.php?cat=cimanow-arabic-movies" to "افلام عربية",
        "category.php?cat=cimanow-foreign-films" to "افلام اجنبية",
        "category.php?cat=cimanow-turkish-films" to "افلام تركية",
        "category.php?cat=cimanow-anime-and-cartoon-movies" to "افلام انمي وكرتوني",
        "category.php?cat=cimanow-indian-movies" to "افلام هندية",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val link = "$mainUrl/${request.data}" + (if (request.data.contains("?")) "&" else "?") + "page=$page"
        val document = app.get(link).document
        val home = document.select("ul#pm-grid li.col-xs-6").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("div.caption h3 a") ?: return null
        val title = titleElement.text()
        val href = titleElement.attr("href")
        val posterUrl = this.selectFirst("div.pm-video-thumb img")?.attr("src")

        return if (title.contains("مسلسل") || title.contains("برنامج")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/search.php?keywords=$query"
        val document = app.get(link).document
        return document.select("ul#pm-grid li.col-xs-6").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: ""
        val posterUrl = document.selectFirst("div.video-bibplayer-poster")?.attr("style")?.let {
            Regex("""url\((.*?)\)""").find(it)?.groupValues?.get(1)
        } ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = document.selectFirst("div[itemprop=description] p")?.text()?.trim()

        val isSeries = title.contains("مسلسل") || title.contains("برنامج") || document.selectFirst("dt:contains(الموسم)") != null

        if (isSeries) {
            val episodes = ArrayList<Episode>()
            val episodesList = document.select("ul.pm-ul-browse-videos li.col-xs-6")
            if (episodesList.isNotEmpty()) {
                episodesList.forEach {
                    val epTitle = it.selectFirst("div.caption h3 a")?.text() ?: ""
                    val epHref = it.selectFirst("div.caption h3 a")?.attr("href") ?: ""
                    val epPoster = it.selectFirst("div.pm-video-thumb img")?.attr("src")
                    val epNum = Regex("""الحلقة\s*(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                    val seasonNum = Regex("""الموسم\s*(\d+)""").find(document.selectFirst("dt:contains(الموسم) + dd")?.text() ?: "")?.groupValues?.get(1)?.toIntOrNull()

                    episodes.add(newEpisode(epHref) {
                        this.name = epTitle
                        this.season = seasonNum
                        this.episode = epNum
                        this.posterUrl = epPoster
                    })
                }
            } else {
                // It might be a single episode page, just add itself
                episodes.add(newEpisode(url) {
                    this.name = title
                    this.posterUrl = posterUrl
                })
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val vid = Regex("""vid=([^&]+)""").find(data)?.groupValues?.get(1) ?: return false
        val playUrl = "$mainUrl/play.php?vid=$vid"
        val document = app.get(playUrl).document

        var hasLinks = false
        document.select("ul.list_servers li").forEach {
            val embedData = it.attr("data-embed")
            val iframeSrc = Regex("""src='([^']+)'""").find(embedData)?.groupValues?.get(1)
                ?: Regex("""src="([^"]+)"""").find(embedData)?.groupValues?.get(1)

            if (iframeSrc != null) {
                hasLinks = true
                loadExtractor(iframeSrc, data, subtitleCallback, callback)
            }
        }

        return hasLinks
    }
}
