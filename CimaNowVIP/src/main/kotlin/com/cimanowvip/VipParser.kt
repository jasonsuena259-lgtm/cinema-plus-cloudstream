package com.cimanowvip

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import java.net.URLDecoder
import okio.ByteString.Companion.decodeBase64

data class VipCard(val title:String,val url:String,val poster:String?,val series:Boolean)
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
    fun sources(doc:Document):List<String> {
        val hosts=listOf("jetload.top","vk.com","vkvideo.ru","ok.ru","forafile.com","frdl.my","filemoon","upns.","upnshare","uqload","savefiles")
        return doc.select("iframe[src],iframe[data-src],a[href],[data-url],[data-link]").mapNotNull{e->
            val raw=listOf("data-src","src","data-url","data-link","href").firstNotNullOfOrNull{e.absUrl(it).takeIf{v->v.isNotBlank()}} ?: return@mapNotNull null
            val u=unwrap(raw) ?: return@mapNotNull null;val h=u.toHttpUrlOrNull()!!.host
            u.takeIf{e.tagName()=="iframe"||hosts.any{h.contains(it)}||u.substringBefore('?').endsWith(".mp4")||u.substringBefore('?').endsWith(".m3u8")}
        }.distinct()
    }
}
