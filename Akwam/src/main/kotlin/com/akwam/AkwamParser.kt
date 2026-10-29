package com.akwam

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

data class AkwamItem(val title:String,val url:String,val poster:String?,val series:Boolean)
data class AkwamEpisode(val url:String,val season:Int,val number:Int)
data class AkwamQuality(val id:String,val quality:Int,val watch:String?,val download:String?)

object AkwamParser {
    const val BASE="https://akwam.ss"
    private val stream=Regex("""^/(watch|download)/(\d+)/(\d+)(?:/|$)""")
    private val quality=Regex("""(?i)(2160|1080|720|480|360|240)\s*p?""")
    fun document(html:String,base:String):Document=Jsoup.parse(html,base)
    fun site(raw:String,base:String=BASE):String?{
        val url=base.toHttpUrlOrNull()?.resolve(raw)?:return null
        return url.toString().takeIf{url.scheme=="https"&&
            (url.host=="akwam.ss"||url.host=="www.akwam.ss")}
    }
    private fun image(node:Element):String?{
        val img=node.selectFirst("picture img,img")?:return null
        val raw=listOf("data-src","data-original","data-lazy-src","src")
            .map(img::attr).firstOrNull{it.isNotBlank()}?:return null
        return img.baseUri().toHttpUrlOrNull()?.resolve(raw)?.toString()
    }
    fun cards(html:String,base:String=BASE):List<AkwamItem>{
        val out=linkedMapOf<String,AkwamItem>()
        for(a in document(html,base).select("a[href]")){
            val url=site(a.attr("href"),base)?:continue
            val path=url.toHttpUrlOrNull()?.encodedPath.orEmpty()
            val series=when{
                Regex("""^/movie/\d+(?:/|$)""").containsMatchIn(path)->false
                Regex("""^/series/\d+(?:/|$)""").containsMatchIn(path)->true
                else->continue
            }
            val heading=a.selectFirst("h3,h4")?:a.parent()?.selectFirst("h3,h4")
            val title=(heading?.text()?:a.text()).trim()
            if(title.length !in 2..150||title in setOf("مشاهدة","شاهد الآن","الحلقات"))continue
            val pic=image(a)?:a.parent()?.let(::image)
            out.putIfAbsent(url,AkwamItem(title,url,pic,series))
        }
        return out.values.toList()
    }
    fun episodes(doc:Document,season:Int=1):List<AkwamEpisode>{
        val out=linkedMapOf<String,AkwamEpisode>()
        for(a in doc.select("a[href*=/episode/]")){
            val url=site(a.attr("href"),doc.baseUri())?:continue
            if(!Regex("""^/episode/\d+(?:/|$)""").containsMatchIn(
                url.toHttpUrlOrNull()?.encodedPath.orEmpty()))continue
            val label=a.text()+" "+a.parent()?.text().orEmpty()
            val number=Regex("""(?:الحلقة|حلقة|episode|ep)[^\d]{0,10}(\d{1,4})""",
                RegexOption.IGNORE_CASE).find(label)?.groupValues?.get(1)?.toIntOrNull()
                ?:Regex("""(?:الحلقة|episode)[-_ ]?(\d{1,4})""",
                    RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.toIntOrNull()
                ?:continue
            out.putIfAbsent(url,AkwamEpisode(url,season,number))
        }
        return out.values.sortedBy{it.number}
    }
    fun seasons(doc:Document):List<Pair<String,Int>>=
        doc.select("a[href*=/season/]").mapNotNull{a->
            val url=site(a.attr("href"),doc.baseUri())?:return@mapNotNull null
            val n=Regex("""\d{1,2}""").find(a.text())?.value?.toIntOrNull()
                ?:return@mapNotNull null
            url to n
        }.distinctBy{it.first}
    fun hasNext(doc:Document,page:Int):Boolean=
        doc.select("a[href*='page=']").any{it.absUrl("href").contains("page="+(page+1))}
    fun qualities(html:String,base:String=BASE):List<AkwamQuality>{
        val doc=document(html,base)
        val entries=linkedMapOf<String,Pair<String?,String?>>()
        val numbers=mutableMapOf<String,Int>()
        for(a in doc.select("a[href]")){
            val url=site(a.attr("href"),base)?:continue
            val m=stream.find(url.toHttpUrlOrNull()?.encodedPath.orEmpty())?:continue
            val kind=m.groupValues[1];val id=m.groupValues[2]
            val old=entries[id]?: (null to null)
            entries[id]=if(kind=="watch")url to old.second else old.first to url
            val q=quality.find(a.parent()?.text().orEmpty().take(120))
                ?.groupValues?.get(1)?.toIntOrNull()
            if(q!=null)numbers[id]=q
        }
        val tabs=doc.select("a,span,li").mapNotNull{
            val text=it.ownText().trim()
            if(text.length<12)quality.matchEntire(text)?.groupValues?.get(1)?.toIntOrNull()
            else null
        }.distinct()
        return entries.entries.mapIndexed{index,entry->
            AkwamQuality(entry.key,numbers[entry.key]?:tabs.getOrNull(index)?:0,
                entry.value.first,entry.value.second)
        }
    }
    fun downloads(html:String,base:String):List<String>=
        document(html,base).select("a[href]").mapNotNull{a->
            val url=base.toHttpUrlOrNull()?.resolve(a.attr("href"))?:return@mapNotNull null
            val p=url.encodedPath.lowercase()
            if(url.scheme=="https"&&(url.host.endsWith(".downet.net")||
                url.host=="downet.net"||p.endsWith(".mp4")||p.endsWith(".mkv")||
                p.endsWith(".m3u8")))url.toString() else null
        }.distinct()
    fun embeds(html:String,base:String):List<String>=
        document(html,base).select("iframe[src],video[src],video source[src],source[src]")
            .mapNotNull{base.toHttpUrlOrNull()?.resolve(it.attr("src"))?.toString()}
            .filter{it.startsWith("https://")}.distinct()
    fun title(doc:Document)=doc.selectFirst("h1")?.text()?.trim()?.takeIf{it.isNotEmpty()}
    fun poster(doc:Document)=doc.selectFirst("meta[property=og:image]")?.attr("content")
        ?.let{doc.baseUri().toHttpUrlOrNull()?.resolve(it)?.toString()}
        ?:doc.select("img").mapNotNull(::image).firstOrNull{
            !it.contains("logo")&&!it.contains("/icon")
        }
    fun plot(doc:Document)=doc.select("p").map{it.text().trim()}.firstOrNull{
        it.length>70&&!it.contains("جميع الحقوق")
    }
}
