package com.tuktuk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup

class TukTukProvider : MainAPI() {
    override var mainUrl = TukTukParser.BASE
    override var name = "TukTuk Cinema | توك توك سينما"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val mainPage = mainPageOf("/recent/" to "المضاف حديثًا", "/category/movies-2/" to "الأفلام", "/sercat/مسلسلات-اجنبي/" to "مسلسلات أجنبية", "/sercat/مسلسلات-تركي/" to "مسلسلات تركية", "/sercat/مسلسلات-أسيوي/" to "مسلسلات آسيوية", "/sercat/قائمة-الانمي/" to "أنمي")
    private suspend fun page(raw: String): org.jsoup.nodes.Document {
        val url = TukTukParser.safeUrl(raw) ?: throw ErrorLoadingException("توك توك: رابط صفحة غير صالح")
        val response = try { app.get(url, headers=TukTukResolver.headers, timeout=15) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { throw ErrorLoadingException("توك توك: تعذر الاتصال بالموقع (${e.javaClass.simpleName})") }
        if (response.code !in 200..299) throw ErrorLoadingException("توك توك: الموقع أعاد HTTP ${response.code}")
        return Jsoup.parse(response.text, response.url)
    }
    private fun result(item: TukItem): SearchResponse = if (item.series) newTvSeriesSearchResponse(item.title,item.url) { posterUrl=item.poster } else newMovieSearchResponse(item.title,item.url) { posterUrl=item.poster }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = mainUrl + request.data + if (page>1) "page/$page/" else ""
        val doc = page(url)
        val items = TukTukParser.cards(doc)
        if (page == 1 && items.isEmpty()) throw ErrorLoadingException("توك توك: وصلت الصفحة دون بطاقات قابلة للقراءة")
        return newHomePageResponse(request.name,items.map(::result),hasNext=doc.select("a.next.page-numbers").isNotEmpty())
    }
    override suspend fun search(query: String): List<SearchResponse> = search(query,1).items
    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return newSearchResponseList(emptyList(),false)
        val url = "$mainUrl/" + (if(page>1) "page/$page/" else "") + "?s=" + java.net.URLEncoder.encode(query,"UTF-8")
        val doc = page(url)
        return newSearchResponseList(TukTukParser.cards(doc).map(::result),doc.select("a.next.page-numbers").isNotEmpty())
    }
    override suspend fun load(url: String): LoadResponse {
        if (java.net.URI(url).host != "tuktukcinemas.com") throw ErrorLoadingException("توك توك: الرابط ليس من الموقع")
        val doc = page(url)
        val schema = TukTukParser.schema(doc)
        val title = doc.selectFirst(".post-title, h1")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: schema?.path("name")?.asText() ?: throw ErrorLoadingException("توك توك: لم يُعثر على عنوان العمل")
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst(".story")?.text()?.takeIf { it.isNotBlank() } ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
        val yearValue = Regex("(?:19|20)\\d{2}").find(schema?.path("dateCreated")?.asText()?.takeIf { it.isNotBlank() } ?: title)?.value?.toIntOrNull()
        val seasons = TukTukParser.seasons(doc)
        val series = url.contains("/series/") || schema?.path("@type")?.asText() in listOf("TVEpisode","TVSeries","TVSeason") || seasons.isNotEmpty()
        if (!series) return newMovieLoadResponse(title,url,TvType.Movie,url) { posterUrl=poster; this.plot=plot; year=yearValue }
        val eps = mutableListOf<TukEpisode>()
        val pages = seasons.ifEmpty { listOf(url to (Regex("(?i)-s(\\d+)-e").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1)) }
        val failures = mutableListOf<String>()
        for ((seasonUrl,n) in pages) {
            try {
                var current: String? = seasonUrl
                val visited=mutableSetOf<String>()
                while(current != null && visited.add(current)) {
                    val seasonDoc = if(current==url) doc else page(current)
                    eps += TukTukParser.episodes(seasonDoc,n)
                    current = seasonDoc.selectFirst("a.next.page-numbers")?.absUrl("href")?.takeIf { it.isNotBlank() }
                }
            } catch(e: CancellationException) { throw e } catch(e: Exception) { failures.add("الموسم $n") }
        }
        if(eps.isEmpty()) throw ErrorLoadingException("توك توك: تعذر استخراج حلقات المسلسل. " + failures.joinToString())
        val episodes=eps.distinctBy { it.url }.sortedWith(compareBy({it.season},{it.number})).map { ep -> newEpisode(ep.url) { season=ep.season; episode=ep.number; name=ep.title; posterUrl=ep.poster } }
        val seriesTitle=schema?.path("partOfSeries")?.path("name")?.asText()?.takeIf { it.isNotBlank() } ?: title
        return newTvSeriesLoadResponse(seriesTitle,url,TvType.TvSeries,episodes) { posterUrl=poster; this.plot=plot; year=yearValue }
    }
    override suspend fun loadLinks(data: String,isCasting: Boolean,subtitleCallback: (SubtitleFile)->Unit,callback: (ExtractorLink)->Unit): Boolean = TukTukResolver().resolve(data,subtitleCallback,callback)
}
