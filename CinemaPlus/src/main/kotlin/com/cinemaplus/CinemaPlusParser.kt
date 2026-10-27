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
            CinemaItem(title, url, poster, series, card.attr("data-year").toIntOrNull(), card.attr("data-genre").split('|').filter { it.isNotBlank() })
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
        return (cards + sliders).distinctBy { it.url }
    }
    fun movieId(url: String): Pair<String, String>? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.path?.endsWith("/movie.html") != true) return null
        val q = uri.rawQuery?.split('&')?.mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }?.toMap() ?: return null
        val id = q["tmdb"]?.takeIf { it.matches(Regex("[0-9]+")) } ?: return null
        val mux = q["mux"]?.takeIf { it.matches(Regex("[A-Za-z0-9]+")) } ?: return null
        return id to mux
    }
    fun episodes(html: String): List<EpisodeKey> {
        val body = Regex("""const\s+episodeLinks\s*=\s*\{([\s\S]*?)}""").find(html)?.groupValues?.get(1) ?: return emptyList()
        return Regex("""["'](\d+)-(\d+)["']\s*:\s*["']([^"']+)["']""").findAll(body).mapNotNull {
            val (season, episode, source) = it.destructured
            val s = season.toIntOrNull() ?: return@mapNotNull null
            val e = episode.toIntOrNull() ?: return@mapNotNull null
            if (s < 1 || e < 1 || !source.matches(Regex("[A-Za-z0-9]+"))) null else EpisodeKey(s, e, source)
        }.distinctBy { it.season to it.episode }.toList()
    }
}
