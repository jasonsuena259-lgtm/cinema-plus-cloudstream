package com.dexter

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

data class DexterCard(val title:String,val url:String,val poster:String?,val series:Boolean)
data class DexterEpisode(val url:String,val season:Int,val number:Int,val title:String?)
data class DexterSource(val url:String,val origin:String)

object DexterParser {
    const val BASE = "https://dexter.pw"
    private val movie = Regex("""^/watch/[0-9]+-[^/]+/?$""")
    private val series = Regex("""^/show/[0-9]+-[^/]+/?$""")
    private val episode = Regex("""^/watch/show/[0-9]+-[^/]+/([0-9]+)/([0-9]+)(?:/|$)""")

    fun document(html:String,base:String=BASE):Document = Jsoup.parse(html,base)

    fun site(raw:String,base:String=BASE):String? {
        val url=base.toHttpUrlOrNull()?.resolve(raw) ?: return null
        return url.toString().takeIf {
            url.scheme=="https" && url.host in setOf("dexter.pw","www.dexter.pw") &&
                url.username.isBlank() && url.password.isBlank()
        }
    }

    fun isMovie(url:String):Boolean=movie.matches(url.toHttpUrlOrNull()?.encodedPath.orEmpty())
    fun isSeries(url:String):Boolean=series.matches(url.toHttpUrlOrNull()?.encodedPath.orEmpty())
    fun isEpisode(url:String):Boolean=
        episode.containsMatchIn(url.toHttpUrlOrNull()?.encodedPath.orEmpty())

    private fun image(node:Element?):String? {
        if(node==null)return null
        val img=if(node.normalName()=="img")node else node.selectFirst("img")
        val source=img?.let {
            listOf("data-src","data-original","data-lazy-src","src")
                .firstNotNullOfOrNull { attr -> it.attr(attr).takeIf(String::isNotBlank) }
        } ?:return null
        return img.baseUri().toHttpUrlOrNull()?.resolve(source)?.toString()
            ?.takeIf { it.startsWith("https://") }
    }

    fun cards(html:String,base:String=BASE):List<DexterCard> {
        val found=linkedMapOf<String,DexterCard>()
        val doc=document(html,base)
        for(a in doc.select("a[href]")){
            val url=site(a.attr("href"),base) ?:continue
            val show=isSeries(url)
            if(!show && !isMovie(url))continue
            val wrap=a.closest("article,.card,.movie-card,.show-card,.poster,.media-card,li")
            val heading=wrap?.selectFirst("h2,h3,h4,.title,.name")?.text()?.trim()
            val title=sequenceOf(
                heading,
                a.attr("aria-label"),
                a.attr("title"),
                a.selectFirst("img")?.attr("alt"),
                a.text()
            ).mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
                .firstOrNull { it.length in 2..160 && !it.equals("Play",true) &&
                    !it.equals("Watch",true) && !it.equals("Details",true) }
                ?:continue
            val poster=image(wrap)?:image(a)
            val previous=found[url]
            if(previous==null || (previous.poster==null && poster!=null))
                found[url]=DexterCard(title,url,poster,show)
        }
        return found.values.toList()
    }

    fun episodes(doc:Document):List<DexterEpisode> {
        val found=linkedMapOf<String,DexterEpisode>()
        for(a in doc.select("a[href]")){
            val url=site(a.attr("href"),doc.baseUri())?:continue
            val match=episode.find(url.toHttpUrlOrNull()?.encodedPath.orEmpty())?:continue
            val season=match.groupValues[1].toIntOrNull()?:continue
            val number=match.groupValues[2].toIntOrNull()?:continue
            val title=a.attr("title").ifBlank { a.text().trim() }.takeIf {
                it.isNotBlank() && it.length<=100
            }
            found.putIfAbsent(url,DexterEpisode(url,season,number,title))
        }
        return found.values.sortedWith(compareBy<DexterEpisode> { it.season }.thenBy { it.number })
    }

    fun seasonPages(doc:Document):List<Pair<String,Int>> {
        val found=linkedMapOf<String,Int>()
        for(a in doc.select("a[href]")){
            val url=site(a.attr("href"),doc.baseUri())?:continue
            val label=a.text().trim()
            val number=Regex("""(?i)(?:season|الموسم)\s*(\d{1,2})""")
                .find(label)?.groupValues?.get(1)?.toIntOrNull() ?:continue
            // Only follow links actually present in the HTML. Never invent season URLs.
            if(!url.startsWith(BASE+"/show/") && !url.startsWith(BASE+"/watch/show/"))
                continue
            if(url!=doc.baseUri() && !url.substringBefore('#').equals(doc.baseUri().substringBefore('#')))
                found[url]=number
        }
        return found.entries.map { it.key to it.value }
    }

