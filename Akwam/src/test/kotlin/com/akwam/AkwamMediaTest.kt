package com.akwam

import org.junit.Assert.*
import org.junit.Test

/**
 * Contract tests for Akwam.ss rather than removed akwam.day/JWPlayer routes.
 * These fixtures contain no actual signed video or download links.
 */
class AkwamMediaTest {
    @Test fun separateQualityRoutesArePreservedWithoutInventedServers() {
        val html = """
          <div class="download-list">
            <div><span>720p</span>
              <a href="/watch/183443/11572/love-language">مشاهدة</a>
              <a href="/download/183443/11572/love-language">تحميل</a>
            </div>
            <div><span>360p</span>
              <a href="/watch/183444/11572/love-language">مشاهدة</a>
              <a href="/download/183444/11572/love-language">تحميل</a>
            </div>
          </div>
        """.trimIndent()
        val entries=AkwamParser.qualities(html)
        assertEquals(2,entries.size)
        assertEquals(listOf(720,360),entries.map { it.quality })
        assertTrue(entries.all { it.watch != null && it.download != null })
        assertEquals(2,entries.mapNotNull { it.watch }.distinct().size)
    }

    @Test fun videoElementsExposeOnlyObservedMediaAndEmbeds() {
        val html = """
           <video controls>
             <source src="https://files.example.org/real-media.mp4" type="video/mp4">
             <source src="https://files.example.org/real-media-low.mp4" type="video/mp4">
           </video>
           <iframe src="https://player.example.org/e/a1"></iframe>
        """.trimIndent()
        assertEquals(
            listOf(
                "https://files.example.org/real-media.mp4",
                "https://files.example.org/real-media-low.mp4",
                "https://player.example.org/e/a1"
            ).sorted(),
            AkwamParser.embeds(html,"https://akwam.ss/watch/183443/11572/love-language").sorted()
        )
    }

    @Test fun onlyActualHlsPlaylistsAreRecognized() {
        assertTrue(AkwamParser.isHlsManifest(
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=3500000\n720/index.m3u8"))
        assertTrue(AkwamParser.isHlsManifest(
            "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg001.ts"))
        assertFalse(AkwamParser.isHlsManifest("<html>Cloudflare challenge</html>"))
        assertFalse(AkwamParser.isHlsManifest("#EXTM3U\n#EXT-X-TARGETDURATION:6"))
    }

    @Test fun noFakePlayerLinksOrRoutesAreEmitted() {
        val html = "<div>مشاهدة جودة عالية - 720p</div>"
        assertTrue(AkwamParser.embeds(html,"https://akwam.ss").isEmpty())
        assertTrue(AkwamParser.qualities(html).isEmpty())
        assertTrue(AkwamParser.downloads(html,"https://akwam.ss").isEmpty())
    }
}
