package com.akwam

import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Optional, real-site integration checks against akwam.ss (not retired akwam.day).
 * CI unit tests skip these unless AKWAM_LIVE_TEST=1 is explicitly selected.
 * A successful HTML response is NOT proof that an MP4 is playable on Android.
 */
class AkwamLiveTest {
    private fun enabled() = assumeTrue("Enable with AKWAM_LIVE_TEST=1",
        System.getenv("AKWAM_LIVE_TEST") == "1")

    @Test fun realCatalogueSearchAndMovieQualityRoutes() = runBlocking {
        enabled()
        val base = AkwamParser.BASE
        val page = app.get("${base}/movies", timeout = 15)
        assertEquals("Movie catalogue HTTP", 200, page.code)
        val items = AkwamParser.cards(page.text, "${base}/movies")
        assertTrue("Live movie catalogue contained no valid cards", items.isNotEmpty())

        val provider = AkwamProvider()
        val home = provider.getMainPage(1,
            MainPageRequest(name = "Movies", data = "/movies", horizontalImages = false))
        assertTrue("CloudStream main page returned no cards",
            home.items.flatMap { it.list }.isNotEmpty())

        val movie = "${base}/movie/11572/love-language"
        val detail = app.get(movie, timeout = 15)
        assertEquals("Movie details HTTP", 200, detail.code)
        val routes = AkwamParser.qualities(detail.text, movie)
        assertTrue("No genuine quality routes", routes.isNotEmpty())
        assertTrue("No genuine watch/download quality pair",
            routes.any { it.watch != null && it.download != null })
        assertTrue(provider.load(movie) is MovieLoadResponse)
        println("AKWAM_SS_LIVE_MOVIE_OK: cards=${items.size}; qualities=${routes.size}")
    }

    @Test fun realSeriesHasActualOrderedEpisodes() = runBlocking {
        enabled()
        val series = AkwamParser.BASE +
            "/series/5908/the-diplomat-%D8%A7%D9%84%D9%85%D9%88%D8%B3%D9%85-%D8%A7%D9%84%D8%B1%D8%A7%D8%A8%D8%B9"
        val detail = app.get(series, timeout = 15)
        assertEquals("Series details HTTP", 200, detail.code)
        val episodes = AkwamParser.episodes(AkwamParser.document(detail.text, series), 4)
        assertTrue("Series page returned no real episode URLs", episodes.isNotEmpty())
        assertEquals(episodes.map { it.number }.sorted(), episodes.map { it.number })
        assertTrue(episodes.all { it.url.startsWith(AkwamParser.BASE + "/episode/") })
        val response = AkwamProvider().load(series)
        assertTrue("Series must be loadable as a TV season", response is TvSeriesLoadResponse)
        println("AKWAM_SS_LIVE_SERIES_OK: episodes=${episodes.size}")
    }
}
