package com.arabseed

import org.junit.Assert.*
import org.junit.Test

class ArabSeedParserTest {
    private fun page(html:String,url:String)=ArabSeedParser.doc(html,url)

    @Test fun postsAndSeriesCardsHavePosters() {
        val html="""
            <ul class="movie__blocks__ul">
              <li><div class="item__contents is__episode">
                <a class="movie__block" href="/فيلم-test-2026-مترجم/">
                  <div class="post__image"><img data-src="/uploads/test.jpg"></div>
                  <div class="post__info"><h3>فيلم Test 2026 مترجم</h3></div>
                </a>
              </div></li>
              <li><div class="series__box">
                <a href="/series/مسلسل-ingobernable/">
                  <div class="image__poster"><img src="/uploads/show.jpg"></div>
                  <div class="title___"><h3>مسلسل Ingobernable</h3></div>
                </a>
              </div></li>
            </ul>
            <ul class="page-numbers"><a class="page-numbers" href="/recently/page/2/">2</a></ul>
        """
        val d=page(html,"https://arabsed.cam/recently/")
        val list=ArabSeedParser.cards(d)
        assertEquals(2,list.size)
        assertFalse(list[0].series)
        assertTrue(list[1].series)
        assertEquals("https://arabsed.cam/uploads/test.jpg",list[0].poster)
        assertTrue(ArabSeedParser.next(d,1))
        assertFalse(ArabSeedParser.next(d,2))
    }

    @Test fun episodesAreOnlyFromCurrentSeasonAndSorted() {
        val d=page("""
            <section class="episodes__section">
              <ul><li class="selected"><a href="/series/مسلسل-ingobernable-الموسم-الاول/">الموسم الاول</a></li>
                  <li><a href="/series/مسلسل-ingobernable-الموسم-الثاني/">الموسم الثاني</a></li></ul>
              <ul class="episodes__list">
                <li><a href="/مسلسل-ingobernable-الموسم-الاول-الحلقة-10/"><div class="epi__num">الحلقة 10</div></a></li>
                <li><a href="/مسلسل-ingobernable-الموسم-الاول-الحلقة-2/"><div class="epi__num">الحلقة ٢</div></a></li>
                <li><a href="/مسلسل-ingobernable-الموسم-الاول-الحلقة-1/"><div class="epi__num">الحلقة 1</div></a></li>
              </ul>
            </section>
            <ul class="episodes__blocks__holder">
              <li><a class="episode__item" href="/مسلسل-unrelated-الحلقة-99/">الحلقة 99</a></li>
            </ul>
        ""","https://arabsed.cam/series/مسلسل-ingobernable-الموسم-الاول/")
        assertEquals(listOf(1,2),ArabSeedParser.seasonLinks(d).map{it.first})
        val eps=ArabSeedParser.episodes(d,1)
        assertEquals(listOf(1,2,10),eps.map{it.number})
        assertTrue(eps.all{it.season==1})
    }

    @Test fun watchAndDownloadPreserveDifferentHostsAndRejectUnsafeSchemes() {
        val watch=page("""
        <nav class="arabsed-watch-servers">
          <button class="arabsed-watch-server" data-src="https://doodstream.com/e/a">doodstream</button>
          <button class="arabsed-watch-server" data-src="https://doodstream.com/e/b">doodstream</button>
          <button class="arabsed-watch-server" data-src="https://mixdrop.ps/e/x">mixdrop</button>
          <button class="arabsed-watch-server" data-src="javascript:alert(1)">fake</button>
        </nav>
        ""","https://arabsed.cam/مسلسل-ingobernable-الموسم-الاول-الحلقة-10/watch/")
        assertEquals(3,ArabSeedParser.watchSources(watch).size)
        val d=page("""
        <div class="arabsed-download-grid">
          <a class="arabsed-download-card" href="https://doodstream.com/d/a">doodstream</a>
          <a class="arabsed-download-card" href="https://mixdrop.ps/f/b">mixdrop</a>
        </div>
        ""","https://arabsed.cam/مسلسل-ingobernable-الموسم-الاول-الحلقة-10/download/")
        assertEquals(2,ArabSeedParser.downloadSources(d).size)
        val base="https://arabsed.cam/مسلسل-ingobernable-الموسم-الاول-الحلقة-10/"
        assertEquals(base+"watch/",ArabSeedParser.watchUrl(base))
        assertEquals(base+"download/",ArabSeedParser.downloadUrl(base+"watch/"))
        assertNull(ArabSeedParser.siteUrl("https://arabsed.cam.evil.example/x/"))
        assertNull(ArabSeedParser.episodeBase("https://arabsed.cam/series/test/"))
    }
}
