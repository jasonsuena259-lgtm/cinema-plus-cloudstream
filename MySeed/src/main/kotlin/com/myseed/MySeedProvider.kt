package com.myseed
import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
class MySeedProvider(private val context:Context?=null):MainAPI(){
    override var mainUrl=MySeedParser.BASE
    override var name="MySeed | ماي سيد"
    override var lang="ar"
    override val hasMainPage=true
    override val supportedTypes=setOf(TvType.Movie,TvType.TvSeries,TvType.Anime)
    override val mainPage=mainPageOf(*MySeedParser.sections.toTypedArray())
    private val repo=MySeedRepository()
    private fun card(item:SeedItem):SearchResponse=if(item.series)newTvSeriesSearchResponse(item.title,item.url,if(item.anime)TvType.Anime else TvType.TvSeries){posterUrl=item.poster}
        else newMovieSearchResponse(item.title,item.url){posterUrl=item.poster}
    override suspend fun getMainPage(page:Int,request:MainPageRequest):HomePageResponse{
        val url=MySeedParser.siteUrl(request.data)?.toHttpUrlOrNull()?.newBuilder()?.apply{if(page>1)setQueryParameter("page",page.toString())}?.build()?.toString() ?: throw ErrorLoadingException("ماي سيد: قسم غير صالح")
        val doc=repo.page(url);val items=MySeedParser.cards(doc)
        if(page==1&&items.isEmpty()&&!doc.text().contains("لا توجد اى ملفات بهذا التصنيف"))throw ErrorLoadingException("ماي سيد: تعذر قراءة بطاقات ${request.name}")
        return newHomePageResponse(request.name,items.map(::card),hasNext=MySeedParser.next(doc,page)!=null)
    }
    override suspend fun search(query:String):List<SearchResponse> = search(query,1).items
    override suspend fun search(query:String,page:Int):SearchResponseList {
        if(query.isBlank())return newSearchResponseList(emptyList(),false)
        val url="$mainUrl/search.php".toHttpUrlOrNull()!!.newBuilder().addQueryParameter("keywords",query).addQueryParameter("page",page.toString()).build().toString()
        val doc=repo.page(url)
        return newSearchResponseList(MySeedParser.cards(doc).map(::card),MySeedParser.next(doc,page)!=null)
    }
    override suspend fun load(url:String):LoadResponse{
        val valid=MySeedParser.watchUrl(url) ?: throw ErrorLoadingException("ماي سيد: رابط عمل غير صالح")
        val doc=repo.page(valid)
        val title=doc.selectFirst("h1[itemprop=name],h1")?.text()?.trim()?.takeIf{it.isNotBlank()} ?: throw ErrorLoadingException("ماي سيد: تعذر قراءة عنوان العمل")
        val poster=doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot=doc.selectFirst(".pm-video-description [itemprop=description]")?.text()?.takeIf{it.isNotBlank()} ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
        val genres=doc.select(".pm-video-description dd a[href*=category.php]").map{it.text()}.distinct()
        val yearValue=Regex("(?:19|20)\\d{2}").find(title)?.value?.toIntOrNull()
        val episodes=MySeedParser.episodes(doc)
        if(episodes.isNotEmpty()){
            val seriesName=doc.select(".pm-video-description dt").firstOrNull{it.text().trim()=="المسلسل"}?.nextElementSibling()?.text()?.takeIf{it.isNotBlank()} ?: title.substringBefore("الحلقة").trim()
            return newTvSeriesLoadResponse(seriesName,valid,if(title.contains("انمي")||title.contains("كرتون"))TvType.Anime else TvType.TvSeries,
                episodes.map{ep->newEpisode(ep.url){season=ep.season;episode=ep.number;name=ep.title}}){posterUrl=poster;this.plot=plot;tags=genres;year=yearValue}
        }
        if(title.contains("الحلقة")||title.startsWith("مسلسل"))throw ErrorLoadingException("ماي سيد: لم تُقرأ قائمة مواسم وحلقات هذا المسلسل")
        return newMovieLoadResponse(title,valid,TvType.Movie,valid){posterUrl=poster;this.plot=plot;tags=genres;year=yearValue}
    }
    override suspend fun loadLinks(data:String,isCasting:Boolean,subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean{
        val play=MySeedParser.playUrl(data) ?: return false
        val sources=MySeedParser.sources(repo.page(play))
        return MySeedResolver(context).resolve(sources,play,subtitleCallback,callback)
    }
}
