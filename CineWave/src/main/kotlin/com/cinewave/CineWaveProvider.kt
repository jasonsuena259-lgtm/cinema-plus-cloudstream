package com.cinewave
import android.content.Context
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
class CineWaveProvider(private val context: Context? = null) : MainAPI() {
    override var mainUrl=CineWaveParser.BASE
    override var name="CineWave"
    override var lang="en"
    override val hasMainPage=true
    override val supportedTypes=setOf(TvType.Movie,TvType.TvSeries)
    override val mainPage=mainPageOf("movies" to "Movies", "tv" to "TV Series")
    private val repo=CineWaveRepository()
    private fun card(n:JsonNode,type:String):SearchResponse {
        val url=WaveId(type,n.path("id").asInt()).url()
        return if(type=="movie") newMovieSearchResponse(n.path("title").asText(),url){posterUrl=n.path("posterUrl").asText()}
        else newTvSeriesSearchResponse(n.path("title").asText(),url){posterUrl=n.path("posterUrl").asText()}
    }
    override suspend fun getMainPage(page:Int,request:MainPageRequest):HomePageResponse {
        val type=if(request.data=="tv") "tv" else "movie"
        val route=if(type=="tv") "tv" else "movies"
        val n=repo.json("/api/$route/all?page=$page")
        val items=CineWaveParser.items(n,if(type=="tv") "tvShows" else "movies")
        return newHomePageResponse(request.name,items.map{card(it,type)},hasNext=page<n.path("totalPages").asInt(page))
    }
    override suspend fun search(query:String):List<SearchResponse> {
        if(query.isBlank()) return emptyList()
        val out=mutableListOf<SearchResponse>();var succeeded=false
        for(type in listOf("movie","tv"))try{
            val n=repo.json("/api/${if(type=="movie") "movies" else "tv"}/search?q="+java.net.URLEncoder.encode(query,"UTF-8"))
            out+=CineWaveParser.items(n,"results").map{card(it,type)};succeeded=true
        }catch(e:CancellationException){throw e}catch(_:Exception){}
        if(!succeeded) throw ErrorLoadingException("CineWave: تعذر البحث")
        return out.distinctBy{it.url}
    }
    override suspend fun load(url:String):LoadResponse {
        val id=CineWaveParser.id(url) ?: throw ErrorLoadingException("CineWave: رابط غير صالح")
        val root=repo.json("/api/${if(id.type=="movie") "movies" else "tv"}/get?id=${id.id}")
        val n=CineWaveParser.items(root,if(id.type=="movie") "movies" else "tvShows").firstOrNull { it.path("id").asInt()==id.id }
            ?: throw ErrorLoadingException("CineWave: لا توجد تفاصيل للعمل")
        val title=n.path("title").asText(); val description=n.path("overview").asText()
        val poster=n.path("posterUrl").asText(); val genres=n.path("genres").map{it.path("name").asText()}
        val release=n.path(if(id.type=="movie") "releaseDate" else "firstAirDate").asText().take(4).toIntOrNull()
        if(id.type=="movie") return newMovieLoadResponse(title,id.url(),TvType.Movie,id.url()){
            posterUrl=poster;plot=description;year=release;tags=genres
        }
        val episodes=CineWaveParser.episodes(n).map { ep -> newEpisode(ep.url()){season=ep.season;episode=ep.episode;name="Episode ${ep.episode}"} }
        if(episodes.isEmpty()) throw ErrorLoadingException("CineWave: لا توجد حلقات في بيانات الموقع")
        return newTvSeriesLoadResponse(title,id.url(),TvType.TvSeries,episodes){posterUrl=poster;plot=description;year=release;tags=genres}
    }
    override suspend fun loadLinks(data:String,isCasting:Boolean,subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean {
        val id=CineWaveParser.id(data) ?: return false
        return CineWaveResolver(context ?: throw ErrorLoadingException("CineWave: Android context unavailable")).resolve(repo.sources(id),subtitleCallback,callback)
    }
}
