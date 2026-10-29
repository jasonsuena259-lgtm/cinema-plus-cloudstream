package com.dexter

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class DexterParserTest {
    @Test fun catalogueCardsDoNotConfuseMovieWithTvEpisode() {
        val html="""
          <article class="card">
            <a href="/watch/429617-spider-man-far-from-home"><img src="/images/movie.webp"
              alt="Spider-Man: Far From Home"></a>
            <h3>Spider-Man: Far From Home</h3>
          </article>
          <article class="card">
            <a href="/show/1405-dexter"><img src="/images/dexter.webp" alt="Dexter"></a>
            <h3>Dexter (2006)</h3>
          </article>
          <a href="/watch/show/1405-dexter/1/1">Episode 1</a>
        """.trimIndent()
        val cards=DexterParser.cards(html)
        assertEquals(2,cards.size)
        assertFalse(cards[0].series)
        assertTrue(cards[1].series)
        assertEquals("Spider-Man: Far From Home",cards[0].title)
        assertTrue(cards.all{it.poster?.startsWith("https://dexter.pw/images/")==true})
        assertFalse(cards.any { it.url.contains("/watch/show/") })
    }

    @Test fun seasonsAndEpisodesKeepOnlyExistingLinksAndAreSorted() {
        val doc=DexterParser.document("""
          <a href="/watch/show/1405-dexter/2/10">Episode 10</a>
          <a href="/watch/show/1405-dexter/1/2">Episode 2</a>
          <a href="/watch/show/1405-dexter/1/1">Episode 1</a>
          <a href="/watch/show/1405-dexter/2/10">Episode 10 duplicate</a>
          <a href="/show/1405-dexter?season=3">Watch Season 3 (12 episodes)</a>
        """.trimIndent(),"https://dexter.pw/show/1405-dexter")
        val episodes=DexterParser.episodes(doc)
        assertEquals(listOf(1,1,2),episodes.map { it.season })
        assertEquals(listOf(1,2,10),episodes.map { it.number })
        assertEquals(3,episodes.map { it.url }.distinct().size)
        assertEquals(3,DexterParser.seasonPages(doc).single().second)
    }

    @Test fun suppliedNeonvaseHlsPathHas1080QualityNotOneServerPerSegment() {
        val html="""
          <video><source src="https://v.neonvase.top/v/sample/1920x1080/index.m3u8"></video>
          <iframe src="https://player.example.org/e/abc"></iframe>
          <source src="https://v.neonvase.top/v/sample/1280x720/index.m3u8">
        """.trimIndent()
        val sources=DexterParser.sources(html,DexterParser.BASE)
        assertEquals(3,sources.size)
        assertEquals(1080,DexterParser.quality(sources[0].url))
        assertEquals(720,DexterParser.quality(sources[2].url))
        val hosts=sources.map{URI(it.url).host}
        assertEquals(2,hosts.distinct().size)
    }

    @Test fun subtitleCandidatesRejectThumbnailVttAndDoNotInventArabic() {
        val html="""
          <track src="/captions/en.vtt?token=abc" kind="subtitles" srclang="en">
          <track src="https://v.neonvase.top/v/abc/thumbs.vtt" kind="metadata">
          <track src="/captions/unknown.srt">
          <a href="/ads.html">Ad</a>
        """.trimIndent()
        val found=DexterParser.subtitles(html,DexterParser.BASE)
        assertEquals(2,found.size)
        assertEquals(listOf("en","Unknown"),found.map{it.first})
        assertTrue(found[0].second.contains("?token=abc"))
        assertFalse(found.any { it.second.contains("thumbs.vtt") })
    }

    @Test fun onlyHlsPlaylistsAndSafeDexterUrlsAreAccepted() {
        assertTrue(DexterParser.isHls("#EXTM3U\n#EXTINF:6,\nseg001.m4s"))
        assertTrue(DexterParser.isHls("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=111\nindex.m3u8"))
        assertFalse(DexterParser.isHls("<html>Just a moment...</html>"))
        assertNull(DexterParser.site("https://evil.example/watch/429617-spider-man"))
        assertNull(DexterParser.site("javascript:alert(1)"))
        assertNull(DexterParser.externalUrl("http://127.0.0.1/media.mp4",DexterParser.BASE))
    }

    @Test fun noGuessedLinksWhenPageIsOnlyLoadingOrBlocked() {
        val html="<h1>Just a moment...</h1><div>21 servers loading</div>"
        assertTrue(DexterParser.sources(html,DexterParser.BASE).isEmpty())
        assertTrue(DexterParser.episodes(DexterParser.document(html)).isEmpty())
        assertTrue(DexterParser.cards(html).isEmpty())
    }
}
