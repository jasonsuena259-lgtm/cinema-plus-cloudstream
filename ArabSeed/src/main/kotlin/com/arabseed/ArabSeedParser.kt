package com.arabseed

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class ArabSeedItem(val title:String,val url:String,val poster:String?,val series:Boolean,val anime:Boolean)
data class ArabSeedEpisode(val title:String,val url:String,val season:Int,val number:Int)
data class ArabSeedSource(val name:String,val url:String)

internal object ArabSeedParser {
    const val BASE = "https://arabsed.cam"
    // Focus the CloudStream homepage on useful collections, not every tiny
    // WordPress tag. The site still exposes niche categories in its own menu.
    val sections = listOf(
        "/recently/" to "المضاف حديثًا",
        "/movies/" to "أحدث الأفلام",
        "/series/" to "جميع المسلسلات",
        "/category/افلام-اجنبية-2026/" to "أفلام أجنبية 2026",
        "/category/افلام-اجنبية-2025/" to "أفلام أجنبية 2025",
        "/category/افلام-اجنبية/" to "أفلام أجنبية",
        "/category/افلام-عربي/" to "أفلام عربية",
        "/category/افلام-اسيوي/" to "أفلام آسيوية",
        "/category/افلام-هندية/" to "أفلام هندية",
        "/category/افلام-انمي/" to "أفلام أنمي",
        "/category/مسلسلات-اجنبية/" to "مسلسلات أجنبية",
        "/category/مسلسلات-عربية/" to "مسلسلات عربية",
        "/category/مسلسلات-اسيوية/" to "مسلسلات آسيوية",
        "/category/مسلسلات-انمي/" to "مسلسلات أنمي",
        "/category/مسلسلات-هندية/" to "مسلسلات هندية"
    )
    private val archives = setOf("main","recently","movies","series","category","search","watch","download","page")
    fun siteUrl(raw:String):String? {
        if (raw.isBlank()) return null
        val u = BASE.toHttpUrlOrNull()!!.resolve(raw.replace("&amp;","&")) ?: return null
        if (u.scheme!="https" || u.host!="arabsed.cam" || u.port!=443 || u.username.isNotEmpty() || u.password.isNotEmpty())return null
        return u.toString()
    }
    fun isContent(url:String):Boolean {
        val u=siteUrl(url)?.toHttpUrlOrNull() ?: return false
        val seg=u.pathSegments.filter{it.isNotBlank()}
        return (seg.size==1 && seg[0] !in archives) || (seg.size==2 && seg[0]=="series")
    }
    fun episodeBase(raw:String):String? {
        val u=siteUrl(raw)?.toHttpUrlOrNull() ?: return null
        val segments=u.pathSegments.filter{it.isNotBlank()}
        val slug=when {
            segments.size==1 && segments[0] !in archives -> segments[0]
            segments.size==2 && segments[1] in setOf("watch","download") && segments[0] !in archives -> segments[0]
            else -> return null
        }
        return BASE + "/" + slug + "/"
    }
    fun watchUrl(raw:String)=episodeBase(raw)?.plus("watch/")
    fun downloadUrl(raw:String)=episodeBase(raw)?.plus("download/")
    fun pageUrl(path:String,page:Int):String? {
        val base=siteUrl(path)?.toHttpUrlOrNull() ?: return null
        if(page<=1)return base.toString()
        val p=base.encodedPath.trimEnd('/')
        return base.newBuilder().encodedPath(p + "/page/" + page + "/").build().toString()
    }
    fun doc(html:String,url:String):Document=Jsoup.parse(html,url)
    private fun image(doc:Document,raw:String):String? =
        doc.location().toHttpUrlOrNull()?.resolve(raw.trim())?.toString()

    fun cards(doc:Document):List<ArabSeedItem> =
        doc.select(".item__contents, .series__box").mapNotNull { element ->
            val link=element.selectFirst("a.movie__block[href], a[href]") ?: return@mapNotNull null
            val url=siteUrl(link.absUrl("href")) ?: return@mapNotNull null
            if(!isContent(url))return@mapNotNull null
            val title=element.selectFirst("h3, .title___ h3")?.text()?.trim()
                ?.takeIf{it.isNotBlank()} ?: return@mapNotNull null
            val pic=element.selectFirst(".post__image img, .image__poster img, img")
            val raw=pic?.attr("data-src")?.ifBlank{pic.attr("src")}.orEmpty()
            val poster=raw.takeIf{it.isNotBlank()}?.let{image(doc,it)}
            val isAnime=title.contains("انمي")||title.contains("أنمي")
            val series=url.toHttpUrlOrNull()?.encodedPath?.startsWith("/series/")==true ||
                title.startsWith("مسلسل") || title.contains("الحلقة") ||
                (isAnime && title.contains("الموسم"))
            ArabSeedItem(title,url,poster,series,isAnime)
        }.distinctBy{it.url}

    fun next(doc:Document,page:Int)=doc.select(".page-numbers a[href], a.page-numbers[href]")
        .any { a ->
            a.absUrl("href").contains("/page/" + (page+1) + "/") ||
                a.text().trim().toIntOrNull()==page+1
        }

