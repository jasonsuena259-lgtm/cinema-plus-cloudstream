package com.cinemaplus

import org.junit.Assert.*
import org.junit.Test

class ParserTest {
    @Test fun movieQuery() {
        assertEquals("1428857" to "yKiVRUq56B28fGUsEzA1SN6005ojqmgN2ZVGBMEB59P8", CinemaPlusParser.movieId("https://example.org/movie.html?tmdb=1428857&mux=yKiVRUq56B28fGUsEzA1SN6005ojqmgN2ZVGBMEB59P8"))
        assertNull(CinemaPlusParser.movieId("https://example.org/movie.html?tmdb=12"))
        assertNull(CinemaPlusParser.movieId("https://example.org/movie.html?tmdb=bad&mux=foo"))
    }
    @Test fun cardAndDedup() {
        val card = """<div class="card-wrapper" data-title="MobLand" data-year="2025" data-genre="Crime|Drama"><a href="series/247718.html"><img src="https://example.org/a.jpg"></a></div>"""
        val items = CinemaPlusParser.cards(card + card, "https://example.org/index.html")
        assertEquals(1, items.size); assertTrue(items[0].series); assertEquals(listOf("Crime", "Drama"), items[0].genres)
        assertTrue(CinemaPlusParser.cards("<div>changed</div>", "https://example.org/").isEmpty())
    }
    @Test fun sectionsSurviveDuplicateCards() {
        val card = """<div class="card-wrapper"><a href="movie.html?tmdb=42&amp;mux=abc"><img src="x.jpg"></a><h3 class="card-details-title">Movie</h3></div>"""
        val html = """<div class="section"><h3 class="section-title">الاضافات الأخيرة</h3>$card</div><div class="section"><h3 class="section-title">الأفلام الجديدة</h3>$card</div>"""
        val items = CinemaPlusParser.cards(html, "https://example.org/index.html")
        assertEquals(1, items.size)
        assertEquals(setOf("الاضافات الأخيرة", "الأفلام الجديدة"), items.single().sections)
    }
    @Test fun matchDetailsUsingStableIdentity() {
        val a = CinemaItem("Film", "https://example.org/movie.html?tmdb=42&mux=One", null, false, null, emptyList())
        val b = CinemaItem("Series", "https://example.org/series/106379.html", null, true, null, emptyList())
        assertEquals(a, CinemaPlusParser.findItem(listOf(a, b), "https://example.org/movie.html?mux=One&tmdb=42"))
        assertNull(CinemaPlusParser.findItem(listOf(a, b), "https://example.org/movie.html?mux=Different&tmdb=42"))
        assertEquals(b, CinemaPlusParser.findItem(listOf(a, b), "https://example.org/series/106379.html?ref=search"))
    }
    @Test fun allSeriesMapsFromSite() {
        val json = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(javaClass.getResource("/series-maps.json")!!.readText())
        assertEquals(69, json.size())
        var total = 0
        json.forEach { page ->
            val actual = CinemaPlusParser.episodes(page.path("html").asText())
            val expected = page.path("entries").map { EpisodeKey(it[0].asInt(), it[1].asInt(), it[2].asText()) }.distinctBy { it.season to it.episode }
            assertEquals("Series ${page.path("series").asText()}", expected, actual)
            total += actual.size
        }
        assertTrue(total > 1200)
    }
    @Test fun multipleMovieSourcesMustMatchIdentityAndTitle() {
        val items = listOf(
            CinemaItem("Movie", "https://example.org/movie.html?tmdb=42&mux=One", null, false, null, emptyList()),
            CinemaItem("Movie", "https://example.org/movie.html?tmdb=42&mux=Two", null, false, null, emptyList()),
            CinemaItem("Other", "https://example.org/movie.html?tmdb=42&mux=Wrong", null, false, null, emptyList()),
            CinemaItem("Movie", "https://example.org/movie.html?tmdb=43&mux=Wrong", null, false, null, emptyList())
        )
        assertEquals(listOf("One", "Two"), CinemaPlusParser.sameMovieSources(items, items[0].url))
        assertEquals(3, items.distinctBy { CinemaPlusParser.catalogueKey(it) }.size)
    }
    @Test fun moonKnightFixture() {
        val html = javaClass.getResource("/series-92749.html")!!.readText()
        val episodes = CinemaPlusParser.episodes(html)
        assertEquals(6, episodes.size)
        assertEquals("q00Dv00yIOUgZpr5monnXXIyc1RjyRn02GojaDTG5RrPjA", episodes.first().source)
        assertEquals(6, episodes.last().episode)
    }
    @Test fun realSeriesFixture() {
        val html = javaClass.getResource("/series-106379.html")!!.readText()
        val episodes = CinemaPlusParser.episodes(html)
        assertEquals(16, episodes.size)
        assertEquals("46lLvi5wmmVRBn3ad6j02XruD02e00O9pBd45F01Cr600niM", episodes.first().source)
        assertEquals("fDbhWeBwcFa8uyh7iKL601Fz01bbflm6La02IcROlobtGQ", episodes.first { it.season == 2 && it.episode == 1 }.source)
    }
    @Test fun episodesRemainDistinct() {
        val html = """const episodeLinks = {"1-1":"first", "1-2":"second", "2-1":"third"};"""
        val eps = CinemaPlusParser.episodes(html)
        assertEquals(listOf("first", "second", "third"), eps.map { it.source })
        assertEquals(listOf(1, 1, 2), eps.map { it.season })
        assertTrue(CinemaPlusParser.episodes("const episodeLinks = broken").isEmpty())
    }
}
