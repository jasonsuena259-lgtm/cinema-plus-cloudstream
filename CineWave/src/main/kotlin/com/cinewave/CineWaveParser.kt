package com.cinewave

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class WaveId(val type: String, val id: Int, val season: Int? = null, val episode: Int? = null) {
    fun url() = "${CineWaveParser.BASE}/${if(type=="movie") "movies" else "tv"}/$id" +
        (if(season != null && episode != null) "?season=$season&episode=$episode" else "")
}
data class WaveSource(val name: String, val url: String)
object CineWaveParser {
    const val BASE = "https://cinewave.org.lk"
    val mapper = jacksonObjectMapper()
    fun id(raw: String): WaveId? {
        val u = BASE.toHttpUrlOrNull()?.resolve(raw) ?: return null
        if(u.host != "cinewave.org.lk" || u.username.isNotEmpty() || u.password.isNotEmpty()) return null
        val p=u.pathSegments.filter { it.isNotEmpty() }
        if(p.size!=2 || p[0] !in listOf("movies","tv")) return null
        val n=p[1].toIntOrNull()?.takeIf { it>0 } ?: return null
        val s=u.queryParameter("season")?.toIntOrNull()
        val e=u.queryParameter("episode")?.toIntOrNull()
        if((s==null)!=(e==null) || (s!=null && (s<0 || e!!<1))) return null
        return WaveId(if(p[0]=="movies") "movie" else "tv",n,s,e)
    }
    fun json(raw: String): JsonNode = mapper.readTree(raw) ?: error("Empty JSON")
    fun items(root: JsonNode, key: String): List<JsonNode> = root.path(key).filter {
        it.path("id").asInt()>0 && it.path("title").asText().isNotBlank() &&
        it.path("playerUrl").asText().toHttpUrlOrNull()!=null
    }.distinctBy { it.path("mediaType").asText() to it.path("id").asInt() }
    fun episodes(item: JsonNode): List<WaveId> = item.path("seasons").flatMap { s ->
        val number=s.path("season_number").asInt(-1)
        val count=s.path("episode_count").asInt(0)
        if(number<0 || count !in 1..1000) emptyList() else (1..count).map { WaveId("tv",item.path("id").asInt(),number,it) }
    }.distinct()
    // Parse only the site's literal URL builders. Never execute downloaded JavaScript.
    fun sources(script: String, id: WaveId): List<WaveSource> {
        val pattern=Regex("""key:"[^"]+",label:"([^"]+)",buildMovieUrl:(\w+)=>`([^`]+)`,buildTvUrl:(\w+|\([^)]*\))=>`([^`]+)`""")
        return pattern.findAll(script).mapNotNull { m ->
            val isTv=id.type=="tv"
            val params=(if(isTv) m.groupValues[4] else m.groupValues[2]).trim('(',')').split(',')
            if(isTv && (params.size!=3 || id.season==null || id.episode==null)) return@mapNotNull null
            var template=if(isTv) m.groupValues[5] else m.groupValues[3]
            val values=listOf(id.id,id.season ?: 1,id.episode ?: 1)
            params.forEachIndexed { i,p -> template=template.replace("\${"+p+"}",values[i].toString()) }
            if(template.contains("\${")) return@mapNotNull null
            val u=template.toHttpUrlOrNull() ?: return@mapNotNull null
            if(u.scheme!="https" || (if(isTv) "tv" else "movie") !in u.pathSegments) return@mapNotNull null
            WaveSource(m.groupValues[1],u.toString())
        }.distinctBy { it.url }.toList()
    }
}
