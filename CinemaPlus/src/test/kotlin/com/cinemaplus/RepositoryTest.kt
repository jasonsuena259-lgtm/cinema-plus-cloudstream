package com.cinemaplus

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class RepositoryTest {
    private val base = CinemaPlusParser.BASE
    private fun fixture(name: String) = javaClass.getResource("/$name")!!.readText()

    @Test fun allRealCatalogueEntriesCanReachTheirDetailsIdentity() = runBlocking {
        val repository = CinemaPlusRepository(base) { url, _ -> when {
            url.endsWith("index.html") -> fixture("site-index.html")
            url.endsWith("discover.html") -> fixture("site-discover.html")
            url.endsWith("movies.json") -> fixture("movies.json")
            else -> null
        } }
        val items = repository.catalogue()
        assertTrue(items.size > 400)
        assertTrue(items.any { it.series }); assertTrue(items.any { !it.series })
        for (item in items) {
            val canonical = CinemaPlusParser.canonicalUrl(item.url)
            assertNotNull(item.title, canonical)
            assertNotNull(item.title, CinemaPlusParser.findItem(items, canonical!!))
            if (!item.series) assertNotNull(item.title, CinemaPlusParser.movieId(canonical))
        }
        assertFalse(items.any { it.url.startsWith("go:") })
    }
    @Test fun allSeriesDetailsSurviveUnavailableMetadata() = runBlocking {
        val maps = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(fixture("series-maps.json"))
        var total = 0
        for (page in maps) {
            val repository = CinemaPlusRepository(base) { url, _ ->
                if (url.startsWith(base)) page.path("html").asText() else null
            }
            val episodes = repository.seriesEpisodes("$base/series/${page.path("series").asText()}.html")
            assertEquals(page.path("entries").map { it[0].asInt() to it[1].asInt() }.distinct().size, episodes.size)
            assertNull(repository.metadata("92749", true))
            total += episodes.size
        }
        assertTrue(total > 1200)
    }
    @Test fun brokenJsonAndUnavailableHomeDoNotDiscardDiscover() = runBlocking {
        val repository = CinemaPlusRepository(base) { url, _ -> when {
            url.endsWith("index.html") -> throw java.io.IOException("offline")
            url.endsWith("movies.json") -> "{broken"
            url.endsWith("discover.html") -> fixture("site-discover.html")
            else -> null
        } }
        assertTrue(repository.catalogue().size > 400)
    }
    @Test fun failedSeriesPageHasExplicitErrorAndLimitedRetry() = runBlocking {
        var requests = 0
        val repository = CinemaPlusRepository(base) { _, _ -> requests++; null }
        try { repository.seriesEpisodes("$base/series/92749.html"); fail("Expected network error") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("تعذر جلب")) }
        assertEquals(2, requests)
    }
    @Test fun changedSeriesPageHasExplicitError() = runBlocking {
        val repository = CinemaPlusRepository(base) { _, _ -> "<html>Changed page</html>" }
        try { repository.seriesEpisodes("$base/series/92749.html"); fail("Expected parser error") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("لا تحتوي حلقات")) }
    }
    @Test fun cancellationIsNotRetried() = runBlocking {
        var requests = 0
        val repository = CinemaPlusRepository(base) { _, _ -> requests++; throw CancellationException("stop") }
        try { repository.get(base); fail("Cancellation swallowed") }
        catch (_: CancellationException) { assertEquals(1, requests) }
    }
    @Test fun legacyUrlsAreNormalizedWithoutGuessingSources() {
        assertEquals("$base/movie.html?tmdb=42&mux=abc", CinemaPlusParser.canonicalUrl("movie.html?tmdb=42&amp;mux=abc"))
        assertEquals("$base/series/92749.html#1-2", CinemaPlusParser.canonicalUrl("http://ffrrx-2000.github.io/cinema-plas-bot/series/92749.html#1-2"))
        assertNull(CinemaPlusParser.canonicalUrl("go:1001311"))
        assertNull(CinemaPlusParser.canonicalUrl("https://other.example/series/92749.html"))
        assertNull(CinemaPlusParser.canonicalUrl("../../series/92749.html"))
    }
}
