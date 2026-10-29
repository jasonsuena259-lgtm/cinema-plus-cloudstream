package com.dexter

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder

class DexterProvider:MainAPI() {
    override var mainUrl=DexterParser.BASE
    override var name="Dexter | ديكستر"
    override var lang="en"
    override val hasMainPage=true
    override val supportedTypes=setOf(TvType.Movie,TvType.TvSeries,TvType.Anime)
    override val mainPage=mainPageOf(
        "/" to "الرئيسية",
        "/movies" to "الأفلام",
        "/tv" to "المسلسلات"
    )

    private suspend fun fetch(url:String):String? {
        try {
            val result=app.get(url,timeout=16)
            if(result.code==403)throw ErrorLoadingException(
                "Dexter: الموقع أعاد HTTP 403 ويتطلب تحققاً من المتصفح")
            return result.takeIf { it.code in 200..299 }?.text
        } catch(e:CancellationException) { throw e }
        catch(e:ErrorLoadingException) { throw e }
        catch(_:Exception) { return null }
    }

    private fun result(item:DexterCard):SearchResponse =
        if(item.series)newTvSeriesSearchResponse(item.title,item.url) {
            posterUrl=item.poster
        } else newMovieSearchResponse(item.title,item.url) { posterUrl=item.poster }

    override suspend fun getMainPage(page:Int,request:MainPageRequest):HomePageResponse {
        val path=request.data
        val suffix=if(page>1) (if('?' in path)"&" else "?")+"page="+page else ""
        val url=mainUrl+path+suffix
        val html=fetch(url)?:return newHomePageResponse(request.name,emptyList(),hasNext=false)
        val doc=DexterParser.document(html,url)
        return newHomePageResponse(request.name,DexterParser.cards(html,url).map(::result),
            hasNext=DexterParser.hasNext(doc,page))
    }

    override suspend fun search(query:String):List<SearchResponse> = search(query,1).items

    override suspend fun search(query:String,page:Int):SearchResponseList {
        if(query.isBlank())return newSearchResponseList(emptyList(),false)
        val keyword=URLEncoder.encode(query.trim(),"UTF-8")
        // Search form is not independently validated because CI received HTTP 403.
        // Only publish results with real /watch/id or /show/id hyperlinks.
        for(path in listOf("/search?q=","/search?query=")) {
            val url=mainUrl+path+keyword+"&page="+page
            val html=fetch(url)?:continue
            val items=DexterParser.cards(html,url).map(::result)
            if(items.isNotEmpty())
                return newSearchResponseList(items,
                    DexterParser.hasNext(DexterParser.document(html,url),page))
        }
        return newSearchResponseList(emptyList(),false)
    }

    override suspend fun load(url:String):LoadResponse? {
        val safe=DexterParser.site(url)?:return null
        if(!DexterParser.isSeries(safe) && !DexterParser.isMovie(safe))return null
        val html=fetch(safe)?:throw ErrorLoadingException("Dexter: تعذر فتح صفحة المحتوى")
        val doc=DexterParser.document(html,safe)
        val title=DexterParser.title(doc)
            ?:throw ErrorLoadingException("Dexter: لم يظهر عنوان حقيقي للصفحة")
        val poster=DexterParser.poster(doc)
        val plot=DexterParser.plot(doc)
        if(DexterParser.isMovie(safe))
            return newMovieLoadResponse(title,safe,TvType.Movie,safe) {
                posterUrl=poster;this.plot=plot
            }

        val episodes=linkedMapOf<String,DexterEpisode>()
        DexterParser.episodes(doc).forEach { episodes[it.url]=it }
        for((seasonUrl,_) in DexterParser.seasonPages(doc).take(40)) {
            if(seasonUrl.substringBefore('#')==safe.substringBefore('#'))continue
            val seasonHtml=fetch(seasonUrl)?:continue
            DexterParser.episodes(DexterParser.document(seasonHtml,seasonUrl))
                .forEach { episodes[it.url]=it }
        }
        if(episodes.isEmpty())throw ErrorLoadingException(
            "Dexter: لم تظهر روابط حلقات فعلية ضمن صفحة المسلسل أو المواسم")
        val list=episodes.values.sortedWith(
            compareBy<DexterEpisode>{it.season}.thenBy{it.number}
        ).map { ep ->
            newEpisode(ep.url) {
                season=ep.season
                episode=ep.number
                name=ep.title?.takeIf { it.length>3 } ?: "Episode "+ep.number
            }
        }
        return newTvSeriesLoadResponse(title,safe,TvType.TvSeries,list) {
            posterUrl=poster;this.plot=plot
        }
    }

    override suspend fun loadLinks(
        data:String,isCasting:Boolean,
        subtitleCallback:(SubtitleFile)->Unit,
        callback:(ExtractorLink)->Unit
    ):Boolean=DexterResolver(mainUrl).resolve(data,subtitleCallback,callback)
}
