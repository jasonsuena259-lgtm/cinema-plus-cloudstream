package com.akwam

import org.junit.Assert.*
import org.junit.Test

/**
 * Network-agnostic regression tests: Akwam.ss routes are quality-specific.
 * Full playback must separately pass player/CDN tests, not just HTML parsing.
 */
class AkwamResolverTest {
    @Test fun realMovieQualityPairsDoNotCollapseDifferentFilesIntoOneServer() {
        val html = """
          <section>
            <div class="quality-row"><span>720p</span>
              <a href="/watch/183443/11572/love-language">مشاهدة</a>
              <a href="/download/183443/11572/love-language">تحميل</a>
            </div>
            <div class="quality-row"><span>360p</span>
              <a href="/watch/183444/11572/love-language">مشاهدة</a>
              <a href="/download/183444/11572/love-language">تحميل</a>
            </div>
          </section>
        """.trimIndent()
        val sources=AkwamParser.qualities(html)
        assertEquals(2,sources.size)
        assertEquals(listOf(720,360),sources.map{it.quality})
        assertEquals(2,sources.mapNotNull{it.download}.distinct().size)
        assertEquals(2,sources.mapNotNull{it.watch}.distinct().size)
        assertTrue(sources.all{it.watch!!.startsWith("https://akwam.ss/watch/")})
    }

    @Test fun watchElementsAndDownloadFilesAreIndependentVerifiedCandidates() {
        val watch = """
          <video controls>
            <source type="video/mp4" src="https://cdn.example.org/movie-720.mp4">
            <source type="video/mp4" src="https://cdn.example.org/movie-360.mp4">
          </video>
        """.trimIndent()
        val download = """
          <a href="https://s203d1.downet.net/download/movie.mp4">Download</a>
          <a href="https://tracking.example.org/ad.html">Advert</a>
        """.trimIndent()
        val watchCandidates=AkwamParser.embeds(watch,AkwamParser.BASE)
        val downloadCandidates=AkwamParser.downloads(
            download,"https://akwam.ss/download/183443/11572/love-language")
        assertEquals(2,watchCandidates.size)
        assertEquals(1,downloadCandidates.size)
        assertTrue(downloadCandidates.single().endsWith("movie.mp4"))
        assertTrue(watchCandidates.none{it.endsWith(".html")})
    }

    @Test fun rejectUnobservedOrForeignAkwamRoutes() {
        assertNull(AkwamParser.site("https://other.example/watch/183443/11572"))
        assertTrue(AkwamParser.qualities("<button>1080p</button>").isEmpty())
        assertTrue(AkwamParser.embeds("<div>Loading player...</div>",AkwamParser.BASE).isEmpty())
    }
}
