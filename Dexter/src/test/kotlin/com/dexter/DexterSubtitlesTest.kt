package com.dexter

import org.junit.Assert.*
import org.junit.Test

class DexterSubtitlesTest {
    @Test fun movieAndEpisodeRoutesMapToExistingSubtitleApi() {
        assertEquals("https://sub.vdrk.site/v1/movie/429617",
            DexterSubtitles.endpoint(
                "https://dexter.pw/watch/429617-spider-man-far-from-home?imdb=tt6320628"))
        assertEquals("https://sub.vdrk.site/v1/tv/259909/1/1",
            DexterSubtitles.endpoint(
                "https://dexter.pw/watch/show/259909-dexter-resurrection/1/1"))
        assertNull(DexterSubtitles.endpoint("https://evil.example/watch/429617-test"))
    }

    @Test fun realArabicVttAndAlternativeSubtitleVersionsAreFirst() {
        val json="""[
          {"label":"English","file":"https://cache.vdrk.site/en.srt?token=1"},
          {"label":"Arabic4","file":"https://cache.vdrk.site/ar4.vtt?token=4"},
          {"label":"Spanish","file":"https://cache.vdrk.site/es.vtt"},
          {"label":"Arabic","file":"https://cache.vdrk.site/ar.vtt?token=1"},
          {"label":"Arabic2","file":"https://cache.vdrk.site/ar2.vtt"},
          {"label":"Arabic3","file":"https://cache.vdrk.site/ar3.vtt"},
          {"label":"Fake","file":"https://cache.vdrk.site/thumbs.vtt"},
          {"label":"Ad","file":"javascript:alert(1)"}
        ]"""
        val subtitles=DexterSubtitles.parse(json)
        assertEquals(6,subtitles.size)
        assertEquals(4,subtitles.take(4).count { it.language.startsWith("Arabic") })
        assertTrue(subtitles[0].url.startsWith("https://"))
        assertTrue(subtitles.all { it.url!="javascript:alert(1)" })
    }

    @Test fun invalidHtmlAndMalformedJsonDoNotBecomeArabicSubtitles() {
        assertTrue(DexterSubtitles.parse("<html>403</html>").isEmpty())
        assertTrue(DexterSubtitles.parse("""{"label":"Arabic","file":"bad"}""").isEmpty())
    }
}
