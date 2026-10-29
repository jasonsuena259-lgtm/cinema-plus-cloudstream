package com.cimanowvip

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

class CimaNowVIPProvider(private val session:VipSession):MainAPI(){
    override var mainUrl=VipParser.BASE
    override var name="CimaNow VIP | سيما ناو"
    override var lang="ar"
    override val hasMainPage=true
    override val supportedTypes=setOf(TvType.Movie,TvType.TvSeries)
    private val discovered=ConcurrentHashMap<String,String>()
    private val pageUrls=ConcurrentHashMap<String,String>()
    override val mainPage get()=mainPageOf("/home/" to "الرئيسية","menu:latest" to "الأحدث","menu:movies" to "الأفلام","menu:series" to "المسلسلات","menu:programs" to "البرامج",*discovered.entries.sortedBy{it.key}.map{it.value to it.key}.toTypedArray())
    private fun card(c:VipCard):SearchResponse = if(c.series)newTvSeriesSearchResponse(c.title,c.url,TvType.TvSeries){posterUrl=c.poster} else newMovieSearchResponse(c.title,c.url){posterUrl=c.poster}
    override suspend fun getMainPage(page:Int,request:MainPageRequest):HomePageResponse{
        if(page<1)return newHomePageResponse(request.name,emptyList(),false)
        val home=if(request.data.startsWith("menu:")||request.data=="/home/")session.home() else null
        home?.let{d->VipParser.sections(d).filter{it.url.contains("/category/")}.forEach{discovered[it.title]=it.url}}
        val start=if(request.data.startsWith("menu:"))VipParser.section(home!!,request.data.substringAfter(':'))
            ?: throw ErrorLoadingException("CimaNow VIP: لم يظهر رابط قسم ${request.name} في قائمة الموقع") else VipParser.site(request.data)
            ?: throw ErrorLoadingException("CimaNow VIP: رابط القسم غير صالح")
        var url=pageUrls["${request.data}:$page"] ?: start
        var doc=if(home!=null&&start==home.location()&&url==start)home else session.page(url)
        if(page>1&&pageUrls["${request.data}:$page"]==null){
            for(current in 1 until page){url=VipParser.next(doc,current) ?: return newHomePageResponse(request.name,emptyList(),false);doc=session.page(url);pageUrls["${request.data}:${current+1}"]=url}
        }
        val next=VipParser.next(doc,page);if(next!=null)pageUrls["${request.data}:${page+1}"]=next
        val groups=doc.select("section").mapNotNull{s->
            val title=s.selectFirst("h2,h3,h4,[aria-label=title]")?.text()?.takeIf{it.isNotBlank()} ?: return@mapNotNull null
            val items=VipParser.cards(org.jsoup.Jsoup.parse(s.outerHtml(),doc.location()));if(items.isEmpty())null else HomePageList(title,items.map(::card))
        }.distinctBy{it.name}
        if(groups.isNotEmpty())return newHomePageResponse(groups,next!=null)
        val items=VipParser.cards(doc);if(items.isEmpty())throw ErrorLoadingException("CimaNow VIP: لم تُقرأ بطاقات ${request.name} من الصفحة")
        return newHomePageResponse(request.name,items.map(::card),next!=null)
    }
    override suspend fun search(query:String):List<SearchResponse>{
        if(query.isBlank())return emptyList()
        // Use the site's own search form; do not assume the public site's endpoint matches VIP.
        val home=session.home();val form=home.select("form").firstOrNull{it.selectFirst("input[type=search],input[name=s]")!=null} ?: throw ErrorLoadingException("CimaNow VIP: لم يُقرأ نموذج البحث من الموقع")
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
            try{episodes+=VipParser.episodes(session.page(seasonUrl),sn)}catch(e:CancellationException){throw e}catch(_:Exception){}
        }
        val ep=episodes.distinctBy{it.url}.sortedWith(compareBy({it.season},{it.episode}))
        if(ep.isNotEmpty())return newTvSeriesLoadResponse(title,stable,TvType.TvSeries,ep.map{e->newEpisode(e.url){name=e.title;season=e.season;episode=e.episode}}){posterUrl=poster;this.plot=plot}
        if(VipParser.decode(stable).contains("مسلسل"))throw ErrorLoadingException("CimaNow VIP: لم تُقرأ قائمة الحلقات؛ يلزم فحص صفحة الحساب الفعلية")
        return newMovieLoadResponse(title,stable,TvType.Movie,stable){posterUrl=poster;this.plot=plot}
    }
    override suspend fun loadLinks(data:String,isCasting:Boolean,subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean{
        val item=VipParser.stable(data) ?: return false
        val doc=session.page(item)
        val watch=VipParser.watchLink(doc,item)
        var sources=emptyList<String>()
        if(watch!=null&&VipParser.site(watch)!=null)try{
            sources=VipParser.sources(session.page(watch,render=true,playback=true))
        }catch(e:CancellationException){throw e}catch(_:Exception){}
        if(sources.isNotEmpty())try{return VipResolver(session).resolve(sources,subtitleCallback,callback)}
            catch(e:CancellationException){throw e}catch(_:Exception){}
        // A details-page trailer is never a substitute for an authenticated player.
        val selected=session.capturePlayback(item)
        return VipResolver(session).resolve(selected.sources,subtitleCallback,callback,selected.media)
    }
}
