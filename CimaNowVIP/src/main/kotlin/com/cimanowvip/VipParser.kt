package com.cimanowvip

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import java.net.URLDecoder
import okio.ByteString.Companion.decodeBase64

data class VipCard(val title:String,val url:String,val poster:String?,val series:Boolean)
data class VipSection(val title:String,val url:String)
data class VipEpisode(val title:String,val url:String,val season:Int,val episode:Int)
object VipParser {
    const val BASE="https://vip.cimanowinc.com"
    fun site(raw:String,base:String=BASE):String?=base.toHttpUrlOrNull()?.resolve(raw)?.takeIf{it.scheme=="https"&&it.host=="vip.cimanowinc.com"&&it.port==443&&it.username.isEmpty()&&it.password.isEmpty()}?.toString()
    fun stable(raw:String):String?=site(raw)?.toHttpUrlOrNull()?.newBuilder()?.query(null)?.fragment(null)?.build()?.toString()?.replace(Regex("watching/?$"),"")
    fun decode(s:String)=runCatching{URLDecoder.decode(s,"UTF-8")}.getOrDefault(s)
    fun poster(a:org.jsoup.nodes.Element):String?=a.select("img").firstOrNull{!it.attr("alt").equals("logo",true)&&!it.attr("src").contains("لوجو") }?.let{img->listOf("data-src","data-lazy-src","src").firstNotNullOfOrNull{img.absUrl(it).takeIf{u->u.startsWith("https://")}}}
    fun cards(doc:Document):List<VipCard> = doc.select("article > a[href],section a:has(li[aria-label=title])").mapNotNull{a->
        val url=stable(a.absUrl("href")) ?: return@mapNotNull null
        val title=a.selectFirst("li[aria-label=title]")?.text()?.takeIf{it.isNotBlank()} ?: a.select("img[alt]").firstOrNull{it.attr("alt").isNotBlank()&&!it.attr("alt").equals("logo",true)}?.attr("alt") ?: return@mapNotNull null
        VipCard(title,url,poster(a),title.contains("مسلسل")||url.contains("/selary/")||decode(url).contains("مسلسل"))
    }.distinctBy{it.url}
    fun episodes(doc:Document,season:Int=1):List<VipEpisode> = doc.select("ul#eps a[href],ul#episodes a[href]").mapNotNull{a->
        val url=stable(a.absUrl("href")) ?: return@mapNotNull null
        val readable=decode(url)
        val ep=Regex("(?:-ح|الحلقة-)([0-9]+)").find(readable)?.groupValues?.get(1)?.toIntOrNull() ?: a.select("em").lastOrNull()?.text()?.toIntOrNull() ?: return@mapNotNull null
        val sn=Regex("-ج([0-9]+)(?:-|/)").find(readable)?.groupValues?.get(1)?.toIntOrNull() ?: season
        VipEpisode(a.text().ifBlank{"الحلقة $ep"},url,sn,ep)
    }.distinctBy{it.url}.sortedWith(compareBy({it.season},{it.episode}))
    fun unwrap(raw:String):String? {
        var u=raw.toHttpUrlOrNull() ?: return null
        if(u.host=="href.li")u=u.query?.toHttpUrlOrNull() ?: return null
        return u.takeIf{it.scheme=="https"&&it.username.isEmpty()&&it.password.isEmpty()}?.toString()
    }
    fun jetload(raw:String):String? {
        val u=unwrap(raw)?.toHttpUrlOrNull() ?: return null
        if(u.host!="jetload.top"||u.encodedPath!="/Jetloadon4/")return null
        val decoded=runCatching{u.queryParameter("link")?.decodeBase64()?.utf8()}.getOrNull()?.toHttpUrlOrNull() ?: return null
        return decoded.takeIf{it.scheme=="https"&&it.host.endsWith(".jetload.top")&&it.encodedPath.endsWith(".mp4")&&it.username.isEmpty()}?.toString()
    }
    fun trailer(raw:String):Boolean {
        val host=raw.toHttpUrlOrNull()?.host?.lowercase() ?: return false
        return listOf("youtube.com","youtube-nocookie.com","youtu.be","googlevideo.com","ytimg.com").any{host==it||host.endsWith("."+it)}
    }
    fun normal(s:String)=s.replace("أ","ا").replace("إ","ا").replace("آ","ا").replace(Regex("[\\u064B-\\u065F]"),"").trim()
    private val knownHosts=listOf("jetload.top","vk.com","vkvideo.ru","ok.ru","forafile.com","frdl.my","filemoon.sx","filemoon.to","filemoon.in","filemoon.nl","filemoon.link","upns.online","upnshare.com","uqload.com","uqload.to","savefiles.com")
    fun hostSource(raw:String):Boolean {
        val host=unwrap(raw)?.toHttpUrlOrNull()?.host ?: return false
        return knownHosts.any{host==it||host.endsWith("."+it)}
    }
    fun directMedia(raw:String):Boolean = unwrap(raw)?.toHttpUrlOrNull()?.encodedPath?.let{it.endsWith(".mp4",true)||it.endsWith(".m3u8",true)}==true&&!trailer(raw)
    fun sections(doc:Document):List<VipSection> {
        val items=doc.select("a[href]")
        return items.mapNotNull{a->
            val raw=a.absUrl("href");val url=site(raw) ?: return@mapNotNull null
            val path=url.toHttpUrlOrNull()!!.encodedPath
            if(listOf("signin","signup","logout","signout","account","profile","subscribe","subscription").any{path.contains(it,true)})return@mapNotNull null
            val title=a.text().trim().ifBlank{a.attr("aria-label")}.ifBlank{a.attr("title")}
            if(title.isBlank()||title.length>80||a.closest("article,#eps,#episodes,#related")!=null||a.selectFirst("li[aria-label=title]")!=null)return@mapNotNull null
            VipSection(title,url)
        }.distinctBy{it.url}
    }
    fun section(doc:Document,key:String):String? {
        val labels=when(key){"movies"->listOf("الافلام","افلام","Movies");"series"->listOf("المسلسلات","مسلسلات","Series");"programs"->listOf("البرامج","برامج","Shows");"latest"->listOf("الاحدث","احدث الاضافات","الجديد","Latest");else->emptyList()}
        return sections(doc).firstOrNull{s->labels.any{normal(s.title).equals(it,true)}}?.url
    }
    fun next(doc:Document,page:Int):String? {
        val links=doc.select("a[rel=next],ul[aria-label=pagination] a[href],.pagination a[href],nav[aria-label*=pagination] a[href]")
        val a=links.firstOrNull{it.attr("rel").split(" ").contains("next")} ?: links.firstOrNull{it.text().trim()==(page+1).toString()}
        return a?.absUrl("href")?.let{site(it)}?.takeIf{it!=doc.location()}
    }
    fun watchLink(doc:Document,item:String):String? {
        val key=stable(item) ?: return null
        val direct=doc.select("a[href]").mapNotNull{site(it.absUrl("href"))}.firstOrNull{it.contains("/watching/")&&stable(it)==key}
        if(direct!=null)return direct
        return doc.select("a.shine[href],a[aria-label=watch][href]").firstNotNullOfOrNull{a->unwrap(a.absUrl("href"))?.takeIf{!trailer(it)}}
    }
    fun sources(doc:Document):List<String> {
        return doc.select("iframe[src],iframe[data-src],a[href],[data-url],[data-link],video[src],video source[src]").mapNotNull{e->
            if(e.parents().any{it.id().equals("trailer",true)||it.attr("aria-label").equals("trailer",true)})return@mapNotNull null
            val raw=listOf("data-src","src","data-url","data-link","href").firstNotNullOfOrNull{e.absUrl(it).takeIf{v->v.isNotBlank()}} ?: return@mapNotNull null
            val u=unwrap(raw)?.takeUnless{trailer(it)} ?: return@mapNotNull null
            u.takeIf{hostSource(it)||directMedia(it)}
        }.distinct()
    }
}
