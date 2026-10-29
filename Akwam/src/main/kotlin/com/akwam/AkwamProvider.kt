package com.akwam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CancellationException
import org.jsoup.nodes.Document
import java.net.URLEncoder

class AkwamProvider:MainAPI(){
    override var mainUrl=AkwamParser.BASE
    override var name="Akwam | أكوام"
    override var lang="ar"
    override val hasMainPage=true
    override val supportedTypes=setOf(TvType.Movie,TvType.TvSeries,TvType.Anime)
    override val mainPage=mainPageOf(
        "/one" to "الرئيسية",
        "/recent" to "أضيف حديثًا",
        "/movies" to "الأفلام",
        "/series" to "المسلسلات",
        "/shows" to "التلفزيون",
        "/movies?section=29" to "أفلام عربية",
        "/series?section=30" to "مسلسلات أجنبية"
    )
    private suspend fun fetch(url:String):String?=try {
        app.get(url,timeout=18).takeIf{it.code in 200..299}?.text
    }catch(e:CancellationException){throw e}
    catch(_:Exception){null}

    private fun result(item:AkwamItem):SearchResponse=
        if(item.series)newTvSeriesSearchResponse(item.title,item.url){
            posterUrl=item.poster
        } else newMovieSearchResponse(item.title,item.url){posterUrl=item.poster}

    override suspend fun getMainPage(page:Int,request:MainPageRequest):HomePageResponse{
        val part=request.data
        if(page>1 && (part=="/one"||part=="/recent"))
            return newHomePageResponse(request.name,emptyList(),hasNext=false)
        val url=mainUrl+part+(if(page>1)(if('?' in part)"&" else "?")+"page="+page else "")
        val html=fetch(url)?:return newHomePageResponse(request.name,emptyList(),hasNext=false)
        val doc=AkwamParser.document(html,url)
        return newHomePageResponse(request.name,AkwamParser.cards(html,url).map(::result),
            hasNext=AkwamParser.hasNext(doc,page))
    }
    override suspend fun search(query:String):List<SearchResponse> =
        search(query,1).items
    override suspend fun search(query:String,page:Int):SearchResponseList{
        if(query.isBlank())return newSearchResponseList(emptyList(),false)
        val encoded=URLEncoder.encode(query.trim(),"UTF-8")
        // Verified live Akwam.ss search form uses ?q; ?s returned unrelated cards.
        for(endpoint in listOf("/search?q=")){
            val url=mainUrl+endpoint+encoded+"&page="+page
            val html=fetch(url)?:continue
            val items=AkwamParser.cards(html,url).map(::result)
            if(items.isNotEmpty()){
                return newSearchResponseList(items,
                    AkwamParser.hasNext(AkwamParser.document(html,url),page))
            }
        }
        return newSearchResponseList(emptyList(),false)
    }
    override suspend fun load(url:String):LoadResponse?{
        val valid=AkwamParser.site(url)?:return null
        val html=fetch(valid) ?: throw ErrorLoadingException("أكوام: تعذر تحميل صفحة التفاصيل")
        val doc=AkwamParser.document(html,valid)
        val title=AkwamParser.title(doc)?:throw ErrorLoadingException("أكوام: لم يظهر عنوان المحتوى")
        val poster=AkwamParser.poster(doc)
        val plot=AkwamParser.plot(doc)
        val entries=AkwamParser.qualities(html,valid)
        val tags=doc.select("a[href*='section='],a[href*='/genre/']")
            .map{it.text()}.filter{it.isNotBlank()}.distinct()
        if(valid.contains("/movie/")||valid.contains("/shows/")){
            return newMovieLoadResponse(title,valid,TvType.Movie,valid){
                posterUrl=poster;this.plot=plot;this.tags=tags
            }
        }
        val groups=AkwamParser.seasons(doc)
        val all=mutableListOf<AkwamEpisode>()
        val baseSeason = when {
            Regex("""(?:الموسم|season)\s*(?:الرابع|fourth|4)""",RegexOption.IGNORE_CASE)
                .containsMatchIn(title) -> 4
            Regex("""(?:الموسم|season)\s*(?:الثالث|third|3)""",RegexOption.IGNORE_CASE)
                .containsMatchIn(title) -> 3
            Regex("""(?:الموسم|season)\s*(?:الثاني|second|2)""",RegexOption.IGNORE_CASE)
                .containsMatchIn(title) -> 2
            else -> 1
        }
        if(groups.isEmpty())all+=AkwamParser.episodes(doc,baseSeason)
        else for((link,number) in groups){
            val seasonHtml=fetch(link)?:continue
            all+=AkwamParser.episodes(AkwamParser.document(seasonHtml,link),number)
        }
        // Some TV entries link directly to the latest episode in the banner.
        // Never fabricate episode URLs or classify failed loading as "coming soon".
        if(all.isEmpty())throw ErrorLoadingException(
            "أكوام: صفحة المسلسل لم تعرض روابط حلقات قابلة للتحقق")
        val episodes=all.distinctBy{it.url}
            .sortedWith(compareBy<AkwamEpisode>{it.season}.thenBy{it.number})
            .map { ep->newEpisode(ep.url){
                season=ep.season;episode=ep.number;name="الحلقة "+ep.number
            } }
        return newTvSeriesLoadResponse(title,valid,TvType.TvSeries,episodes){
            posterUrl=poster;this.plot=plot;this.tags=tags
        }
    }
    override suspend fun loadLinks(
        data:String,isCasting:Boolean,
        subtitleCallback:(SubtitleFile)->Unit,
        callback:(ExtractorLink)->Unit
    ):Boolean=AkwamResolver(mainUrl).resolve(data,subtitleCallback,callback)
}
