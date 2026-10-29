package com.akwcam
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class CamCard(val url:String,val title:String,val poster:String?,val series:Boolean)
data class CamEpisode(val url:String,val title:String,val poster:String?,val season:Int?,val number:Int?)
data class CamSource(val url:String,val name:String)
object AkwCamParser {
 const val BASE="https://akw.cam"
 fun site(raw:String,base:String=BASE):String?=base.toHttpUrlOrNull()?.resolve(raw)?.takeIf{it.host=="akw.cam" && it.scheme=="https"}?.toString()
 fun doc(html:String,url:String)=Jsoup.parse(html,url)
 fun cards(d:Document)=d.select("a.br-card-link").mapNotNull{a->
  val u=site(a.attr("href"),d.baseUri()) ?: return@mapNotNull null
  val title=a.selectFirst(".br-card-title")?.text().orEmpty()
  if(title.isBlank())return@mapNotNull null
  CamCard(u,title,a.selectFirst("img")?.absUrl("src")?.takeIf{it.isNotBlank()},u.contains("/series/")||u.contains("/season/")||a.select(".ak-type-label").text().contains("حلقة")||title.startsWith("مسلسل")||title.startsWith("انمي"))
 }.distinctBy{it.url}
 fun season(text:String):Int? {
  Regex("""(?i)(?:season[- ]|الموسم\s*)(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let{return it}
  val words=listOf("الاول","الثاني","الثالث","الرابع","الخامس","السادس","السابع","الثامن","التاسع","العاشر")
  val t=text.replace("أ","ا");return words.indexOfFirst{t.contains("الموسم $it")}.takeIf{it>=0}?.plus(1)
 }
 fun episodes(d:Document):List<CamEpisode> =d.select(".br-series-season").flatMap{group->
  val season=season(group.select(".br-series-season-head h3").text())
  group.select(".br-series-detail-card-link").mapNotNull{a->
   val u=site(a.attr("href"),d.baseUri()) ?: return@mapNotNull null
   CamEpisode(u,a.select("h4").text(),a.selectFirst("img")?.absUrl("src"),season,Regex("\\d+").find(a.select(".br-series-detail-epno").text())?.value?.toIntOrNull())
  }
 }.distinctBy{it.url}.sortedWith(compareBy({it.season ?: 0},{it.number ?: Int.MAX_VALUE}))
 fun sources(d:Document):List<CamSource> {
  val out=mutableListOf<CamSource>()
  for(a in d.select(".br-server-item[data-src],.br-download-row a[href],iframe#brWatchFrame")){
   val raw=when{a.hasAttr("data-src")->a.attr("data-src");a.tagName()=="iframe"->a.attr("src");else->a.attr("href")}
   val u=d.baseUri().toHttpUrlOrNull()?.resolve(raw)?.takeIf{it.scheme in listOf("http","https")} ?: continue
   val name=a.attr("data-name").ifBlank{a.parent()?.select("strong")?.text().orEmpty()}.ifBlank{u.host}
   out+=CamSource(u.toString(),"$name · ${u.host}")
  }
  return out.distinctBy{it.url}
 }
 fun mirrors(raw:String,base:String):List<CamSource> {
  val root=runCatching{com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(raw)}.getOrNull() ?: return emptyList()
  return root.path("props").path("streams").path("data").flatMap{level->level.path("mirrors").mapNotNull{m->
   val link=m.path("link").asText().takeIf{it.isNotBlank()} ?: return@mapNotNull null
   base.toHttpUrlOrNull()?.resolve(link)?.takeIf{it.scheme in listOf("https","http")}?.let{CamSource(it.toString(),m.path("driver").asText().ifBlank{it.host}+" · "+level.path("label").asText())}
  }}.distinctBy{it.url}
 }
 fun next(d:Document,page:Int)=d.select(".br-pagination a[href]").any{(it.text().toIntOrNull() ?: 0)>page}
 fun media(script:String,base:String):List<String> = Regex("""["']((?:https?:)?//[^"'\s]+(?:\.m3u8|\.mp4)(?:\?[^"'\s]*)?)["']""").findAll(script.replace("\\/","/")).mapNotNull{base.toHttpUrlOrNull()?.resolve(it.groupValues[1])?.toString()}.distinct().toList()
}
