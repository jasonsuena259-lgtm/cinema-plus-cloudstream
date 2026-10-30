package com.myseed

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Site-specific HTML parser for the 2026 MySeed Cam layout.
 * Keep legacy MySeedParser for the older .baby site and its fixtures.
 */
internal object MySeedCamParser {
    const val BASE = "https://myseed.cam"
    val sections = listOf(
        "/all-content" to "المضاف حديثاً",
        "/films" to "جميع الأفلام",
        "/seriestv" to "جميع المسلسلات",
        "/episodestv" to "جميع الحلقات",
        "/category/افلام-اجنبي" to "أفلام أجنبية",
        "/category/افلام-اجنبية-2025" to "أفلام أجنبية 2025",
        "/category/افلام-اجنبية-2026" to "أفلام أجنبية 2026",
        "/category/افلام-اسيوي" to "أفلام آسيوية",
        "/category/افلام-انمي" to "أفلام أنمي",
        "/category/افلام-تركي" to "أفلام تركية",
        "/category/افلام-عربي" to "أفلام عربية",
        "/category/افلام-كرتون" to "أفلام كرتون",
        "/category/افلام-مدبلجة" to "أفلام مدبلجة",
        "/category/افلام-هندي" to "أفلام هندية",
        "/category/فيلم-تلفزيوني" to "أفلام تلفزيونية",
        "/category/مسلسلات-أجنبي" to "مسلسلات أجنبية",
        "/category/مسلسلات-أسيوي" to "مسلسلات آسيوية",
        "/category/مسلسلات-اجنبي" to "مسلسلات أجنبي",
        "/category/مسلسلات-اسيوية" to "مسلسلات آسيوية متنوعة",
        "/category/مسلسلات-الأنميات" to "مسلسلات الأنمي",
        "/category/مسلسلات-انمي" to "مسلسلات أنمي أخرى",
        "/category/مسلسلات-تركي" to "مسلسلات تركية",
        "/category/مسلسلات-جديدة" to "مسلسلات جديدة",
        "/category/مسلسلات-عربية" to "مسلسلات عربية",
        "/category/مسلسلات-هندي" to "مسلسلات هندية",
        "/category/برامج-تلفزيونية" to "برامج تلفزيونية"
    )
    private val excludedSlugs = setOf(
        "films", "seriestv", "episodestv", "search", "category",
        "watch", "download", "all-content", "login", "register", "main"
    )

    fun siteUrl(raw: String): String? {
        if (raw.isBlank()) return null
        val url = BASE.toHttpUrlOrNull()!!.resolve(raw.replace("&amp;", "&")) ?: return null
        if (url.host != "myseed.cam" || url.username.isNotEmpty() ||
            url.password.isNotEmpty() || url.port != 443 || url.scheme != "https") return null
        return url.toString()
    }

    /** Resolves a content slug, excluding category/search and foreign links. */
    fun slug(raw: String): String? {
        val url = siteUrl(raw)?.toHttpUrlOrNull() ?: return null
        val parts = url.pathSegments.filter { it.isNotBlank() }
        val value = when {
            parts.size == 1 && parts[0] !in excludedSlugs -> parts[0]
            parts.size == 2 && parts[0] == "download" -> parts[1]
            parts.size == 3 && parts[0] == "watch" && parts[2] == "servers" -> parts[1]
            else -> null
        } ?: return null
        return value.takeIf { it.matches(Regex("[a-zA-Z0-9_-]+")) }
    }

    fun watchUrl(raw: String) = slug(raw)?.let { BASE + "/watch/" + it + "/servers" }
    fun downloadUrl(raw: String) = slug(raw)?.let { BASE + "/download/" + it }

    fun pageUrl(path: String, page: Int): String? {
        val u = siteUrl(path)?.toHttpUrlOrNull() ?: return null
        if (page <= 1) return u.toString()
        return if (u.encodedPath.startsWith("/category/") || u.encodedPath == "/search")
            u.newBuilder().setQueryParameter("page", page.toString()).build().toString()
        else u.newBuilder().encodedPath(u.encodedPath.trimEnd('/') + "/page/" + page).build().toString()
    }

    fun cards(doc: Document): List<SeedItem> {
        return doc.select(".tt-archive-grid article.tt-card > a.tt-card-link[href], .fhd-cinema-grid article.tt-card > a.tt-card-link[href]")
            .mapNotNull { a ->
                val link = siteUrl(a.absUrl("href")) ?: return@mapNotNull null
                val path = link.toHttpUrlOrNull()?.encodedPath.orEmpty()
                if (path.startsWith("/watch/") || path.startsWith("/download/")) return@mapNotNull null
                if (!path.startsWith("/series/") && slug(link) == null) return@mapNotNull null
                val title = a.selectFirst(".tt-card-body h3, h3")?.text()?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val imageNode = a.selectFirst(".tt-thumb img[src], img[data-src], img[src]")
                val poster = if (imageNode == null) null else {
                    val img = imageNode.attr("data-src").ifBlank { imageNode.attr("src") }
                    doc.location().toHttpUrlOrNull()?.resolve(img)?.toString()
                }
                val category = a.selectFirst(".tt-card-foot span, .tt-badge.tt-genre")?.text().orEmpty()
                val series = path.startsWith("/series/") || category.contains("مسلسل") ||
                    category.contains("حلقة") || title.contains("الحلقة") || title.startsWith("مسلسل") ||
                    title.startsWith("أنمي")
                val anime = title.contains("أنمي") || title.contains("انمي") ||
                    category.contains("أنمي") || category.contains("انمي")
                SeedItem(title, link, poster, series, anime)
            }.distinctBy { it.url }
    }

