package com.tuktuk

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.base64Decode

data class TukItem(val title: String, val url: String, val poster: String?, val series: Boolean)
data class TukEpisode(val url: String, val season: Int, val number: Int, val title: String, val poster: String?)
data class TukSource(val name: String, val url: String, val quality: Int = 0)
object TukTukParser {
    const val BASE = "https://tuktukcinemas.com"
    fun safeUrl(raw: String, base: String = BASE): String? = base.toHttpUrlOrNull()?.resolve(raw)?.takeIf { it.scheme in listOf("http", "https") }?.toString()
    fun contentUrl(raw: String): String? {
        var value=raw.trim().replace("&amp;", "&")
        if (value.startsWith("https%3A",true) || value.startsWith("http%3A",true)) {
            value=runCatching { java.net.URLDecoder.decode(value,"UTF-8") }.getOrNull() ?: return null
        }
        val parsed=BASE.toHttpUrlOrNull()?.resolve(value) ?: return null
        if(parsed.host.trimEnd('.') !in setOf("tuktukcinemas.com","www.tuktukcinemas.com") || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        if(parsed.port != 80 && parsed.port != 443) return null
        return parsed.newBuilder().scheme("https").host("tuktukcinemas.com").port(443).build().toString()
    }
    fun poster(element: Element): String? {
        val img = element.selectFirst("img") ?: return null
        return listOf("data-src", "src").map { img.absUrl(it) }.firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
    }
    fun cards(doc: Document): List<TukItem> = doc.select(".Block--Item > a[href]").mapNotNull { a ->
        val url = contentUrl(a.absUrl("href")) ?: return@mapNotNull null
        val title = a.attr("title").ifBlank { a.selectFirst(".title, h2, h3")?.text().orEmpty() }.trim()
        if (title.isBlank()) return@mapNotNull null
        TukItem(title, url, poster(a), url.contains("/series/") || Regex("(?i)-s\\d+-e\\d+").containsMatchIn(url) || title.startsWith("مسلسل") || title.startsWith("انمي"))
    }.distinctBy { it.url }
    fun schema(doc: Document): JsonNode? = doc.select("script[type=application/ld+json]").asSequence().flatMap { s ->
        val node = runCatching { jacksonObjectMapper().readTree(s.data()) }.getOrNull()
        if (node == null) emptySequence() else if (node.has("@graph")) node.path("@graph").asSequence() else sequenceOf(node)
    }.firstOrNull { it.path("@type").asText() in listOf("Movie", "TVEpisode", "TVSeries", "TVSeason") }
    fun seasons(doc: Document): List<Pair<String, Int>> = doc.select(".allseasonss .Block--Item a[href], .SeriesPosterGrid .Block--Item a[href]").mapNotNull { a ->
        val title = a.attr("title").ifBlank { a.text() }
        val n = Regex("الموسم\\s*(\\d+)").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        contentUrl(a.absUrl("href"))?.let { it to n }
    }.distinctBy { it.first }
    fun episodes(doc: Document, season: Int): List<TukEpisode> = doc.select("a.SeriesEpisodeCard[href], .episodes--list--side a[href]").mapNotNull { a ->
        val url = contentUrl(a.absUrl("href")) ?: return@mapNotNull null
        val match = Regex("(?i)-s(\\d+)-e(\\d+)").find(url)
        val n = match?.groupValues?.get(2)?.toIntOrNull() ?: Regex("الحلقة\\s*(\\d+)").find(a.attr("title"))?.groupValues?.get(1)?.toIntOrNull() ?: a.selectFirst("em")?.text()?.toIntOrNull() ?: return@mapNotNull null
        TukEpisode(url, match?.groupValues?.get(1)?.toIntOrNull() ?: season, n, a.attr("title").ifBlank { "الحلقة $n" }, poster(a))
    }.distinctBy { it.url }
    fun sources(doc: Document): List<TukSource> {
        val list = doc.select(".watch--servers--list .server--item[data-link]").mapNotNull { e ->
            val decoded = runCatching { base64Decode(e.attr("data-link").substringBefore("0REL0Y&").reversed()) }.getOrNull()
            decoded?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { safeUrl(it) }?.let { TukSource(e.text(), it) }
        }.toMutableList()
        doc.selectFirst("#main-video-frame[data-crypt]")?.let { e: Element ->
            runCatching { base64Decode(e.attr("data-crypt")) }.getOrNull()?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { safeUrl(it) }?.let { list.add(TukSource("TukTuk Vip", it)) }
        }
        return list.distinctBy { it.url }
    }
    fun mirrors(json: String, base: String): List<TukSource> {
        val root = runCatching { jacksonObjectMapper().readTree(json) }.getOrNull() ?: return emptyList()
        val streams = root.path("props").path("streams")
        if (streams.path("status").asText() != "success") return emptyList()
        return streams.path("data").flatMap { row ->
            val quality = row.path("resolution").asText().substringAfter('x', "").toIntOrNull() ?: 0
            row.path("mirrors").mapNotNull { mirror -> safeUrl(mirror.path("link").asText(), base)?.let { TukSource(mirror.path("driver").asText().ifBlank { "TukTuk Vip" }, it, quality) } }
        }.distinctBy { it.url }
    }
    fun media(script: String, base: String): List<String> = Regex("""["']([^"'\s]+(?:\.m3u8|master\.txt)(?:\?[^"'\s]*)?)["']""").findAll(script.replace("\\/", "/")).mapNotNull { safeUrl(it.groupValues[1], base) }.distinct().toList()
}
