package com.cinelite

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

data class LiteCard(val title: String, val url: String, val poster: String?, val type: String)
data class LiteEpisode(val title: String, val url: String, val season: Int?, val episode: Int?)
data class LiteSource(val name: String, val url: String, val direct: Boolean)
data class LiteIdentity(val id: Int, val type: String, val title: String, val poster: String?)

object CineLiteParser {
    const val BASE = "https://cinelite.xyz"

    fun siteUrl(raw: String, base: String = BASE): String? {
        val target = base.toHttpUrlOrNull()?.resolve(raw) ?: return null
        if (target.scheme != "https" || target.host !in setOf("cinelite.xyz", "www.cinelite.xyz")
            || target.username.isNotEmpty() || target.password.isNotEmpty()) return null
        return target.toString()
    }

    fun absolute(raw: String, base: String): String? {
        val url = base.toHttpUrlOrNull()?.resolve(raw.trim()) ?: return null
        if (url.scheme !in listOf("https", "http") || url.username.isNotEmpty()
            || url.password.isNotEmpty() || url.host == "localhost") return null
        return url.toString()
    }

    fun doc(html: String, url: String = BASE): Document = Jsoup.parse(html, url)

    private fun attr(e: Element, vararg keys: String): String? =
        keys.asSequence().map { e.attr(it).trim() }.firstOrNull { it.isNotBlank() }

    private fun mediaType(url: String): String? {
        val path = url.toHttpUrlOrNull()?.encodedPath.orEmpty().lowercase()
        return when {
            Regex("/(?:watch/)?(?:movies?|films?)(?:/|$)").containsMatchIn(path) -> "movie"
            Regex("/(?:watch/)?(?:tv|shows?|series|anime)(?:/|$)").containsMatchIn(path) ->
                if ("/anime" in path) "anime" else "tv"
            else -> null
        }
    }

    fun cardUrl(id: Int, type: String, title: String, poster: String?): String =
        BASE.toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("cineliteId", id.toString())
            .addQueryParameter("cineliteType", type)
            .addQueryParameter("cineliteTitle", title)
            .apply { if (!poster.isNullOrBlank()) addQueryParameter("cinelitePoster", poster) }
            .build().toString()

    fun identity(raw: String): LiteIdentity? {
        val u = siteUrl(raw)?.toHttpUrlOrNull() ?: return null
        if (u.encodedPath != "/") return null
        val id = u.queryParameter("cineliteId")?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val type = u.queryParameter("cineliteType")?.takeIf { it in setOf("movie","tv","anime") } ?: return null
        val title = u.queryParameter("cineliteTitle")?.takeIf { it.isNotBlank() } ?: return null
        return LiteIdentity(id, type, title, u.queryParameter("cinelitePoster"))
    }

    /** These routes are specified by CineLite's public embed documentation. */
    fun embedMovie(id: Int): String? = id.takeIf { it > 0 }?.let { "https://embed.cinelite.xyz/" + it }
    fun embedEpisode(id: Int, season: Int, episode: Int): String? =
        if (id > 0 && season >= 0 && episode > 0)
            "https://embed.cinelite.xyz/" + id + "/" + season + "/" + episode
        else null

