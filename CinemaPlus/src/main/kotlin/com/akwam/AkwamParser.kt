package com.akwam

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class AkwamItem(val title: String, val url: String, val poster: String?, val series: Boolean)
data class AkwamEpisode(val url: String, val season: Int, val number: Int)
object AkwamParser {
    fun cards(html: String, base: String): List<AkwamItem> = Jsoup.parse(html, base).select("a.show-card[href]").mapNotNull { a ->
        val url = a.absUrl("href").toHttpUrlOrNull()?.toString() ?: return@mapNotNull null
        val kind = when { url.contains("/film/") -> false; url.contains("/series/") || url.contains("/season/") || url.contains("/episode/") -> true; else -> return@mapNotNull null }
        val title = a.selectFirst(".title")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        AkwamItem(title, url, a.selectFirst("img")?.absUrl("src")?.takeIf { it.isNotBlank() }, kind)
    }.distinctBy { it.url }
    fun document(html: String, base: String): Document = Jsoup.parse(html, base)
    fun seasons(doc: Document): List<Pair<String, Int>> = doc.select("a.epss[href*=/season/]").mapNotNull { a ->
        val number = a.selectFirst(".fs-2")?.text()?.trim()?.toIntOrNull() ?: return@mapNotNull null
        val url = a.absUrl("href").toHttpUrlOrNull()?.toString() ?: return@mapNotNull null; if (url.isBlank()) null else url to number
    }.distinctBy { it.first }
    fun episodes(doc: Document, season: Int): List<AkwamEpisode> = doc.select("a.epss[href*=/episode/]").mapNotNull { a ->
        val n = a.selectFirst(".fs-2")?.text()?.trim()?.toIntOrNull() ?: return@mapNotNull null
        val url = a.absUrl("href").toHttpUrlOrNull()?.toString() ?: return@mapNotNull null; if (url.isBlank()) null else AkwamEpisode(url, season, n)
    }.distinctBy { it.url }
    fun hasNext(doc: Document, page: Int): Boolean {
        if (doc.select(".pagination a[href]").any { it.absUrl("href").contains("page=${page + 1}") }) return true
        return doc.select(".pagination [onclick]").any {
            Regex("""updateQuery\(\s*['\"]page['\"]\s*,\s*(\d+)\s*\)""").find(it.attr("onclick"))?.groupValues?.get(1)?.toIntOrNull() == page + 1
        }
    }
    fun videoParameters(html: String): Pair<String, String>? {
        val key = Regex("""const\s+watchResolveKey\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: return null
        val csrf = Regex("""const\s+csrfToken\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: return null
        return key to csrf
    }
}
