package com.cinemaplus

import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.ErrorLoadingException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProviderTest {
    private val base = CinemaPlusParser.BASE
    private fun provider(): CinemaPlusProvider {
        val repository = CinemaPlusRepository(base) { url, _ -> when {
            url.endsWith("index.html") -> javaClass.getResource("/site-index.html")!!.readText()
            url.endsWith("discover.html") -> javaClass.getResource("/site-discover.html")!!.readText()
            url.endsWith("movies.json") -> "broken json"
            url.endsWith("/series/106379.html") -> javaClass.getResource("/series-106379.html")!!.readText()
            url.endsWith("/series/92749.html") -> javaClass.getResource("/series-92749.html")!!.readText()
            else -> null // TMDB/network unavailable
        } }
        return CinemaPlusProvider(repository)
    }
    @Test fun movieLoadReturnsDetailsWithoutTmdb() = runBlocking {
        val result = provider().load("$base/movie.html?tmdb=1428857&mux=yKiVRUq56B28fGUsEzA1SN6005ojqmgN2ZVGBMEB59P8")
        assertTrue(result is MovieLoadResponse)
        assertFalse(result.name.isBlank())
        assertEquals("Cinema Plus", result.apiName)
    }
    @Test fun seriesLoadReturnsEveryEpisodeWithoutTmdb() = runBlocking {
        for ((id, count) in listOf("92749" to 6, "106379" to 16)) {
            val result = provider().load("$base/series/$id.html") as TvSeriesLoadResponse
            assertEquals(count, result.episodes.size)
            assertTrue(result.episodes.all { it.data.startsWith("$base/series/$id.html#") })
        }
    }
    @Test fun everySiteSeriesReturnsCloudstreamEpisodes() = runBlocking {
        val maps = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(javaClass.getResource("/series-maps.json")!!.readText())
        val pages = maps.associate { "$base/series/${it.path("series").asText()}.html" to it }
        val repository = CinemaPlusRepository(base) { url, _ -> pages[url]?.path("html")?.asText() }
        val api = CinemaPlusProvider(repository)
        for ((url, page) in pages) {
            val result = api.load(url) as TvSeriesLoadResponse
            assertEquals(url, page.path("entries").map { it[0].asInt() to it[1].asInt() }.distinct().size, result.episodes.size)
            assertTrue(result.episodes.all { it.data.startsWith("$url#") })
        }
    }
    @Test fun everyCatalogueMovieReturnsCloudstreamDetails() = runBlocking {
        val repository = CinemaPlusRepository(base) { url, _ -> when {
            url.endsWith("index.html") -> javaClass.getResource("/site-index.html")!!.readText()
            url.endsWith("discover.html") -> javaClass.getResource("/site-discover.html")!!.readText()
            else -> null
        } }
        val api = CinemaPlusProvider(repository)
        val movies = repository.catalogue().filter { !it.series }
        assertTrue(movies.size > 400)
        for (movie in movies) {
            val result = api.load(movie.url) as MovieLoadResponse
            assertEquals(movie.title, result.name)
            assertEquals(movie.url, result.dataUrl)
        }
    }
    @Test fun invalidSavedLinkExplainsFailureInsteadOfReturningNull() = runBlocking {
        try { provider().load("go:1001311"); fail("Expected explicit error") }
        catch (e: ErrorLoadingException) { assertTrue(e.message!!.contains("Cinema Plus v6")); assertTrue(e.message!!.contains("go:")) }
    }
}
