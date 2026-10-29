package com.dexter

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URI
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

    private suspend fun fetch(url:String):String?=try {
        app.get(url,timeout=14).takeIf{it.code in 200..299}?.text
    } catch(e:CancellationException) { throw e }
      catch(_:Exception) { null }

    private fun result(item:DexterCard):SearchResponse =
        if(item.series)newTvSeriesSearchResponse(item.title,item.url) {
            posterUrl=item.poster
        } else newMovieSearchResponse(item.title,item.url) { posterUrl=item.poster }

    private fun tmdbId(url:String):Int? =
        Regex("""^/(?:show|watch)/(\d+)-""")
            .find(runCatching { URI(url).path }.getOrDefault(""))
            ?.groupValues?.get(1)?.toIntOrNull()

    private fun validPoster(raw:String?):Boolean =
        raw!=null && DexterParser.externalUrl(raw,DexterCatalog.BASE)!=null &&
            !listOf("placeholder","no-poster","noimage","default-poster",
                "blank.","missing-poster").any { raw.contains(it,ignoreCase=true) }

    private suspend fun fallbackCards(page:Int,isSeries:Boolean):List<DexterCard> {
        val body=fetch(DexterCatalog.catalogUrl(isSeries,page))?:return emptyList()
        return DexterCatalog.entries(body,isSeries)
    }

    /**
     * Resolve a genuine IMDb ID from a title, then require the returned TMDB
     * ID to match the number in Dexter's own URL. Never attach artwork from
     * a same-named but different title.
     */
    private suspend fun matchedMeta(
        title:String,tmdb:Int,isSeries:Boolean
    ):Pair<String,String>? {
        val cleanTitle=title.replace(Regex("""\s*\(\d{4}\)\s*$"""),"")
        val body=fetch(DexterCatalog.searchUrl(cleanTitle,isSeries))?:return null
        for((imdb,_) in DexterCatalog.searchCandidates(body,title).take(2)) {
            val metaUrl=DexterCatalog.metaUrl(imdb,isSeries)?:continue
            val meta=fetch(metaUrl)?:continue
            if(DexterCatalog.metadataTmdbId(meta)==tmdb)return imdb to meta
        }
        return null
    }

    /**
     * Prefer actual Dexter catalogue entries. Use independent posters only
     * when they match the exact TMDB ID already linked by the site.
     * Search a bounded number of unknown cards; no fake image URLs.
     */
    private suspend fun decorateCards(cards:List<DexterCard>,page:Int):List<DexterCard> {
        if(cards.isEmpty())return cards
        val needs=cards.filter { !validPoster(it.poster) ||
            it.poster?.contains("dexter.pw",ignoreCase=true)==true }
        if(needs.isEmpty())return cards
        val both=coroutineScope {
            listOf(false,true).map { show ->
                async { fallbackCards(page,show) }
            }.awaitAll().flatten()
        }
        val popular=both.mapNotNull { card ->
            tmdbId(card.url)?.let { id -> (card.series to id) to card.poster }
        }.toMap()
        val known=cards.map { item ->
            val id=tmdbId(item.url)
            if(validPoster(item.poster) && 
                item.poster?.contains("dexter.pw",ignoreCase=true)!=true)item else {
                val replacement=id?.let { popular[item.series to it] }
                item.copy(poster=replacement?.takeIf(::validPoster)?:item.poster)
            }
        }
        val missing=known.filter { !validPoster(it.poster) ||
            it.poster?.contains("dexter.pw",ignoreCase=true)==true }.take(8)
        val resolved=mutableMapOf<String,String>()
        for(batch in missing.chunked(4)) {
            val fetched=coroutineScope {
                batch.map { card -> async {
                    val id=tmdbId(card.url)?:return@async null
                    val match=matchedMeta(card.title,id,card.series)?:return@async null
                    val poster=DexterCatalog.details(match.second,card.series,card.url)?.poster
                    if(validPoster(poster)) card.url to poster!! else null
                } }.awaitAll().filterNotNull()
            }
            fetched.forEach { (url,poster)->resolved[url]=poster }
        }
        return known.map { item ->
            val match=resolved[item.url]
            if(validPoster(match))item.copy(poster=match)
            else item
        }
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
        val fromDexter=html?.let { DexterParser.cards(it,url) }.orEmpty()
        if(fromDexter.isNotEmpty()) {
            val decorated=decorateCards(fromDexter,page)
            return newHomePageResponse(request.name,decorated.map(::result),
                hasNext=DexterParser.hasNext(DexterParser.document(html!!,url),page))
        }
        val fallback=fallbackCards(page,isSeries)
        return newHomePageResponse(request.name,fallback.map(::result),
            hasNext=fallback.size>=40)
    }

    override suspend fun search(query:String):List<SearchResponse> =search(query,1).items

    private suspend fun searchCatalog(query:String,isSeries:Boolean):List<DexterCard> {
        val raw=fetch(DexterCatalog.searchUrl(query,isSeries))?:return emptyList()
        val immediate=DexterCatalog.entries(raw,isSeries)
        if(immediate.isNotEmpty())return immediate
        return coroutineScope {
            DexterCatalog.searchImdbIds(raw).take(8).map { id -> async {
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
            if(matches.isNotEmpty()) {
                val decorated=decorateCards(matches,page)
                return newSearchResponseList(decorated.map(::result),
                    DexterParser.hasNext(DexterParser.document(body,url),page))
            }
        }
        if(page>1)return newSearchResponseList(emptyList(),false)
        val movies=searchCatalog(query,false)
        val shows=searchCatalog(query,true)
        return newSearchResponseList((movies+shows).distinctBy{it.url}.map(::result),false)
    }

    override suspend fun load(url:String):LoadResponse? {
        val safe=DexterParser.site(url)?:return null
        val series=DexterParser.isSeries(safe)
        if(!series && !DexterParser.isMovie(safe))return null

        val html=fetch(safe)
        val doc=html?.let { DexterParser.document(it,safe) }
        val siteTitle=doc?.let(DexterParser::title)
        val id=tmdbId(safe)
        val knownImdb=DexterCatalog.imdbFromUrl(safe)
        val metadata=if(knownImdb!=null) {
            DexterCatalog.metaUrl(knownImdb,series)?.let { fetch(it) }
        } else if(id!=null && siteTitle!=null) {
            matchedMeta(siteTitle,id,series)?.second
        } else null
        val alternate=metadata?.let { DexterCatalog.details(it,series,safe) }
        val title=siteTitle?:alternate?.name
            ?:throw ErrorLoadingException("Dexter: تعذر جلب عنوان العمل من الموقع والكتالوج")
        val pagePoster=doc?.let(DexterParser::poster)
        val poster=pagePoster?.takeIf(::validPoster)?:alternate?.poster
        val plot=doc?.let(DexterParser::plot)?:alternate?.plot

        if(!series)return newMovieLoadResponse(title,safe,TvType.Movie,safe) {
            posterUrl=poster;this.plot=plot
        }

        val explicit=mutableListOf<DexterEpisode>()
        val seasonStarts=mutableListOf<DexterSeasonIndex>()
        if(doc!=null) {
            explicit+=DexterParser.episodes(doc)
            seasonStarts+=DexterSeasons.starts(doc)
            for((seasonUrl,_) in DexterParser.seasonPages(doc).take(24)) {
                if(seasonUrl.substringBefore('#')==safe.substringBefore('#'))continue
                val body=fetch(seasonUrl)?:continue
                val page=DexterParser.document(body,seasonUrl)
                explicit+=DexterParser.episodes(page)
                seasonStarts+=DexterSeasons.starts(page)
            }
        }
        val episodes=DexterSeasons.merge(explicit,seasonStarts.distinctBy{it.season},
            alternate?.episodes.orEmpty())
        if(episodes.isEmpty())throw ErrorLoadingException(
            "Dexter: تعذر إيجاد حلقات هذا المسلسل في الموقع أو الفهرس الاحتياطي")
        val list=episodes.map { ep ->
            newEpisode(ep.url) {
                season=ep.season
                episode=ep.number
                name=ep.title?.takeIf { it.length>3 && !DexterSeasons.isSeasonLink(it) }
                    ?: "Episode "+ep.number
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
