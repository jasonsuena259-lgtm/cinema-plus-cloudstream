package com.dexter

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        "/tv" to "المسلسلات",
        "/catalog/movies" to "أفلام شائعة • كتالوج احتياطي",
        "/catalog/tv" to "مسلسلات شائعة • كتالوج احتياطي"
    )

    /** A blocked Dexter page is not a fatal error if public metadata is available. */
    private suspend fun fetch(url:String):String? =try {
        app.get(url,timeout=14).takeIf { it.code in 200..299 }?.text
    } catch(e:CancellationException) { throw e }
      catch(_:Exception) { null }

    private fun result(item:DexterCard):SearchResponse =
        if(item.series)newTvSeriesSearchResponse(item.title,item.url) {
            posterUrl=item.poster
        } else newMovieSearchResponse(item.title,item.url) { posterUrl=item.poster }

    private suspend fun fallbackCards(page:Int,isSeries:Boolean):List<DexterCard> {
        val body=fetch(DexterCatalog.catalogUrl(isSeries,page))?:return emptyList()
        return DexterCatalog.entries(body,isSeries)
    }

    override suspend fun getMainPage(page:Int,request:MainPageRequest):HomePageResponse {
        val part=request.data
        val isSeries=part=="/tv" || part=="/catalog/tv"
        val isCatalog=part.startsWith("/catalog/")
        if(isCatalog) {
            val cards=fallbackCards(page,isSeries)
            return newHomePageResponse(request.name,cards.map(::result),
                hasNext=cards.size>=40)
        }
        val suffix=if(page>1)(if('?' in part)"&" else "?")+"page="+page else ""
        val url=mainUrl+part+suffix
        val html=fetch(url)
        val cards=html?.let { DexterParser.cards(it,url) }.orEmpty()
        if(cards.isNotEmpty())return newHomePageResponse(request.name,cards.map(::result),
            hasNext=DexterParser.hasNext(DexterParser.document(html!!,url),page))

        // Site HTML is frequently Cloudflare-blocked, unlike the independent
        // public movie metadata catalog. These titles are discovery suggestions;
        // actual Dexter playback still depends on the per-title API.
        val alternate=fallbackCards(page,isSeries)
        return newHomePageResponse(request.name,alternate.map(::result),
            hasNext=alternate.size>=40)
    }

    override suspend fun search(query:String):List<SearchResponse> = search(query,1).items

    private suspend fun searchCatalog(query:String,isSeries:Boolean):List<DexterCard> {
        val raw=fetch(DexterCatalog.searchUrl(query,isSeries))?:return emptyList()
        val immediate=DexterCatalog.entries(raw,isSeries)
        if(immediate.isNotEmpty())return immediate
        val ids=DexterCatalog.searchImdbIds(raw).take(8)
        return coroutineScope {
            ids.map { id -> async {
                val url=DexterCatalog.metaUrl(id,isSeries)?:return@async null
                fetch(url)?.let { DexterCatalog.entryFromMeta(it,isSeries) }
            } }.awaitAll().filterNotNull()
        }
    }

    override suspend fun search(query:String,page:Int):SearchResponseList {
        if(query.isBlank())return newSearchResponseList(emptyList(),false)
        val keyword=URLEncoder.encode(query.trim(),"UTF-8")
        for(path in listOf("/search?q=","/search?query=")) {
            val url=mainUrl+path+keyword+"&page="+page
            val body=fetch(url)?:continue
            val matches=DexterParser.cards(body,url)
            if(matches.isNotEmpty())return newSearchResponseList(matches.map(::result),
                DexterParser.hasNext(DexterParser.document(body,url),page))
        }
        if(page>1)return newSearchResponseList(emptyList(),false)
        val movies=searchCatalog(query,false)
        val shows=searchCatalog(query,true)
        return newSearchResponseList((movies+shows).distinctBy { it.url }.map(::result),
            false)
    }

    override suspend fun load(url:String):LoadResponse? {
        val safe=DexterParser.site(url)?:return null
        val isSeries=DexterParser.isSeries(safe)
        if(!isSeries && !DexterParser.isMovie(safe))return null

        val html=fetch(safe)
        val doc=html?.let { DexterParser.document(it,safe) }
        val imdb=DexterCatalog.imdbFromUrl(safe)
        val metaBody=imdb?.let { id ->
            DexterCatalog.metaUrl(id,isSeries)?.let { fetch(it) }
        }
        val alternate=metaBody?.let { DexterCatalog.details(it,isSeries,safe) }
        val title=doc?.let(DexterParser::title)?:alternate?.name
            ?:throw ErrorLoadingException("Dexter: تعذر جلب بيانات العنوان من الموقع والكتالوج")
        val poster=doc?.let(DexterParser::poster)?:alternate?.poster
        val plot=doc?.let(DexterParser::plot)?:alternate?.plot

        if(!isSeries)
            return newMovieLoadResponse(title,safe,TvType.Movie,safe) {
                posterUrl=poster;this.plot=plot
            }

        val episodes=linkedMapOf<String,DexterEpisode>()
        if(doc!=null) {
            DexterParser.episodes(doc).forEach { episodes[it.url]=it }
            for((seasonUrl,_) in DexterParser.seasonPages(doc).take(30)) {
                if(seasonUrl.substringBefore('#')==safe.substringBefore('#'))continue
                val seasonHtml=fetch(seasonUrl)?:continue
                DexterParser.episodes(DexterParser.document(seasonHtml,seasonUrl))
                    .forEach { episodes[it.url]=it }
            }
        }
        if(episodes.isEmpty()) alternate?.episodes?.forEach { episodes[it.url]=it }
        if(episodes.isEmpty())throw ErrorLoadingException(
            "Dexter: تعذر إيجاد حلقات حقيقية في صفحة المسلسل أو بيانات مواسمه")
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