    fun next(doc: Document, page: Int): Boolean =
        doc.select(".tt-pagination a[href]").any { a ->
            a.text().trim().toIntOrNull() == page + 1 ||
                a.absUrl("href").matches(Regex(""".*(?:/page/|[?&]page=)""" + (page + 1) + """(?:[&#/]|$).*"""))
        }

    fun number(text: String): Int? =
        Regex("[0-9٠-٩]+").find(text)?.value
            ?.map { c -> if (c in '٠'..'٩') '0' + (c - '٠') else c }
            ?.joinToString("")?.toIntOrNull()

    fun episodes(doc: Document): List<SeedEpisode> {
        val grouped = doc.select("section.tt-season").flatMap { seasonNode ->
            val season = number(seasonNode.selectFirst(".tt-season-head h2, h2")?.text().orEmpty()) ?: 1
            seasonNode.select("a.tt-episode-card[href]").mapNotNull { a ->
                val link = siteUrl(a.absUrl("href")) ?: return@mapNotNull null
                if (slug(link) == null) return@mapNotNull null
                val label = a.selectFirst(".tt-episode-copy strong")?.text()?.trim()
                    .orEmpty().ifBlank { a.text().trim() }
                val ep = number(a.selectFirst(".tt-episode-copy small")?.text().orEmpty())
                    ?: Regex("""-n(\d+)(?:/|$)""").find(link)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: number(label.substringAfterLast("الحلقة", ""))
                    ?: return@mapNotNull null
                SeedEpisode(label.ifBlank { "الحلقة " + ep }, link, season, ep)
            }
        }
        if (grouped.isNotEmpty()) return grouped.distinctBy { it.url }
            .sortedWith(compareBy<SeedEpisode> { it.season }.thenBy { it.number })
        // Individual episode page: only follow explicit season/episode list, not recommendations.
        val listed = doc.select(".myseed-single-episodes a[href], .tt-episodes-panel a.tt-episode-card[href]")
            .mapNotNull { a ->
                val link = siteUrl(a.absUrl("href")) ?: return@mapNotNull null
                val n = number(a.text().substringAfter("الحلقة", "")) ?: return@mapNotNull null
                SeedEpisode(a.text().trim().ifBlank { "الحلقة " + n }, link, 1, n)
            }
        return listed.distinctBy { it.url }.sortedWith(compareBy({ it.season }, { it.number }))
    }

    private fun hostSource(url: String): String {
        val host = url.toHttpUrlOrNull()?.host?.lowercase().orEmpty()
        return when {
            host.contains("mixdrop") -> "Mixdrop"
            host.contains("vidmoly") -> "Vidmoly"
            host.contains("vidspeed") -> "Vidspeed"
            host.startsWith("voe.") -> "Voe"
            host.contains("streamhls") -> "Streamhls"
            host.contains("vidara") -> "Vidara"
            host.contains("upzur") -> "Upzur"
            else -> host.removePrefix("www.").substringBefore('.').ifBlank { "Server" }
        }
    }

    private fun externalUrl(base: String, raw: String): String? {
        if (raw.isBlank()) return null
        val url = base.toHttpUrlOrNull()?.resolve(raw.trim()) ?: return null
        if (url.scheme !in listOf("http", "https") || url.host.isBlank() ||
            url.username.isNotBlank() || url.password.isNotBlank()) return null
        if (url.host == "myseed.cam") return null
        return url.toString()
    }

    /** The new site exposes each real embed address on the button itself. */
    fun watchSources(doc: Document): List<SeedSource> = doc.select("button.tt-watch-server[data-src]")
        .mapNotNull { button ->
            val url = externalUrl(doc.location(), button.attr("data-src")) ?: return@mapNotNull null
            SeedSource(hostSource(url), url)
        }.distinctBy { it.url }

    /** Keep real download mirrors, but don't mislabel a host landing page as a video. */
    fun downloadSources(doc: Document): List<SeedSource> = doc.select(".tt-download-list > div a[href]")
        .mapNotNull { a ->
            val url = externalUrl(doc.location(), a.absUrl("href")) ?: return@mapNotNull null
            SeedSource(hostSource(url), url)
        }.distinctBy { it.url }

    fun poster(doc: Document): String? {
        val raw = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst(".myseed-single-poster img, .tt-series-cover img, .tt-download-cover img")
                ?.attr("src")?.takeIf { it.isNotBlank() }
        return raw?.let { doc.location().toHttpUrlOrNull()?.resolve(it)?.toString() }
    }

    fun story(doc: Document): String? {
        val element = doc.selectFirst(".myseed-single-hero-story, .myseed-single-story, meta[name=description]")
        val value = if (element == null) null
            else if (element.tagName() == "meta") element.attr("content") else element.text()
        return value?.takeIf { it.isNotBlank() }
    }

    fun tags(doc: Document): List<String> = doc.select(
        ".myseed-single-genres a, .myseed-single-info a[href*=/category/], .tt-series-info a[href*=/category/]"
    ).map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
}