    fun cards(html: String, base: String = BASE): List<LiteCard> {
        val document = doc(html, base)
        // CineLite's React components do not render <a> tags for media cards.
        // The WebView assigns attributes only from real React memoizedProps.item values.
        val annotated = document.select("[data-cinelite-id][data-cinelite-type]").mapNotNull { e ->
            val id = e.attr("data-cinelite-id").toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            val type = e.attr("data-cinelite-type").lowercase()
                .takeIf { it in setOf("movie", "tv", "anime") } ?: return@mapNotNull null
            val title = e.selectFirst("h3,h4")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val rawPoster = e.selectFirst("img[src],img[data-src]")?.let { attr(it, "src","data-src") }
            val poster = rawPoster?.let { absolute(it, base) }
            LiteCard(title, cardUrl(id,type,title,poster), poster, type)
        }
        val anchors = document.select("a[href], a[data-href]").mapNotNull { a ->
            val link = siteUrl(attr(a, "href", "data-href") ?: return@mapNotNull null, base)
                ?: return@mapNotNull null
            val type = mediaType(link) ?: return@mapNotNull null
            val image = a.selectFirst("img") ?: a.parent()?.selectFirst("img")
            val title = attr(a, "title", "aria-label")
                ?: a.selectFirst("h2,h3,h4,[class*=title],img[alt]")?.let {
                    attr(it, "alt", "title") ?: it.text().trim()
                }
                ?: image?.attr("alt")?.trim()
            if (title.isNullOrBlank()) return@mapNotNull null
            val poster = image?.let { imageElement ->
                attr(imageElement, "data-src", "data-original", "src")
            }?.let { raw ->
                if (raw.startsWith("/t/p/")) "https://image.tmdb.org" + raw
                else absolute(raw, base)
            }
            LiteCard(title, link, poster, type)
        }
        return (annotated + anchors).distinctBy { it.url }
    }

    fun title(html: String, base: String): String? {
        val document = doc(html, base)
        return document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.trim()?.takeIf { it.isNotBlank() }
    }

    fun poster(html: String, base: String): String? {
        val document = doc(html, base)
        return document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.let { absolute(it, base) }
    }

    fun description(html: String): String? =
        doc(html).selectFirst("meta[name=description],meta[property=og:description]")
            ?.attr("content")?.takeIf { it.isNotBlank() }

    fun episodes(html: String, base: String): List<LiteEpisode> {
        val document = doc(html, base)
        return document.select("a[href], a[data-href], button[data-url]").mapNotNull { e ->
            val number = attr(e, "data-episode", "data-episode-number")?.toIntOrNull()
                ?: Regex("(?i)(?:episode|ep|الحلقة)[\\s#:-]*(\\d+)").find(e.text())
                    ?.groupValues?.get(1)?.toIntOrNull()
                ?: return@mapNotNull null
            if (number < 1) return@mapNotNull null
            val url = siteUrl(attr(e, "href", "data-href", "data-url")
                ?: return@mapNotNull null, base) ?: return@mapNotNull null
            val season = attr(e, "data-season", "data-season-number")?.toIntOrNull()
                ?: Regex("(?i)(?:season|s|الموسم)[\\s#:-]*(\\d+)").find(url)
                    ?.groupValues?.get(1)?.toIntOrNull()
            LiteEpisode(e.text().trim().ifBlank { "Episode " + number }, url, season, number)
        }.distinctBy { Triple(it.url, it.season, it.episode) }
    }

    fun sources(html: String, base: String): List<LiteSource> {
        val d = doc(html, base)
        val result = mutableListOf<LiteSource>()
        fun insert(label: String, raw: String) {
            val url = absolute(raw, base) ?: return
            val parsed = url.toHttpUrlOrNull() ?: return
            val path = parsed.encodedPath.lowercase()
            val direct = Regex("\\.(?:m3u8|mp4)(?:$|\\?)").containsMatchIn(url.lowercase())
            val embed = "embed" in parsed.host || "player" in parsed.host ||
                Regex("/(?:embed|player|e)/(?:[^/]+)").containsMatchIn(path)
            if (!direct && !embed) return
            result += LiteSource(label.ifBlank { parsed.host }, url, direct)
        }
        d.select("iframe[src], iframe[data-src], video[src], video source[src], source[src], [data-server-url], [data-embed-url]").forEach { e ->
            val link = attr(e, "src", "data-src", "data-server-url", "data-embed-url") ?: return@forEach
            insert(attr(e, "title", "data-name", "aria-label") ?: e.tagName(), link)
        }
        // Some player frontends store literal HLS/MP4 URLs in inline source configurations.
        val pattern = Regex("""https?://[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
        d.select("script:not([src])").forEach { script ->
            pattern.findAll(script.data().replace("\\/", "/")).forEach { m -> insert("Direct", m.value) }
        }
        return result.distinctBy { it.url }
    }
}
