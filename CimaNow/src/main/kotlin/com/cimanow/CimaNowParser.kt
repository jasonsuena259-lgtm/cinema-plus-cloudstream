package com.cimanow

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal data class CimaCard(
    val title: String, val url: String, val poster: String?,
    val series: Boolean, val year: Int?
)
internal data class CimaEpisode(
    val title: String, val url: String, val season: Int, val number: Int
)
internal data class CimaSection(val title: String, val url: String)

/** DOM rules derived from the public (non-VIP) CimaNow extension, with null-safe fallbacks. */
internal object CimaNowParser {
    private val episodeNumber = Regex("(?:الحلقة|حلقه|episode|ep)[^0-9]{0,12}([0-9]+)", RegexOption.IGNORE_CASE)
    private val seasonNumber = Regex("(?:الموسم|season|ج)(?:[^0-9]{0,8})([0-9]+)", RegexOption.IGNORE_CASE)

    private fun sameSite(raw: String, doc: Document): String? {
        val url = doc.location().toHttpUrlOrNull()?.resolve(raw) ?: return null
        val host = doc.location().toHttpUrlOrNull()?.host ?: return null
        return url.takeIf {
            it.scheme == "https" && it.host == host && it.username.isEmpty() && it.password.isEmpty()
        }?.toString()
    }
    private fun poster(element: Element): String? {
        val img = element.selectFirst("img") ?: return null
        for (attr in listOf("data-src", "data-lazy-src", "src")) {
            val value = img.absUrl(attr)
            if (value.startsWith("https://") && !value.contains("data:image")) return value
        }
        return null
    }
    fun cards(doc: Document): List<CimaCard> = doc.select("section article a[href],article > a[href]")
        .mapNotNull { a ->
            val url = sameSite(a.absUrl("href"), doc) ?: return@mapNotNull null
            if (url.contains("/category/") || url.contains("/expired-download/") ||
                url.contains("/watching") || url.contains("/page/")) return@mapNotNull null
            val title = a.selectFirst("li[aria-label=title]")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: a.selectFirst("img[alt]")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val series = url.contains("مسلسل") || url.contains("موسم") ||
                title.contains("مسلسل") || title.contains("الموسم") ||
                a.selectFirst("li[aria-label=episode]") != null
            val year = a.selectFirst("li[aria-label=year]")?.text()?.trim()?.toIntOrNull()
            CimaCard(title, url, poster(a), series, year)
        }.distinctBy { it.url }

    fun groups(doc: Document): List<Pair<String, List<CimaCard>>> {
        val result = doc.select("section").mapNotNull { section ->
            val name = section.selectFirst("h2,h3,span")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val page = org.jsoup.Jsoup.parse(section.outerHtml(), doc.location())
            val items = cards(page)
            if (items.isEmpty()) null else name to items
        }
        return result.distinctBy { it.first }
    }

    fun sections(doc: Document): List<CimaSection> = doc.select("header a[href],nav a[href]")
        .mapNotNull { a ->
            val url = sameSite(a.absUrl("href"), doc) ?: return@mapNotNull null
            if (!url.contains("/category/") && !url.contains("/الاكثر-مشاهدة/")) return@mapNotNull null
            val title = a.attr("aria-label").ifBlank { a.text() }.trim()
                .takeIf { it.length in 2..65 } ?: return@mapNotNull null
            CimaSection(title, url)
        }.distinctBy { it.url }

    fun episodes(doc: Document, defaultSeason: Int = 1): List<CimaEpisode> =
        doc.select("ul#eps li a[href],ul#episodes li a[href]").mapNotNull { a ->
            val url = sameSite(a.absUrl("href"), doc) ?: return@mapNotNull null
            val raw = a.text() + " " + a.attr("href")
            val number = a.selectFirst("em")?.text()?.trim()?.toIntOrNull()
                ?: episodeNumber.find(raw)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            val season = seasonNumber.find(raw)?.groupValues?.get(1)?.toIntOrNull()
                ?: defaultSeason
            CimaEpisode(a.text().trim().ifBlank { "الحلقة $number" }, url, season, number)
        }.distinctBy { it.url }.sortedWith(compareBy({ it.season }, { it.number }))

    fun otherSeasons(doc: Document): List<Pair<Int, String>> =
        doc.select("#seasons a[href],.seasons a[href],ul[aria-label=seasons] a[href]")
            .mapNotNull { a ->
                val u = sameSite(a.absUrl("href"), doc) ?: return@mapNotNull null
                val sn = seasonNumber.find(a.text())?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("[0-9]+").find(a.text())?.value?.toIntOrNull() ?: return@mapNotNull null
                sn to u
            }.distinctBy { it.second }

    fun sources(doc: Document): List<String> {
        val candidates = doc.select(
            "ul#download [aria-label=quality] a[href],ul#download a[href]," +
            "iframe[src],iframe[data-src],video[src],video source[src]," +
            "source[src],a[data-server][href],a[data-link][href]"
        ).mapNotNull { element ->
            val raw = listOf("data-src", "src", "href").firstNotNullOfOrNull {
                element.absUrl(it).takeIf { value -> value.isNotBlank() }
            } ?: return@mapNotNull null
            val u = raw.toHttpUrlOrNull() ?: return@mapNotNull null
            if (u.scheme != "https" || u.username.isNotEmpty() || u.password.isNotEmpty()) return@mapNotNull null
            val host = u.host.lowercase()
            if (listOf("youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com").any {
                    host == it || host.endsWith(".$it")
                }) return@mapNotNull null
            if (u.encodedPath.endsWith("/watching", true)) return@mapNotNull null
            u.toString()
        }
        return candidates.distinct()
    }
}