    fun poster(doc:Document):String? {
        val og=doc.selectFirst("meta[property=og:image]")?.attr("content")
        return og?.takeIf(String::isNotBlank)?.let {
            doc.baseUri().toHttpUrlOrNull()?.resolve(it)?.toString()
        } ?:image(doc.selectFirst("main img,article img"))
    }

    fun title(doc:Document):String? =
        doc.selectFirst("h1")?.text()?.trim()?.takeIf(String::isNotBlank)
            ?:doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf(String::isNotBlank)

    fun plot(doc:Document):String? =
        doc.selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.length>30 }
            ?:doc.select("main p,article p").map { it.text().trim() }.firstOrNull { it.length>65 }

    fun hasNext(doc:Document,page:Int):Boolean =
        doc.select("a[href]").any { it.absUrl("href").contains("page="+(page+1)) }

    fun externalUrl(raw:String,base:String):String? {
        val url=base.toHttpUrlOrNull()?.resolve(raw)?:return null
        if(url.scheme!="https" || url.host.isBlank() ||
            url.username.isNotBlank() || url.password.isNotBlank())return null
        val host=url.host.lowercase()
        if(host=="localhost" || host.endsWith(".local") || host.startsWith("127.") ||
            host.startsWith("10.") || host.startsWith("192.168.") ||
            host.startsWith("169.254."))return null
        return url.toString()
    }

    fun sources(html:String,base:String):List<DexterSource> {
        val doc=document(html,base)
        val found=linkedMapOf<String,DexterSource>()
        for(el in doc.select("iframe[src],iframe[data-src],video[src],video[data-src],video source[src],source[src],a[href]")){
            val raw=when {
                el.hasAttr("src")->el.attr("src")
                el.hasAttr("data-src")->el.attr("data-src")
                else->el.attr("href")
            }
            val url=externalUrl(raw,base)?:continue
            val ext=url.toHttpUrlOrNull()?.encodedPath.orEmpty().lowercase()
            if(el.normalName()=="a" && !listOf(".m3u8",".mp4",".mkv").any(ext::endsWith))
                continue
            found.putIfAbsent(url,DexterSource(url,"html"))
        }
        val script=doc.select("script:not([src])").joinToString("\n") { it.data() }
            .replace("\\/","/")
        val regex=Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4|mkv)(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE)
        for(m in regex.findAll(script).take(50)){
            val url=externalUrl(m.value,base)?:continue
            found.putIfAbsent(url,DexterSource(url,"inline-script"))
        }
        return found.values.toList()
    }

    fun subtitles(html:String,base:String):List<Pair<String,String>> {
        val doc=document(html,base)
        val out=linkedMapOf<String,String>()
        for(el in doc.select("track[src],a[href]")){
            val raw=el.attr("src").ifBlank { el.attr("href") }
            val url=externalUrl(raw,base)?:continue
            val path=url.toHttpUrlOrNull()?.encodedPath.orEmpty().lowercase()
            if(listOf("thumbs.vtt","thumbnails.vtt","storyboard").any(path::contains))
                continue
            if(!listOf(".vtt",".srt",".ass",".ssa").any(path::endsWith))continue
            val kind=el.attr("kind").lowercase()
            if(el.normalName()=="track" && kind.isNotEmpty() &&
                kind !in setOf("subtitles","captions"))continue
            val label=el.attr("srclang").ifBlank { el.attr("label") }
                .ifBlank { el.attr("hreflang") }
                .ifBlank { "Unknown" }
            out.putIfAbsent(url,label)
        }
        return out.map { (url,lang)-> lang to url }
    }

    fun quality(url:String):Int {
        val path=url.toHttpUrlOrNull()?.encodedPath.orEmpty()
        val h=Regex("""/(\d{3,4})x(\d{3,4})(?:/|$)""").find(path)
        return h?.groupValues?.get(2)?.toIntOrNull()
            ?:Regex("""(?i)(2160|1600|1440|1080|720|480|360)p""")
                .find(path)?.groupValues?.get(1)?.toIntOrNull() ?:0
    }

    fun isHls(text:String):Boolean {
        val t=text.trimStart('\uFEFF',' ','\n','\r','\t')
        return t.startsWith("#EXTM3U") &&
            (t.contains("#EXT-X-STREAM-INF") || t.contains("#EXTINF:"))
    }
}