    fun number(raw:String):Int?=Regex("[0-9٠-٩]+").find(raw)?.value?.map{c->
        if(c in '٠'..'٩') '0'+(c-'٠') else c
    }?.joinToString("")?.toIntOrNull()

    fun seasonNumber(text:String):Int {
        val t=text.lowercase().replace("أ","ا").replace("إ","ا")
        // Year digits in a title (e.g. 2026) must never become season 2026.
        return when {
            "الاول" in t -> 1
            "الثاني" in t -> 2
            "الثالث" in t -> 3
            "الرابع" in t -> 4
            "الخامس" in t -> 5
            "السادس" in t -> 6
            "السابع" in t -> 7
            "الثامن" in t -> 8
            "التاسع" in t -> 9
            "العاشر" in t -> 10
            else -> Regex("""(?i)(?:الموسم|season)\s*[:#-]?\s*(\d{1,2})""").find(t)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 1
        }
    }
    fun seasonLinks(doc:Document):List<Pair<Int,String>> =
        doc.select(".episodes__section a[href*='/series/']").mapNotNull{a->
            val url=siteUrl(a.absUrl("href")) ?: return@mapNotNull null
            if(!url.toHttpUrlOrNull()!!.encodedPath.startsWith("/series/")) return@mapNotNull null
            seasonNumber(a.text()) to url
        }.distinctBy{it.second}

    fun episodes(doc:Document,season:Int):List<ArabSeedEpisode> =
        doc.select(".episodes__section ul.episodes__list a[href]").mapNotNull{a->
            val url=siteUrl(a.absUrl("href")) ?: return@mapNotNull null
            if(episodeBase(url)==null)return@mapNotNull null
            val n=number(a.selectFirst(".epi__num")?.text().orEmpty())
                ?: number(a.text()) ?: return@mapNotNull null
            ArabSeedEpisode("الحلقة " + n,url,season,n)
        }.distinctBy{it.url}.sortedBy{it.number}

    fun poster(doc:Document):String? {
        val fallback:org.jsoup.nodes.Element?=doc.selectFirst(".single__contents img, .single__cover img")
        val raw:String?=doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf{it.isNotBlank()}
            ?: fallback?.attr("data-src")?.ifBlank{fallback.attr("src")}
        return raw?.takeIf{it.isNotBlank()}?.let{image(doc,it)}
    }
    fun plot(doc:Document):String? {
        val element:org.jsoup.nodes.Element?=doc.selectFirst(".info__side .story, .info__side .post__desc, meta[name=description]")
        if(element==null)return null
        val value:String=if(element.tagName()=="meta")element.attr("content") else element.text()
        return value.trim().takeIf{it.isNotBlank()}
    }

    private fun external(base:String,raw:String):String? {
        if(raw.isBlank())return null
        val u=base.toHttpUrlOrNull()?.resolve(raw.trim()) ?: return null
        if(u.scheme !in listOf("https","http") || u.username.isNotEmpty() || u.password.isNotEmpty() ||
           u.host=="arabsed.cam") return null
        return u.toString()
    }
    fun watchSources(doc:Document):List<ArabSeedSource> =
        doc.select("button.arabsed-watch-server[data-src]").mapNotNull{a->
            val u=external(doc.location(),a.attr("data-src")) ?: return@mapNotNull null
            ArabSeedSource(a.text().trim().ifBlank{u.toHttpUrlOrNull()?.host.orEmpty()},u)
        }.distinctBy{it.url}

    fun downloadSources(doc:Document):List<ArabSeedSource> =
        doc.select(".arabsed-download-grid a.arabsed-download-card[href], a.arabsed-download-hero-link[href]")
            .mapNotNull{a->
                val u=external(doc.location(),a.absUrl("href")) ?: return@mapNotNull null
                val host=u.toHttpUrlOrNull()?.host.orEmpty().removePrefix("www.")
                ArabSeedSource(host.substringBefore('.').ifBlank{"Download"},u)
            }.distinctBy{it.url}

    fun media(script:String,base:String):List<String> {
        // Many 1vid / Vidmoly / Vidspeed player scripts use escaped JSON or
        // bare URL literals rather than the quoted strings we handled in v3.
        val normalized=script.replace("\\/","/")
            .replace("\\u002F","/").replace("\\u002f","/")
            .replace("\\u003A",":").replace("\\u003a",":")
            .replace("&amp;","&")
        val urlPattern=Regex("""(?i)(?:https?:)?//[^\s<>"'\\]+(?:\.m3u8|\.mp4)(?:\?[^\s<>"'\\]*)?""")
        val baseUrl=base.toHttpUrlOrNull() ?: return emptyList()
        return urlPattern.findAll(normalized).mapNotNull{match ->
            val raw=match.value.trimEnd(';',',',')',']')
            val u=baseUrl.resolve(raw) ?: return@mapNotNull null
            if(u.scheme !in listOf("https","http") || u.username.isNotEmpty() ||
                u.password.isNotEmpty())null else u.toString()
        }.distinct().toList()
    }
}
