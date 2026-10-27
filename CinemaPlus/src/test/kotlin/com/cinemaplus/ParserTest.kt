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
    @Test fun matchDetailsUsingStableIdentity() {
        val a = CinemaItem("Film", "https://example.org/movie.html?tmdb=42&mux=One", null, false, null, emptyList())
        val b = CinemaItem("Series", "https://example.org/series/106379.html", null, true, null, emptyList())
        assertEquals(a, CinemaPlusParser.findItem(listOf(a, b), "https://example.org/movie.html?mux=One&tmdb=42"))
        assertNull(CinemaPlusParser.findItem(listOf(a, b), "https://example.org/movie.html?mux=Different&tmdb=42"))
        assertEquals(b, CinemaPlusParser.findItem(listOf(a, b), "https://example.org/series/106379.html?ref=search"))
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
