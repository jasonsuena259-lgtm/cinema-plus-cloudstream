package com.myseed
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class SeedItem(val title:String,val url:String,val poster:String?,val series:Boolean,val anime:Boolean)
data class SeedEpisode(val title:String,val url:String,val season:Int,val number:Int)
data class SeedSource(val name:String,val url:String)
object MySeedParser {
    const val BASE="https://myseed.baby"
    val sections=listOf(
        "/topvideos.php" to "الاكثر مشاهدة", "/newvideos.php" to "الجديد",
        "/category.php?cat=cimanow" to "مسلسلات وبرامج عرب سيد",
        "/category.php?cat=cimanow-arabic-tv-series" to "مسلسلات عربية",
        "/category.php?cat=cimanow-turkish-series" to "مسلسلات تركية",
        "/category.php?cat=cimanow-foreign-tv-series" to "مسلسلات اجنبية",
        "/category.php?cat=cimanow-korean-dramas" to "مسلسلات كورية",
        "/category.php?cat=cimanow-tv-programs" to "برامج تلفزيونية",
        "/category.php?cat=cimanow-anime-and-cartoon-series" to "مسلسلات انمي وكرتون",
        "/category.php?cat=cimanow-indian-tv-series" to "مسلسلات هندية",
        "/category.php?cat=movies-cimanow" to "افلام",
        "/category.php?cat=cimanow-arabic-movies" to "افلام عربية",
        "/category.php?cat=cimanow-foreign-films" to "افلام اجنبية",
        "/category.php?cat=cimanow-turkish-films" to "افلام تركية",
        "/category.php?cat=cimanow-anime-and-cartoon-movies" to "افلام انمي وكرتوني",
        "/category.php?cat=cimanow-indian-movies" to "افلام هندية")
    fun siteUrl(raw:String):String? {
        if(raw.isBlank())return null
        val u=BASE.toHttpUrlOrNull()?.resolve(raw.replace("&amp;","&")) ?: return null
        return u.takeIf{it.host=="myseed.baby" && it.username.isEmpty() && it.password.isEmpty() && it.port in listOf(80,443)}?.newBuilder()?.scheme("https")?.port(443)?.build()?.toString()
    }
    fun watchUrl(raw:String):String? {
        val u=siteUrl(raw)?.toHttpUrlOrNull() ?: return null
        val id=u.queryParameter("vid") ?: return null
        if(u.encodedPath!="/watch.php" || !Regex("[a-zA-Z0-9_-]+").matches(id))return null
        return "$BASE/watch.php?vid=$id"
    }
    fun playUrl(raw:String)=watchUrl(raw)?.replace("/watch.php?","/play.php?")
    fun doc(html:String,url:String)=Jsoup.parse(html,url)
    fun cards(doc:Document):List<SeedItem> = doc.select(".thumbnail").mapNotNull { el ->
        val a=el.selectFirst(".caption h3 a[href], h3 a[href]") ?: return@mapNotNull null
        val u=watchUrl(a.absUrl("href")) ?: return@mapNotNull null
        val title=a.text().trim().takeIf{it.isNotEmpty()} ?: return@mapNotNull null
        val img=el.selectFirst("img")
        val poster=listOf("data-src","data-original","src").firstNotNullOfOrNull{attr->img?.absUrl(attr)?.takeIf{it.startsWith("http")}}
        val series=!title.startsWith("فيلم") && (title.contains("مسلسل") || title.contains("الحلقة") || title.startsWith("برنامج") || title.startsWith("انمي"))
        SeedItem(title,u,poster,series,series && (title.contains("انمي") || title.contains("كرتون")))
    }.distinctBy{it.url}
    fun next(doc:Document,page:Int):String?=doc.select(".pagination li:not(.disabled) a[href]").mapNotNull{siteUrl(it.absUrl("href"))}.firstOrNull{it.toHttpUrlOrNull()?.queryParameter("page")?.toIntOrNull()==page+1}
    fun number(text:String):Int?=Regex("[0-9٠-٩]+").find(text)?.value?.map{c->if(c in '٠'..'٩') '0'+(c-'٠') else c}?.joinToString("")?.toIntOrNull()
    fun episodes(doc:Document):List<SeedEpisode> {
        val seasons=doc.select(".SeasonsBox .tablinks[onclick]").mapNotNull{b->
            val target=Regex("""openCity\([^,]+,\s*['"]([^'"]+)['"]\)""").find(b.attr("onclick"))?.groupValues?.get(1) ?: return@mapNotNull null
            val n=number(b.text()) ?: return@mapNotNull null
            target to n
        }.toMap()
        return doc.select(".SeasonsEpisodesMain .tabcontent").flatMap{tab->
            val season=seasons[tab.id()] ?: return@flatMap emptyList()
            tab.select("a[href]").mapNotNull{a->
                val u=watchUrl(a.absUrl("href")) ?: return@mapNotNull null
                val n=number(a.selectFirst("em")?.text().orEmpty()) ?: return@mapNotNull null
                if(n<1)return@mapNotNull null
                SeedEpisode(a.attr("title").ifBlank{"الحلقة $n"},u,season,n)
            }
        }.distinctBy{it.url}.sortedWith(compareBy({it.season},{it.number}))
    }
    fun sources(doc:Document):List<SeedSource> = doc.select(".list_servers li[data-embed]").mapNotNull { li ->
        val iframe=Jsoup.parseBodyFragment(li.attr("data-embed"),doc.location()).selectFirst("iframe[src]") ?: return@mapNotNull null
        val url=iframe.absUrl("src").toHttpUrlOrNull()?.takeIf{it.scheme=="https"}?.toString() ?: return@mapNotNull null
        val name=li.selectFirst("strong")?.text()?.trim()?.takeIf{it.isNotBlank()} ?: return@mapNotNull null
        SeedSource(name,url)
    }.distinctBy{it.name to it.url}
    fun media(script:String,base:String):List<String> {
        val s=script.replace("\\/","/")
        return Regex("""["']((?:https?:)?//[^"'\s]+(?:\.m3u8|\.mp4)(?:\?[^"'\s]*)?)["']""").findAll(s).mapNotNull{base.toHttpUrlOrNull()?.resolve(it.groupValues[1])?.toString()}.distinct().toList()
    }
}
