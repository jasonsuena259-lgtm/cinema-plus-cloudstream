package com.cinelite

import org.junit.Assert.*
import org.junit.Test

/** Contract tests use artificial DOM fixtures, not claimed live captures. */
class CineLiteParserTest {

    @Test fun annotatedRealReactCardsCarryPlayerIdentity() {
        val html = """
            <div class="group cursor-pointer" data-cinelite-id="1416" data-cinelite-type="tv">
                <img alt="Grey's Anatomy" src="https://image.tmdb.org/t/p/w500/abc.jpg">
                <h3>Grey's Anatomy</h3>
            </div>
            <div class="group cursor-pointer" data-cinelite-id="550" data-cinelite-type="movie">
                <img alt="Fight Club" src="https://image.tmdb.org/t/p/w500/def.jpg">
                <h3>Fight Club</h3>
            </div>
            <div class="group cursor-pointer" data-cinelite-id="0" data-cinelite-type="tv">
                <h3>Invalid</h3>
            </div>
        """.trimIndent()
        val cards = CineLiteParser.cards(html)
        assertEquals(2, cards.size)
        val tv = CineLiteParser.identity(cards.first().url)!!
        assertEquals(1416, tv.id)
        assertEquals("tv", tv.type)
        assertEquals("Grey's Anatomy", tv.title)
        assertEquals("https://embed.cinelite.xyz/1416/1/1",
            CineLiteParser.embedEpisode(tv.id, 1, 1))
        val movie = CineLiteParser.identity(cards[1].url)!!
        assertEquals("https://embed.cinelite.xyz/550",
            CineLiteParser.embedMovie(movie.id))
        assertNull(CineLiteParser.identity("https://evil.example/?cineliteId=550"))
        assertNull(CineLiteParser.embedEpisode(1416, 1, 0))
    }

    @Test fun reactCardsUseRealTmdbPostersInsteadOfBrokenSitePaths() {
        val html = """
            <div data-cinelite-id="21" data-cinelite-type="anime">
                <h3>ONE PIECE</h3><img src="/t/p/w500/real.jpg">
            </div>
        """.trimIndent()
        val card = CineLiteParser.cards(html).single()
        assertEquals("https://image.tmdb.org/t/p/w500/real.jpg", card.poster)
        assertEquals("anime", CineLiteParser.identity(card.url)?.type)
    }

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

    @Test fun homeSectionsUseOnlyCardsInsideTheirRealHeading() {
        val html = """
            <section><h2>Top Rated Masterpieces</h2>
              <div data-cinelite-id="1560520" data-cinelite-type="movie">
                <h3>One Movie</h3><img src="https://image.tmdb.org/t/p/w500/test.jpg">
              </div>
            </section>
            <section><h2>Trending Anime</h2>
              <div data-cinelite-id="195539" data-cinelite-type="tv">
                <h3>Anime Show</h3><img src="https://image.tmdb.org/t/p/w500/anime.jpg">
              </div>
            </section>
        """.trimIndent()
        val sections = CineLiteParser.sections(html)
        assertEquals(listOf("Top Rated Masterpieces", "Trending Anime"), sections.map { it.first })
        assertEquals(listOf("One Movie"), sections[0].second.map { it.title })
        assertEquals(listOf("Anime Show"), sections[1].second.map { it.title })
        assertTrue(CineLiteParser.sections("<section><h2>Empty</h2></section>").isEmpty())
    }

    @Test fun episodesAreSortedNumericallyAndAnimeUsesObservedWatchRoute() {
        val eps = listOf(
            LiteEpisode("Episode 10", "https://embed.cinelite.xyz/195539/1/10", 1, 10),
            LiteEpisode("Episode 2", "https://embed.cinelite.xyz/195539/1/2", 1, 2),
            LiteEpisode("Episode 1", "https://embed.cinelite.xyz/195539/2/1", 2, 1),
            LiteEpisode("Duplicate Episode 2", "https://embed.cinelite.xyz/195539/1/2", 1, 2)
        )
        val sorted = CineLiteParser.orderedEpisodes(eps)
        assertEquals(listOf(2, 10, 1), sorted.map { it.episode })
        assertEquals(listOf(1, 1, 2), sorted.map { it.season })
        assertEquals("https://cinelite.xyz/watch/anime/195539/1",
            CineLiteParser.watchAnimeEpisode(195539, 1))
        assertEquals("https://cinelite.xyz/watch/1560520", CineLiteParser.watchMovie(1560520))
        assertNull(CineLiteParser.watchAnimeEpisode(195539, 0))
    }

