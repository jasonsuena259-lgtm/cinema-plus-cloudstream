package com.cimanowvip

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document

class CimaNowVIPProvider(private val session:VipSession):MainAPI(){
    override var mainUrl=VipParser.BASE
    override var name="CimaNow VIP | سيما ناو"
    override var lang="ar"
    override val hasMainPage=true
    override val supportedTypes=setOf(TvType.Movie,TvType.TvSeries)
    override val mainPage=mainPageOf("/home/" to "الرئيسية")
    private fun card(c:VipCard):SearchResponse = if(c.series)newTvSeriesSearchResponse(c.title,c.url,TvType.TvSeries){posterUrl=c.poster} else newMovieSearchResponse(c.title,c.url){posterUrl=c.poster}
    override suspend fun getMainPage(page:Int,request:MainPageRequest):HomePageResponse{
        if(page>1)return newHomePageResponse(request.name,emptyList(),false)
        val doc=session.page(mainUrl+"/home/")
        val groups=doc.select("section").mapNotNull{s->
            val title=s.selectFirst("h2,h3")?.text()?.takeIf{it.isNotBlank()} ?: return@mapNotNull null
            val items=VipParser.cards(org.jsoup.Jsoup.parse(s.outerHtml(),doc.location()));if(items.isEmpty())null else HomePageList(title,items.map(::card))
        }.distinctBy{it.name}
        if(groups.isNotEmpty())return newHomePageResponse(groups,false)
        val items=VipParser.cards(doc);if(items.isEmpty())throw ErrorLoadingException("CimaNow VIP: الصفحة لم تعرض بطاقات قابلة للقراءة؛ افتح إعدادات الإضافة للتأكد من الدخول")
        return newHomePageResponse("الرئيسية",items.map(::card),false)
    }
    override suspend fun search(query:String):List<SearchResponse>{
        if(query.isBlank())return emptyList()
        // Use the site's own search form; do not assume the public site's endpoint matches VIP.
        val home=session.page(mainUrl+"/home/");val form=home.select("form").firstOrNull{it.selectFirst("input[type=search],input[name=s]")!=null} ?: throw ErrorLoadingException("CimaNow VIP: لم يُقرأ نموذج البحث من الموقع")
        if(form.attr("method").equals("post",true))throw ErrorLoadingException("CimaNow VIP: نموذج البحث الحالي يحتاج توافقًا إضافيًا")
        val field=form.selectFirst("input[type=search],input[name=s]")!!;val key=field.attr("name").takeIf{it.isNotBlank()} ?: return emptyList()
        val target=VipParser.site(form.absUrl("action").ifBlank{home.location()})?.toHttpUrlOrNull()?.newBuilder()?.setQueryParameter(key,query)?.build()?.toString() ?: return emptyList()
        return VipParser.cards(session.page(target)).map(::card)
    }
    override suspend fun load(url:String):LoadResponse{
        val stable=VipParser.stable(url) ?: throw ErrorLoadingException("CimaNow VIP: رابط غير صالح")
        val doc=session.page(stable);val title=doc.selectFirst("h1")?.text()?.takeIf{it.isNotBlank()} ?: doc.selectFirst("meta[property=og:title]")?.attr("content") ?: throw ErrorLoadingException("CimaNow VIP: لم يُقرأ عنوان العمل")
        val poster=doc.selectFirst("meta[property=og:image]")?.attr("content") ?: VipParser.poster(doc)
        val plot=doc.selectFirst("[aria-label=story]")?.text()
        val episodes=VipParser.episodes(doc).toMutableList()
        val seasons=doc.select("section[aria-label=seasons] a[href],#seasons a[href],ul[aria-label=seasons] a[href]")
        for(a in seasons){
            val seasonUrl=VipParser.site(a.absUrl("href")) ?: continue
            if(seasonUrl==stable)continue
            val sn=Regex("[0-9]+").find(a.text())?.value?.toIntOrNull() ?: continue
            episodes+=VipParser.episodes(session.page(seasonUrl),sn)
        }
        val ep=episodes.distinctBy{it.url}.sortedWith(compareBy({it.season},{it.episode}))
        if(ep.isNotEmpty())return newTvSeriesLoadResponse(title,stable,TvType.TvSeries,ep.map{e->newEpisode(e.url){name=e.title;season=e.season;episode=e.episode}}){posterUrl=poster;this.plot=plot}
        if(VipParser.decode(stable).contains("مسلسل"))throw ErrorLoadingException("CimaNow VIP: لم تُقرأ قائمة الحلقات؛ يلزم فحص صفحة الحساب الفعلية")
        return newMovieLoadResponse(title,stable,TvType.Movie,stable){posterUrl=poster;this.plot=plot}
    }
    override suspend fun loadLinks(data:String,isCasting:Boolean,subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean{
        var doc=session.page(VipParser.stable(data) ?: return false)
        val watch=doc.select("a[href]").mapNotNull{VipParser.site(it.absUrl("href"))}.firstOrNull{it.contains("/watching/")}
        if(watch!=null)doc=session.page(watch,render=true)
        val sources=VipParser.sources(doc)
        if(sources.isEmpty())throw ErrorLoadingException("CimaNow VIP: لم تظهر روابط السيرفرات في الصفحة؛ افتح إعدادات الإضافة وأكمل الدخول/تحقق الموقع")
        return VipResolver(session).resolve(sources,subtitleCallback,callback)
    }
}
