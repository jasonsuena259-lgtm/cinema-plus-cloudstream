package com.cinemaplus

import org.jsoup.Jsoup
import java.net.URI

object CinemaPlusParser {
    fun cards(html: String, base: String): List<CinemaItem> {
        val document = Jsoup.parse(html, base)
        val cards = document.select(".card-wrapper").mapNotNull { card ->
            val a = card.selectFirst("a[href]") ?: return@mapNotNull null
            val url = a.absUrl("href")
            val series = Regex("/series/\\d+\\.html(?:$|[?#])").containsMatchIn(url)
            if (!series && movieId(url) == null) return@mapNotNull null
            val title = card.attr("data-title").ifBlank { card.selectFirst(".card-details-title")?.text().orEmpty() }.trim()
            if (title.isEmpty()) return@mapNotNull null
            val img = card.selectFirst("img")
            val poster = img?.absUrl("data-src")?.ifBlank { img.absUrl("src") }?.ifBlank { null }
            CinemaItem(title, url, poster, series, card.attr("data-year").toIntOrNull() ?: card.selectFirst(".year")?.text()?.toIntOrNull(), card.attr("data-genre").split('|').filter { it.isNotBlank() }, card.closest(".section")?.selectFirst("h3.section-title")?.text()?.takeIf { it.isNotBlank() }?.let { setOf(it) } ?: emptySet())
        }
        val sliders = document.select(".slider-section .movie-card").flatMap { card ->
            val img = card.selectFirst("img.movie-poster")
            val title = img?.attr("alt").orEmpty().trim()
            card.select("a[href]").mapNotNull { a ->
                val url = a.absUrl("href")
                if (movieId(url) == null || title.isEmpty()) null
                else CinemaItem(title, url, img?.absUrl("src"), false, null, emptyList())
            }
        }
        return mergeSections(cards + sliders)
    }
    fun mergeSections(items: List<CinemaItem>): List<CinemaItem> = items.groupBy { it.url }.values.map { group ->
        group.first().copy(sections = group.flatMap { it.sections }.toSet())
    }
    fun movieId(url: String): Pair<String, String>? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.path?.endsWith("/movie.html") != true) return null
        val q = uri.rawQuery?.split('&')?.mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }?.toMap() ?: return null
        val id = q["tmdb"]?.takeIf { it.matches(Regex("[0-9]+")) } ?: return null
        val mux = q["mux"]?.takeIf { it.matches(Regex("[A-Za-z0-9]+")) } ?: return null
        return id to mux
    }
    fun findItem(items: List<CinemaItem>, url: String): CinemaItem? {
        items.firstOrNull { it.url == url }?.let { return it }
        val movie = movieId(url)
        if (movie != null) return items.firstOrNull { !it.series && movieId(it.url) == movie }
        val series = Regex("/series/(\\d+)\\.html(?:$|[?#])").find(url)?.groupValues?.get(1) ?: return null
        return items.firstOrNull { it.series && Regex("/series/(\\d+)\\.html(?:$|[?#])").find(it.url)?.groupValues?.get(1) == series }
    }
    fun sameMovieSources(items: List<CinemaItem>, url: String): List<String> {
        val chosen = findItem(items, url) ?: return listOfNotNull(movieId(url)?.second)
        val id = movieId(chosen.url)?.first ?: return emptyList()
        val title = chosen.title.trim()
        return items.asSequence().filter { !it.series && it.title.trim().equals(title, ignoreCase = true) }
            .mapNotNull { movieId(it.url) }.filter { it.first == id }.map { it.second }.distinct().toList()
    }
    fun catalogueKey(item: CinemaItem): String {
        val uri = runCatching { URI(item.url) }.getOrNull()
        val seriesId = if (item.series) Regex("/series/(\\d+)\\.html").find(uri?.path.orEmpty())?.groupValues?.get(1) else null
        return when {
            seriesId != null -> "tv:$seriesId"
            !item.series && movieId(item.url) != null -> "movie:${movieId(item.url)?.first}:${item.title.trim().lowercase()}"
            else -> item.url
        }
    }
    fun episodes(html: String): List<EpisodeKey> {
        val body = Regex("""const\s+episodeLinks\s*=\s*\{([\s\S]*?)\}""").find(html)?.groupValues?.get(1) ?: return emptyList()
        return Regex("""["'](\d+)-(\d+)["']\s*:\s*["']([^"']+)["']""").findAll(body).mapNotNull {
            val (season, episode, source) = it.destructured
            val s = season.toIntOrNull() ?: return@mapNotNull null
            val e = episode.toIntOrNull() ?: return@mapNotNull null
            if (s < 1 || e < 1 || !source.matches(Regex("[A-Za-z0-9]+"))) null else EpisodeKey(s, e, source)
        }.distinctBy { it.season to it.episode }.toList()
    }
}