    @Test fun subtitlesAreOnlyExtractedFromDeclaredTextTracks() {
        val document = """
            <track kind="subtitles" srclang="ar" src="/subs/ar.vtt">
            <track kind="captions" label="English CC" src="https://cdn.example.com/en.srt">
            <track kind="metadata" src="/skip.vtt">
        """.trimIndent()
        val tracks = CineLiteParser.subtitleTracks(document)
        assertEquals(listOf("Arabic", "English"), tracks.map { it.first })
        assertEquals(2, tracks.size)
        val master = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",LANGUAGE="ar",NAME="Arabic",URI="/subs/ar.vtt"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",LANGUAGE="en",NAME="English",URI="/subs/en.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",URI="/audio.m3u8"
        """.trimIndent()
        val manifest = CineLiteParser.hlsSubtitleTracks(master, "https://example.com/master.m3u8")
        assertEquals(listOf("Arabic"), manifest.map { it.first })
        assertEquals("https://example.com/subs/ar.vtt", manifest.single().second)
    }

    @Test fun subtitleProxyWithExtParameterComesFromRealHtmlOrHlsOnly() {
        val html = """
            <track kind="subtitles" srclang="ar" src="/proxy/subtitles?ext=.vtt&token=example">
            <a href="/proxy/subtitles?ext=srt&token=example">English</a>
            <a href="/proxy/subtitles?ext=mp4&token=example">Not a subtitle</a>
        """.trimIndent()
        val tracks = CineLiteParser.subtitleTracks(html)
        assertEquals(2, tracks.size)
        assertEquals(listOf("Arabic", "English"), tracks.map { it.first })
        assertTrue(tracks.all { CineLiteParser.isSubtitleFile(it.second) })
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="s",LANGUAGE="ar",NAME="Arabic",URI="/proxy/subs?ext=.vtt&token=example"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="s",LANGUAGE="en",NAME="English",URI="/subs/index.m3u8"
        """.trimIndent()
        val hlsTracks = CineLiteParser.hlsSubtitleTracks(master,
            "https://example.com/master.m3u8")
        assertEquals(1, hlsTracks.size)
        assertEquals("Arabic", hlsTracks.single().first)
    }

    @Test fun signedProxyStreamsUseRealHlsTypeWithoutPersistingAnyTokens() {
        val proxy = "https://media.example.org/proxy/hls?t=unit-test-only&ext=.m3u8"
        val vault = "https://media.example.org/vault/hls.m3u8?t=unit-test-only"
        val movie = "https://media.example.org/media/00000.png_v=.mp4"
        assertEquals("hls", CineLiteParser.mediaKind(proxy))
        assertEquals("hls", CineLiteParser.mediaKind(vault))
        assertEquals("mp4", CineLiteParser.mediaKind(movie))
        assertNull(CineLiteParser.mediaKind("https://media.example.org/proxy/hls?t=unit-test-only"))
        assertNull(CineLiteParser.mediaKind("javascript:alert(1)"))
        val html = """<video><source src="$proxy"></video>"""
        val sources = CineLiteParser.sources(html, CineLiteParser.BASE)
        assertEquals(1, sources.size)
        assertTrue(sources.single().direct)
        assertEquals(proxy, sources.single().url)
    }

    @Test fun verifiedWatchPagesResolveToFirstPartyEmbedOnly() {
        assertEquals("https://embed.cinelite.xyz/1560520",
            CineLiteParser.embeddedPlayerForWatchUrl("https://cinelite.xyz/watch/1560520"))
        assertEquals("https://embed.cinelite.xyz/anime/195539/1",
            CineLiteParser.embeddedPlayerForWatchUrl("https://cinelite.xyz/watch/anime/195539/1"))
        assertNull(CineLiteParser.embeddedPlayerForWatchUrl("https://cinelite.xyz/search"))
        assertNull(CineLiteParser.embeddedPlayerForWatchUrl("https://attacker.example/watch/1560520"))
    }

