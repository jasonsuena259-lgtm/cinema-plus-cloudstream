package com.cimanow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document

internal class CimaNowProvider(private val transport: CimaNowTransport) : MainAPI() {
    override var mainUrl = CimaNowSettings.DEFAULT_BASE
    override var name = "CimaNow | سيما ناو (العادي)"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    @Volatile private var sections: List<CimaSection> = emptyList()
    override val mainPage get() = mainPageOf("/home" to "الرئيسية",
        *sections.map { it.url to it.title }.toTypedArray())

    private fun card(c: CimaCard): SearchResponse =
        if (c.series) newTvSeriesSearchResponse(c.title, c.url, TvType.TvSeries) {
            posterUrl = c.poster
        } else newMovieSearchResponse(c.title, c.url) {
            posterUrl = c.poster
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page < 1) return newHomePageResponse(request.name, emptyList(), false)
        val raw = request.data.ifBlank { "/home" }
        val first = if (page == 1 || raw == "/home") raw
            else raw.toHttpUrlOrNull()?.newBuilder()?.addPathSegment("page")
                ?.addPathSegment(page.toString())?.build()?.toString() ?: raw
        val doc = transport.page(first)
        if (raw == "/home" && page == 1) sections = CimaNowParser.sections(doc)
        val groups = if (raw == "/home" && page == 1) CimaNowParser.groups(doc) else emptyList()
        if (groups.isNotEmpty()) return newHomePageResponse(
            groups.map { HomePageList(it.first, it.second.map(::card)) }, false
        )
        val items = CimaNowParser.cards(doc)
        if (items.isEmpty() && page == 1)
            throw ErrorLoadingException("CimaNow: لم تُقرأ بطاقات الصفحة؛ ربما تغيّر تصميم الموقع")
        val hasNext = doc.selectFirst("a[rel=next],.pagination a.next") != null
        return newHomePageResponse(request.name, items.map(::card), hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val base = transport.base()
        val url = base.toHttpUrlOrNull()!!.newBuilder()
            .addPathSegment("page").addPathSegment("1")
            .addQueryParameter("s", query).build().toString()
        return CimaNowParser.cards(transport.page(url)).map(::card)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = transport.page(url)
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.title().substringBefore(" | ").trim().takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("CimaNow: تعذر قراءة اسم العمل")
        val image = doc.selectFirst("meta[property=og:image]")?.absUrl("content")
            ?.takeIf { it.startsWith("https://") }
        val plot = doc.selectFirst("ul#details li:contains(لمحة) p,article .description,[itemprop=description]")
            ?.text()?.takeIf { it.isNotBlank() }
        val year = Regex("(?:19|20)[0-9]{2}").find(doc.select("article ul").firstOrNull()?.text().orEmpty())
            ?.value?.toIntOrNull()
        val genres = doc.select("article ul").firstOrNull()?.select("li a")?.map { it.text() }
            ?.filter { it.isNotBlank() }?.distinct() ?: emptyList()
        val allEpisodes = CimaNowParser.episodes(doc).toMutableList()
        for ((season, seasonUrl) in CimaNowParser.otherSeasons(doc).take(10)) {
            if (seasonUrl == doc.location()) continue
            try {
                allEpisodes.addAll(CimaNowParser.episodes(transport.page(seasonUrl), season))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { /* one missing season must not hide the others */ }
        }
        val episodes = allEpisodes.distinctBy { it.url }
            .sortedWith(compareBy({ it.season }, { it.number }))
        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries,
                episodes.map { ep -> newEpisode(ep.url) {
                    name = ep.title
                    season = ep.season
                    episode = ep.number
                } }
            ) {
                posterUrl = image
                this.plot = plot
                this.year = year
                tags = genres
            }
        }
        val isSeries = url.contains("مسلسل") || url.contains("موسم") || title.contains("مسلسل")
        if (isSeries)
            throw ErrorLoadingException("CimaNow: حلقات المسلسل غير ظاهرة في صفحة المصدر، ولن نعرض فيلماً بدلاً منها")
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = image
            this.plot = plot
            this.year = year
            tags = genres
        }
    }

    private fun watch(doc: Document): String {
        return doc.selectFirst("a[href*=watching]")?.absUrl("href")
            ?.takeIf { it.startsWith("https://") }
            ?: doc.location().trimEnd('/') + "/watching"
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val item = transport.page(data)
        val watchUrl = watch(item)
        val watchDoc = transport.page(watchUrl)
        val sourceLinks = (CimaNowParser.sources(watchDoc) +
            CimaNowParser.sources(item)).distinct().take(24)
        if (sourceLinks.isEmpty())
            throw ErrorLoadingException("CimaNow: صفحة المشاهدة لا تعرض سيرفرات؛ تحقق من الموقع أو Cloudflare")
        val sent = mutableSetOf<String>()
        val sentSubs = mutableSetOf<String>()
        val mutex = Mutex()
        // Limit parallel attempts: a slow or blocked video host must not stall all others.
        for (batch in sourceLinks.chunked(5)) coroutineScope {
            batch.forEach { source ->
                launch {
                    withTimeoutOrNull(11000L) {
                        try {
                            val u = source.toHttpUrlOrNull() ?: return@withTimeoutOrNull
                            val path = u.encodedPath.lowercase()
                            val direct = path.endsWith(".mp4") || path.endsWith(".m3u8")
                            if (direct) {
                                val link = newExtractorLink(name, u.host, source,
                                    if (path.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) { referer = watchDoc.location() }
                                mutex.withLock { synchronized(sent) { if (sent.add(link.url)) callback(link) } }
                            } else {
                                loadExtractor(source, watchDoc.location(), { sub ->
                                    synchronized(sentSubs) { if (sentSubs.add(sub.url)) subtitleCallback(sub) }
                                }, { link ->
                                    if (sent.add(link.url)) callback(link)
                                })
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { /* other servers continue */ }
                    }
                }
            }
        }
        if (sent.isEmpty())
            throw ErrorLoadingException("CimaNow: عُثر على روابط سيرفرات، لكن لم يُستخرج منها فيديو قابل للتشغيل")
        return true
    }
}
