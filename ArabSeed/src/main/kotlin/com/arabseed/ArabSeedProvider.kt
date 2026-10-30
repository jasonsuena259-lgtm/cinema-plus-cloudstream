package com.arabseed

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ArabSeedProvider(private val context:Context?=null):MainAPI() {
    override var mainUrl = ArabSeedParser.BASE
    override var name = "ArabSeed | عرب سيد"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie,TvType.TvSeries,TvType.Anime)
    override val mainPage = mainPageOf(*ArabSeedParser.sections.toTypedArray())

    private val repository = ArabSeedRepository()
    private fun result(item:ArabSeedItem):SearchResponse =
        if(item.series)newTvSeriesSearchResponse(item.title,item.url,if(item.anime)TvType.Anime else TvType.TvSeries){
            posterUrl=item.poster
        } else newMovieSearchResponse(item.title,item.url){posterUrl=item.poster}

    override suspend fun getMainPage(page:Int,request:MainPageRequest):HomePageResponse {
        val url=ArabSeedParser.pageUrl(request.data,page)
            ?: throw ErrorLoadingException("ArabSeed: القسم غير صالح")
        val document=repository.page(url)
        return newHomePageResponse(request.name,ArabSeedParser.cards(document).map(::result),hasNext=ArabSeedParser.next(document,page))
    }

    override suspend fun search(query:String):List<SearchResponse> = search(query,1).items

    override suspend fun search(query:String,page:Int):SearchResponseList {
        if(query.isBlank())return newSearchResponseList(emptyList(),false)
        val url=mainUrl.toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("s",query).apply{
                if(page>1)addQueryParameter("paged",page.toString())
            }.build().toString()
        val document=repository.page(url)
        return newSearchResponseList(ArabSeedParser.cards(document).map(::result),ArabSeedParser.next(document,page))
    }

    override suspend fun load(url:String):LoadResponse {
        val safe=ArabSeedParser.siteUrl(url)
            ?: throw ErrorLoadingException("ArabSeed: رابط خارج نطاق الموقع")
        if(!ArabSeedParser.isContent(safe))throw ErrorLoadingException("ArabSeed: ليست صفحة فيلم أو مسلسل")
        val document=repository.page(safe)
        val title=document.selectFirst("h1.post__name, .title__single h1, h1")?.text()?.trim()
            ?.takeIf{it.isNotBlank()}
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: throw ErrorLoadingException("ArabSeed: لم يُعثر على اسم المحتوى")
        val picture=ArabSeedParser.poster(document)
        val story=ArabSeedParser.plot(document)
        val anime=title.contains("انمي")||title.contains("أنمي")
        val yearNumber=Regex("""(?:19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        val isSeries=safe.toHttpUrlOrNull()?.encodedPath?.startsWith("/series/")==true ||
            title.contains("الحلقة") || title.startsWith("مسلسل") || anime

        if(!isSeries) return newMovieLoadResponse(title,safe,TvType.Movie,safe) {
            posterUrl=picture
            plot=story
            year=yearNumber
        }

        val seasonLinks=ArabSeedParser.seasonLinks(document)
        val episodes=if(seasonLinks.isEmpty()) {
            ArabSeedParser.episodes(document,ArabSeedParser.seasonNumber(title))
        } else {
            val gate=Semaphore(3)
            coroutineScope {
                seasonLinks.take(16).map{(seasonNumber,seasonUrl)->
                    async { gate.withPermit {
                        val page=if(seasonUrl==safe)document
                            else try{repository.page(seasonUrl)}catch(_:Exception){null}
                        page?.let{ArabSeedParser.episodes(it,seasonNumber)}.orEmpty()
                    }}
                }.awaitAll().flatten()
            }.distinctBy{it.url}.sortedWith(compareBy<ArabSeedEpisode>{it.season}.thenBy{it.number})
        }
        val ready=if(episodes.isNotEmpty())episodes else {
            val ep=ArabSeedParser.number(title.substringAfterLast("الحلقة",""))
            if(ep!=null && ArabSeedParser.episodeBase(safe)!=null)
                listOf(ArabSeedEpisode("الحلقة "+ep,safe,ArabSeedParser.seasonNumber(title),ep))
            else emptyList()
        }
        if(ready.isEmpty())throw ErrorLoadingException("ArabSeed: لم تُعثر حلقات لهذا المسلسل")
        val displayTitle=title.substringBefore("الموسم").substringBefore("الحلقة").trim().ifBlank{title}
        return newTvSeriesLoadResponse(displayTitle,safe,if(anime)TvType.Anime else TvType.TvSeries,
            ready.map{ep->newEpisode(ep.url) {
                season=ep.season
                episode=ep.number
                name=ep.title
            }}
        ){
            posterUrl=picture
            plot=story
            year=yearNumber
        }
    }

    override suspend fun loadLinks(data:String,isCasting:Boolean,
        subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean {
        val watch=ArabSeedParser.watchUrl(data)
            ?: throw ErrorLoadingException("ArabSeed: رابط المشاهدة غير صالح")
        val watchList=ArabSeedParser.watchSources(repository.page(watch))
        val downloadList=ArabSeedParser.downloadUrl(data)?.let{u->
            try{ArabSeedParser.downloadSources(repository.page(u))}catch(_:Exception){emptyList()}
        }.orEmpty()
        val sources=(watchList+downloadList).distinctBy{it.url}
        if(sources.isEmpty())throw ErrorLoadingException("ArabSeed: لم نجد روابط سيرفرات حقيقية")
        return ArabSeedResolver(context).resolve(sources,watch,subtitleCallback,callback)
    }
}