    @Test fun onlyRealSubtitleFilesCanBeExposedAndArabicComesFirst() {
        val ar = "https://www.subtitlecat.com/subs/1654/Movie.Knightfall-ar.srt"
        val en = "https://cdn.example.org/subtitles/Movie.English.vtt"
        val fake = "https://www.subtitlecat.com/subs/1654/Movie.html"
        assertTrue(CineLiteParser.isSubtitleFile(ar))
        assertTrue(CineLiteParser.isSubtitleFile(en))
        assertFalse(CineLiteParser.isSubtitleFile(fake))
        assertFalse(CineLiteParser.isSubtitleFile("http://localhost/subtitles/ar.srt"))
        assertEquals("Arabic", CineLiteParser.subtitleLanguage("", ar))
        assertEquals("Arabic", CineLiteParser.subtitleLanguage("AR subtitle", en))
        assertEquals("English", CineLiteParser.subtitleLanguage("", en))
        assertEquals("Subtitle", CineLiteParser.subtitleLanguage("", "https://cdn.example.org/track.srt"))
        assertTrue(CineLiteParser.subtitlePriority("Arabic") <
            CineLiteParser.subtitlePriority("English"))
        val html = """
            <track kind="subtitles" srclang="ar-SA" src="/subtitle/ar.vtt">
            <a href="https://www.subtitlecat.com/subs/1654/Movie.Knightfall-ar.srt">Arabic</a>
            <a href="https://www.subtitlecat.com/subs/1654/Movie.Knightfall.html">Not SRT</a>
        """.trimIndent()
        val tracks = CineLiteParser.subtitleTracks(html)
        assertEquals(2, tracks.size)
        assertTrue(tracks.all { it.first == "Arabic" })
    }
    @Test fun kodaStreamUsesCanonicalSubtitleNamesAndDirectEmbedRoutes() {
        assertEquals("Arabic", CineLiteParser.subtitleLanguage(
            "العربية", "https://cdn.example.org/track.vtt"))
        assertEquals("Arabic", CineLiteParser.subtitleLanguage(
            "ar", "https://cdn.example.org/track.vtt"))
        assertEquals(0, CineLiteParser.subtitlePriority("Arabic"))
        assertTrue(CineLiteParser.isRecognizedEmbedPath("/anime/21/1"))
        assertTrue(CineLiteParser.isRecognizedEmbedPath("/1560520"))
        assertTrue(CineLiteParser.isRecognizedEmbedPath("/1399/1/1"))
        assertFalse(CineLiteParser.isRecognizedEmbedPath("/anime/21"))
        assertTrue(CineLiteParser.isRecognizedEmbedPath("/e/a1"))
        assertFalse(CineLiteParser.isRecognizedEmbedPath("/e/a%2Fb"))
        assertFalse(CineLiteParser.isRecognizedEmbedPath("/anime/0/1"))
    }


    @Test fun compactAndroidSnapshotPreservesRealSectionCardsAndSearch() {
        val html = """
            <section><h2>Trending Anime</h2>
              <div data-cinelite-id="21" data-cinelite-type="anime"><h3>ONE PIECE</h3>
                <img src="https://img.example.org/onepiece.webp"></div>
              <div data-cinelite-id="20" data-cinelite-type="anime"><h3>Naruto</h3></div>
            </section>
            <section><h2>Current &amp; upcoming TV shows</h2>
              <div data-cinelite-id="1416" data-cinelite-type="tv"><h3>Grey's Anatomy</h3></div>
            </section>
        """.trimIndent()
        val sections = CineLiteParser.sections(html)
        assertEquals(2, sections.size)
        assertEquals("Trending Anime", sections[0].first)
        assertEquals(listOf("ONE PIECE", "Naruto"), sections[0].second.map { it.title })
        val identity = CineLiteParser.identity(sections[0].second.first().url)!!
        assertEquals(21, identity.id)
        assertEquals("anime", identity.type)
        assertEquals(1, sections[1].second.size)
        assertEquals("Grey's Anatomy", sections[1].second.first().title)
        assertEquals(3, CineLiteParser.cards(html).size)
    }
}
