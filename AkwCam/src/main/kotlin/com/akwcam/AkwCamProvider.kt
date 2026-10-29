package com.akwcam
import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.*
class AkwCamProvider(private val context:Context?=null):MainAPI(){
 override var mainUrl=AkwCamParser.BASE
 override var name="AkwCam | أكوام"
 override var lang="ar"
 override val hasMainPage=true
 override val supportedTypes=setOf(TvType.Movie,TvType.TvSeries)
 private val repo=AkwCamRepository()
 override val mainPage=mainPageOf("/all-content" to "أحدث الإضافات","/films" to "الأفلام","/seriestv" to "المسلسلات","/episodestv" to "أحدث الحلقات", *listOf("افلام-اجنبي","افلام-مدبلجة","افلام-هندي","انمي","برامج-تلفزيونية","مسلسلات-أجنبي","مسلسلات-أسيوي","مسلسلات-الأنميات","مسلسلات-تركي").map{"/category/$it" to it.replace('-',' ')}.toTypedArray())
 private fun card(c:CamCard):SearchResponse=if(c.series)newTvSeriesSearchResponse(c.title,c.url){posterUrl=c.poster}else newMovieSearchResponse(c.title,c.url){posterUrl=c.poster}
 override suspend fun getMainPage(page:Int,request:MainPageRequest):HomePageResponse{
  val d=repo.page(mainUrl+request.data+if(page>1)"/page/$page" else "")
  return newHomePageResponse(request.name,AkwCamParser.cards(d).map{card(it)},hasNext=AkwCamParser.next(d,page))
 }
 override suspend fun search(query:String):List<SearchResponse> = AkwCamParser.cards(repo.page("$mainUrl/search?q="+java.net.URLEncoder.encode(query,"UTF-8"))).map{card(it)}
 override suspend fun load(url:String):LoadResponse {
  var d=repo.page(url)
  if(d.baseUri().contains("/season/")){
   val first=AkwCamParser.cards(d).firstOrNull() ?: throw ErrorLoadingException("أكوام: الموسم بلا حلقات")
   d=repo.page(first.url)
  }
  val parent=d.selectFirst(".br-breadcrumb a[href*=/series/]")?.absUrl("href")
  if(parent!=null)d=repo.page(parent)
  val title=d.selectFirst("h1")?.text()?.takeIf{it.isNotBlank()} ?: throw ErrorLoadingException("أكوام: لم تظهر تفاصيل العمل")
  val poster=d.selectFirst(".br-series-cover img,.br-poster-frame img")?.absUrl("src") ?: d.selectFirst("meta[property=og:image]")?.attr("content")
  val description=d.selectFirst(".br-series-story")?.text() ?: d.selectFirst("meta[name=description]")?.attr("content")
  val eps=AkwCamParser.episodes(d)
  val year=d.selectFirst(".br-meta-grid a[href*=year]")?.text()?.toIntOrNull()
  val genres=d.select(".br-meta-grid a[href*=genre]").map{it.text()}
  if(eps.isNotEmpty())return newTvSeriesLoadResponse(title,d.baseUri(),TvType.TvSeries,eps.map{e->newEpisode(e.url){name=e.title;posterUrl=e.poster;season=e.season;episode=e.number}}){posterUrl=poster;plot=description;this.year=year;tags=genres}
  if(d.baseUri().contains("/series/"))throw ErrorLoadingException("أكوام: لم ينشر الموقع حلقات لهذا المسلسل")
  return newMovieLoadResponse(title,url,TvType.Movie,url){posterUrl=poster;plot=description;this.year=year;tags=genres}
 }
 override suspend fun loadLinks(data:String,isCasting:Boolean,subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean=coroutineScope{
  val d=repo.page(data)
  val pages=d.select(".br-single-actions a[href]").mapNotNull{AkwCamParser.site(it.attr("href"),d.baseUri())}.distinct()
  val sources=pages.map{u->async{try{AkwCamParser.sources(repo.page(u))}catch(e:CancellationException){throw e}catch(_:Exception){emptyList()}}}.awaitAll().flatten().distinctBy{it.url}
  AkwCamResolver(context).resolve(sources,data,subtitleCallback,callback)
 }
}
