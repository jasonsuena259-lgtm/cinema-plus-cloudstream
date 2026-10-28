package com.akwam

import org.junit.Assert.*
import org.junit.Test

class AkwamMediaTest {
    @Test fun directAndVariableSourcesFromObservedPlayers() {
        val script = """var links={"hls2":"https://media.example/hls/master.m3u8?t=token&s=1","hls3":"https://media.example/hls/master.txt","hls4":"/stream/file/master.m3u8"};jwplayer("vplayer").setup({sources:[{file:links.hls4||links.hls3||links.hls2,type:"hls"}]});"""
        assertEquals(listOf("https://media.example/hls/master.m3u8?t=token&s=1", "https://media.example/hls/master.txt", "https://fastvid.cam/stream/file/master.m3u8"), AkwamParser.mediaUrls(script,"https://fastvid.cam/e/file"))
        val direct = """jwplayer("vplayer").setup({sources:[{file:"https://media.example/master.m3u8?t=1"}]});"""
        assertEquals(listOf("https://media.example/master.m3u8?t=1"), AkwamParser.mediaUrls(direct,"https://luluvido.com/e/file"))
    }
    @Test fun watchRoutePreservesArabicAndRejectsOtherHosts() {
        assertEquals("https://akwam.day/watch/movie-name", AkwamParser.watchUrl("https://akwam.day/film/movie-name"))
        val arabic = AkwamParser.watchUrl("https://akwam.day/episode/مسلسل-الحلقة-6")!!
        assertTrue(arabic.contains("/watch/%"))
        assertEquals(arabic,AkwamParser.watchUrl("https://akwam.day/episode/مسلسل-الحلقة-6?ref=search"))
        assertNull(AkwamParser.watchUrl("https://other.example/episode/test"))
        assertNull(AkwamParser.watchUrl("https://akwam.day/series/test"))
    }
    @Test fun arabicWatchUrlsMustBeEncodedForHttpHeaders() {
        val raw = "https://akwam.day/watch/مسلسل-الحلقة-6"
        try {
            okhttp3.Headers.Builder().add("Referer", raw).build()
            fail("OkHttp must reject raw Arabic header characters")
        } catch (_: IllegalArgumentException) { }
        val safe = AkwamParser.watchUrl("https://akwam.day/episode/مسلسل-الحلقة-6")!!
        val headers = okhttp3.Headers.Builder().add("Referer", safe).build()
        assertEquals(safe, headers["Referer"])
    }
    @Test fun duplicatesAndNonMediaAreExcluded() {
        val script = """{image:"https://img.example/a.jpg",a:"https://cdn.example/master.m3u8",b:"https://cdn.example/master.m3u8"}"""
        assertEquals(1,AkwamParser.mediaUrls(script,"https://luluvido.com/e/file").size)
        assertTrue(AkwamParser.mediaUrls("<html>Unavailable</html>","https://luluvido.com/e/file").isEmpty())
    }
}
