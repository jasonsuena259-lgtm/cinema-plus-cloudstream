package com.dexter

import org.junit.Assert.*
import org.junit.Test

class DexterApiTest {
    @Test fun endpointUsesMovieIdNotSlugOrWatchPageHtml() {
        assertEquals(
            "https://dexter.pw/api/movie/429617?site=dexter",
            DexterApi.endpoint("https://dexter.pw/watch/429617-spider-man-far-from-home"))
        assertNull(DexterApi.endpoint("https://malicious.example/watch/429617-spider-man"))
        assertNull(DexterApi.endpoint("https://dexter.pw/show/1405-dexter"))
    }

    @Test fun tvEpisodeIdsBecomeExactPublicApiRoutes() {
        assertEquals("https://dexter.pw/api/tv/1405/episode/1/2?site=dexter",
            DexterApi.endpoint("https://dexter.pw/watch/show/1405-dexter/1/2"))
    }

    @Test fun movieApiPreservesRealHlsAndDirectSources() {
        val response="""
          {
             "title":"Spider-Man: Far From Home",
             "playable":true,
             "sources":[
               {"kind":"hls","label":"Neonvase","quality":"1080p",
                "url":"https://v.neonvase.top/v/abc/1920x1080/index.m3u8"},
               {"kind":"hls","label":"Neonvase (duplicate)","quality":"1080p",
                "url":"https://v.neonvase.top/v/abc/1920x1080/index.m3u8"},
               {"kind":"mp4","label":"Direct","url":"https://cdn.example.org/clip.mp4"},
               {"kind":"html","label":"Ad","url":"https://tracker.example.org/ads.html"}
             ],
             "subtitles":[
               {"url":"https://cdn.example.org/subs/ar.vtt?token=aaa","lang":"ar"},
               {"url":"https://cdn.example.org/v/thumbs.vtt","lang":"en"}
             ]
          }
        """.trimIndent()
        val parsed=DexterApi.parse(response)
        assertTrue(parsed.playable)
        assertEquals(3,parsed.sources.size)
        assertEquals("hls",parsed.sources[0].format)
        assertEquals(1080,parsed.sources[0].quality)
        assertEquals("direct",parsed.sources[1].format)
        assertEquals("embed",parsed.sources[2].format)
        assertEquals(1,parsed.subtitles.size)
        assertEquals("ar",parsed.subtitles[0].language)
    }

    @Test fun nonPlayableAndCloudflareResponsesAreNeverServers() {
        assertFalse(DexterApi.parse("<html>Just a moment</html>").playable)
        assertTrue(DexterApi.parse("""{"playable":false,"sources":[{"kind":"hls",
           "url":"https://cdn.example.org/index.m3u8"}]}""").sources.isEmpty())
        assertTrue(DexterApi.parse("""{"sources":[],"playable":true}""").sources.isEmpty())
    }

    @Test fun hlsMasterGeneratesOnlyActualDeclaredResolutionLinks() {
        val manifest="""
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=16050000,RESOLUTION=3840x2160
            3840x2160/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=6110000,RESOLUTION=1920x1080
            1920x1080/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3100000,RESOLUTION=1280x720
            1280x720/index.m3u8
        """.trimIndent()
        val master="https://v.neonvase.top/v/abc/master.m3u8"
        val actual=DexterApi.variants(manifest,master)
        assertEquals(listOf(2160,1080,720),actual.map { it.quality })
        assertEquals("https://v.neonvase.top/v/abc/3840x2160/index.m3u8",
            actual.first().url)
        assertTrue(DexterApi.variants("<html>403</html>",master).isEmpty())
        // Resolutions from one CDN are quality renditions, not 3 servers.
        assertEquals(1,actual.map { java.net.URI(it.url).host }.distinct().size)
    }
}
