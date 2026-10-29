package com.dexter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI

/** Public movie/series metadata fallback, no third-party API key required. */
data class DexterMetadata(
    val name:String, val poster:String?, val plot:String?,
    val episodes:List<DexterEpisode>
)
object DexterCatalog {
    const val BASE="https://v3-cinemeta.strem.io"
    private val mapper=ObjectMapper()
    private fun JsonNode.value(field:String):String =
        path(field).takeIf { it.isTextual || it.isNumber }?.asText().orEmpty().trim()
    fun catalogUrl(isSeries:Boolean,page:Int=1):String {
        val type=if(isSeries)"series" else "movie"
        return BASE+"/catalog/"+type+"/top"+
            (if(page>1)"/skip="+((page-1)*50) else "")+".json"
    }
    fun searchUrl(query:String,isSeries:Boolean):String {
        val type=if(isSeries)"series" else "movie"
        val encoded=java.net.URLEncoder.encode(query.trim(),"UTF-8")
        return BASE+"/catalog/"+type+"/top/search="+encoded+".json"
    }
    fun metaUrl(imdb:String,isSeries:Boolean):String? {
        if(!Regex("""tt\d{5,12}""").matches(imdb))return null
        return BASE+"/meta/"+(if(isSeries)"series" else "movie")+"/"+imdb+".json"
    }
    fun imdbFromUrl(raw:String):String? {
        val uri=runCatching { URI(raw) }.getOrNull() ?:return null
        val query=uri.rawQuery.orEmpty()
        val imdb=Regex("""(?:^|&)imdb=(tt\d{5,12})(?:&|$)""")
            .find(query)?.groupValues?.get(1)
        return imdb
    }
    private fun slug(name:String):String =
        name.lowercase(java.util.Locale.ROOT)
            .replace(Regex("""[^a-z0-9]+"""),"-")
            .trim('-').take(65).ifBlank{"title"}
    private fun entry(node:JsonNode,isSeries:Boolean):DexterCard? {
        if(!node.isObject)return null
        val id=node.value("moviedb_id")
        if(!Regex("""\d{1,9}""").matches(id))return null
        val imdb=node.value("imdb_id").ifBlank{node.value("id")}
        if(!Regex("""tt\d{5,12}""").matches(imdb))return null
        val name=node.value("name").takeIf { it.length in 2..170 }?:return null
        val poster=node.value("poster").takeIf {
            DexterParser.externalUrl(it,BASE)!=null
        }
        val prefix=if(isSeries)"/show/" else "/watch/"
        val url=DexterParser.BASE+prefix+id+"-"+slug(name)+"?imdb="+imdb
        return DexterCard(name,url,poster,isSeries)
    }
    fun entries(body:String,isSeries:Boolean):List<DexterCard> {
        val root=runCatching{mapper.readTree(body)}.getOrNull()?:return emptyList()
        val metas=root.path("metas")
        if(!metas.isArray)return emptyList()
        return metas.mapNotNull { entry(it,isSeries) }.distinctBy { it.url }
    }
    fun searchImdbIds(body:String):List<String> {
        val root=runCatching { mapper.readTree(body) }.getOrNull()
            ?:return emptyList()
        val metas=root.path("metas")
        if(!metas.isArray)return emptyList()
        return metas.mapNotNull {
            it.value("imdb_id").ifBlank { it.value("id") }
                .takeIf { id -> Regex("""tt\d{5,12}""").matches(id) }
        }.distinct()
    }

    fun entryFromMeta(body:String,isSeries:Boolean):DexterCard? {
        val root=runCatching{mapper.readTree(body)}.getOrNull()?:return null
        return entry(root.path("meta"),isSeries)
    }
    fun details(body:String,isSeries:Boolean,url:String):DexterMetadata? {
        val root=runCatching{mapper.readTree(body)}.getOrNull()?:return null
        val meta=root.path("meta")
        if(!meta.isObject)return null
        val title=meta.value("name").takeIf { it.isNotBlank() } ?:return null
        val poster=meta.value("poster").takeIf { DexterParser.externalUrl(it,BASE)!=null }
        val plot=meta.value("description").takeIf { it.isNotBlank() }
        val episodes=linkedMapOf<String,DexterEpisode>()
        if(isSeries) {
            val base=url.substringBefore('?').trimEnd('/')
            val videos=meta.path("videos")
            if(videos.isArray)for(video in videos) {
                val season=video.path("season").takeIf { it.canConvertToInt() }
                    ?.asInt() ?:continue
                val number=video.path("episode").takeIf { it.canConvertToInt() }
                    ?.asInt() ?:continue
                if(season !in 0..99 || number !in 1..1500)continue
                val actual=base.replace("/show/","/watch/show/")+
                    "/"+season+"/"+number
                val name=video.value("title").ifBlank{video.value("name")}
                    .takeIf { it.isNotBlank() }
                episodes.putIfAbsent(actual,DexterEpisode(actual,season,number,name))
            }
        }
        return DexterMetadata(title,poster,plot,
            episodes.values.sortedWith(compareBy<DexterEpisode>{it.season}.thenBy{it.number}))
    }
}
