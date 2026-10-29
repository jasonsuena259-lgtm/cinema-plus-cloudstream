package com.cinelite

import org.junit.Assert.*
import org.junit.Test

/** Contract tests use artificial DOM fixtures, not claimed live captures. */
class CineLiteParserTest {
    @Test fun onlyRealAnchoredCards() {
        val html = """
          <a href="/movie/550"><img data-src="https://image.tmdb.org/t/p/w500/test.jpg" alt="Fight Club"><h3>Fight Club</h3></a>
          <a href="/tv/1399"><h3>Game of Thrones</h3></a>
          <a href="/movie/550"><h3>Fight Club</h3></a>
          <a href="/collection"><h3>No content id</h3></a>
          <a href="https://evil.example/movie/2"><h3>External</h3></a>
        """.trimIndent()
        val cards = CineLiteParser.cards(html)
        assertEquals(2, cards.size)
        assertEquals("movie", cards[0].type)
        assertEquals("tv", cards[1].type)
        assertEquals("https://cinelite.xyz/movie/550", cards[0].url)
        assertTrue(cards[0].poster!!.contains("image.tmdb.org"))
    }

    @Test fun noSyntheticCatalogue() {
        assertTrue(CineLiteParser.cards("<div>loading...</div>").isEmpty())
        assertTrue(CineLiteParser.cards("<a href='/movie/9'></a>").isEmpty())
        assertNull(CineLiteParser.siteUrl("https://evil.example/movie/550"))
        assertNull(CineLiteParser.siteUrl("https://cinelite.xyz.evil.example/movie/550"))
        assertNull(CineLiteParser.siteUrl("https://cinelite.xyz@evil.example/movie/550"))
        assertNull(CineLiteParser.siteUrl("javascript:alert(1)"))
    }

    @Test fun episodesRequireActualItemsAndLinks() {
        val html = """
            <a href="/tv/1399?season=2&episode=1" data-episode="1" data-season="2">Episode 1</a>
            <a href="/tv/1399?season=2&episode=2" data-episode="2" data-season="2">Episode 2</a>
            <a href="https://evil.example/tv/1" data-episode="3">Bad</a>
        """.trimIndent()
        val eps = CineLiteParser.episodes(html, "https://cinelite.xyz/tv/1399")
        assertEquals(2, eps.size)
        assertEquals(2, eps[0].season)
        assertEquals(2, eps[1].episode)
        assertTrue(CineLiteParser.episodes("<div>Season 2 has 10 episodes</div>",
            "https://cinelite.xyz/tv/1399").isEmpty())
    }

    @Test fun onlyDeclaredPlayerSources() {
        val html = """
            <iframe title="Viper" src="https://embed.cinelite.xyz/e/a1"></iframe>
            <iframe title="Viper" src="https://embed.cinelite.xyz/e/a1"></iframe>
            <source src="https://cdn.example.com/stream.m3u8?token=a">
            <a href="https://example.com/not-a-player">News</a>
        """.trimIndent()
        val list = CineLiteParser.sources(html, "https://cinelite.xyz/movie/550")
        assertEquals(2, list.size)
        assertTrue(list.any { it.name == "Viper" && !it.direct })
        assertTrue(list.any { it.direct && it.url.contains("m3u8") })
        assertTrue(CineLiteParser.sources("<div>No playable embed</div>", CineLiteParser.BASE).isEmpty())
    }
}
