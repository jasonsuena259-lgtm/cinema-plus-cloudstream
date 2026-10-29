package com.akwam

import org.junit.Assert.*
import org.junit.Test

class AkwamParserTest{
    @Test fun realMovieAndSeriesCardRoutes(){
        val html="""<div class="card">
          <a href="/movie/11572/love-language"><img src="/img/film.jpg"><h3>Love Language</h3></a>
          </div><a href="/series/5908/the-diplomat"><h3>The Diplomat الموسم الرابع</h3></a>
          <a href="/watch/183443/11572/love-language">مشاهدة</a>
          <a href="/download/183443/11572/love-language">تحميل</a>"""
        val items=AkwamParser.cards(html,AkwamParser.BASE)
        assertEquals(2,items.size)
        assertFalse(items[0].series);assertTrue(items[1].series)
        assertEquals("Love Language",items[0].title)
        assertEquals("https://akwam.ss/img/film.jpg",items[0].poster)
        assertFalse(items.any{it.title=="مشاهدة"})
    }
    @Test fun exactRealEpisodeRoutesAndOrdering(){
        val html="""<section>
          <a href="/episode/104389/the-diplomat/الحلقة-8"><h2>حلقة 8 : مسلسل The Diplomat الموسم الرابع 8</h2></a>
          <a href="/episode/104382/the-diplomat/الحلقة-1"><h2>حلقة 1 : مسلسل The Diplomat الموسم الرابع 1</h2></a>
          <a href="/episode/104384/the-diplomat/الحلقة-3">حلقة 3</a>
          </section>"""
        val episodes=AkwamParser.episodes(AkwamParser.document(html,AkwamParser.BASE),4)
        assertEquals(listOf(1,3,8),episodes.map{it.number})
        assertTrue(episodes.all{it.season==4})
        assertTrue(episodes.all{it.url.startsWith("https://akwam.ss/episode/")})
    }
    @Test fun everyQualityHasItsOwnWatchAndDownload(){
        val html="""<div>
        <a href="/watch/183443/11572/love-language">مشاهدة</a>
        <a href="/download/183443/11572/love-language">تحميل 625.3 MB</a>
        <a href="/watch/183444/11572/love-language">مشاهدة</a>
        <a href="/download/183444/11572/love-language">تحميل 292.6 MB</a>
        </div>"""
        val found=AkwamParser.qualities(html,AkwamParser.BASE)
        assertEquals(2,found.size)
        assertEquals(listOf("183443","183444"),found.map{it.id})
        assertTrue(found.all{it.watch?.contains("/watch/")==true})
        assertTrue(found.all{it.download?.contains("/download/")==true})
    }
    @Test fun downloadPageIncludesRealMp4LinksOnly(){
        val html="""<a href="https://s203d1.downet.net/download/1234/movie.mp4">Download</a>
          <a href="https://evil.example/ads.html">AD</a>
          <a href="javascript:alert(1)">Untrusted</a>"""
        val found=AkwamParser.downloads(html,"https://akwam.ss/download/183443/11572/test")
        assertEquals(1,found.size)
        assertTrue(found.single().endsWith("/movie.mp4"))
    }
    @Test fun paginationDoesNotAssumeInfinitePages(){
        val doc=AkwamParser.document("""<a href="/movies?page=2">2</a>""",AkwamParser.BASE)
        assertTrue(AkwamParser.hasNext(doc,1))
        assertFalse(AkwamParser.hasNext(doc,2))
    }
    @Test fun rejectForeignCatalogueUrls(){
        assertNull(AkwamParser.site("https://some-other-site.example/movie/11572"))
        assertNull(AkwamParser.site("javascript:alert(1)"))
    }
}