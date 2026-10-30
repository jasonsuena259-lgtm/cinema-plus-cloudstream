package com.arabseed

import org.junit.Assert.*
import org.junit.Test

class ArabSeedV4Test {
    @Test fun cybervynxDownloadHasMatchingWatchEndpoint() {
        assertEquals(
            listOf("https://cybervynx.com/d/ac45","https://cybervynx.com/e/ac45"),
            ArabSeedHostCandidates.variants("https://cybervynx.com/d/ac45"))
        assertEquals(listOf("https://cybervynx.com/e/ac45"),
            ArabSeedHostCandidates.variants("https://cybervynx.com/e/ac45"))
    }

    @Test fun doodAndMixdropRetainExistingVariants() {
        assertEquals("https://doodstream.com/e/abc",
            ArabSeedHostCandidates.variants("https://doodstream.com/d/abc").last())
        assertEquals("https://mixdrop.ps/e/abc",
            ArabSeedHostCandidates.variants("https://mixdrop.ps/f/abc").last())
        assertEquals("https://voe.sx/e/abc",
            ArabSeedHostCandidates.variants("https://voe.sx/e/abc").last())
    }

    @Test fun downloadingSameMirrorTwiceIsDeduplicatedButDifferentIDsArePreserved() {
        val url="https://arabsed.cam/مسلسل-test-الموسم-الاول-الحلقة-1/download/"
        val doc=ArabSeedParser.doc("""
            <div class="arabsed-download-grid">
              <a class="arabsed-download-card" href="https://doodstream.com/d/001">doodstream</a>
              <a class="arabsed-download-card" href="https://doodstream.com/d/002">doodstream</a>
              <a class="arabsed-download-card" href="https://doodstream.com/d/001">doodstream</a>
              <a class="arabsed-download-card" href="https://mixdrop.ps/f/3">mixdrop</a>
            </div>
        """,url)
        assertEquals(3,ArabSeedParser.downloadSources(doc).size)
    }

    @Test fun unquotedAndEscapedPlayerUrlsAreRecognized() {
        val base="https://1vid.xyz/e/example"
        val raw="""var src=https://cdn.example/movies/ep.m3u8?token=abc;"""
        assertTrue(ArabSeedParser.media(raw,base).any{it.contains("ep.m3u8")})
        val js="""var file = "https:\/\/cdn.example\/film.mp4?token=abc";"""
        assertEquals(listOf("https://cdn.example/film.mp4?token=abc"),ArabSeedParser.media(js,base))
        assertEquals(emptyList<String>(),ArabSeedParser.media("""<html><img src="/poster.jpg"></html>""",base))
    }

    @Test fun unicodeEscapedJsonUrlsAreRestored() {
        val base="https://vidmoly.org/e/example"
        val js="""{"file":"https:\u002F\u002Fcdn.example\u002Fmaster.m3u8?token=abc"}"""
        assertEquals(listOf("https://cdn.example/master.m3u8?token=abc"),ArabSeedParser.media(js,base))
    }
}
