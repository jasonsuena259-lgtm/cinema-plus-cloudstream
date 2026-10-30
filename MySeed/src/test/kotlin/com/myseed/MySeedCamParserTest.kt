package com.myseed

import org.junit.Assert.*
import org.junit.Test

class MySeedCamParserTest {
    private fun page(body: String, url: String) =
        MySeedParser.doc(body, url)

    @Test fun cardsAndPaginationFromNewMarkup() {
        val doc = page(
            """
            <div class="tt-archive-grid">
              <article class="tt-card"><a class="tt-card-link" href="https://myseed.cam/goodfellas/">
                <div class="tt-thumb"><img src="/uploads/goodfellas.jpg"></div>
                <div class="tt-card-body"><h3>مشاهدة فيلم GoodFellas 1990 مترجم اونلاين</h3>
                  <div class="tt-card-foot"><span>فيلم</span></div></div>
              </a></article>
              <article class="tt-card"><a class="tt-card-link" href="/series/doctor-x-mafia-in-white">
                <div class="tt-thumb"><img src="/uploads/doctor.jpg"></div>
                <div class="tt-card-body"><h3>Doctor X: Mafia in White</h3>
                  <div class="tt-card-foot"><span>مسلسل</span></div></div>
              </a></article>
            </div>
            <nav class="tt-pagination">
              <a class="active" href="/films/page/1">1</a>
              <a href="/films/page/2">2</a>
            </nav>
            """, "https://myseed.cam/films"
        )
        val cards = MySeedCamParser.cards(doc)
        assertEquals(2, cards.size)
        assertFalse(cards.first().series)
        assertTrue(cards.last().series)
        assertEquals("https://myseed.cam/uploads/goodfellas.jpg", cards.first().poster)
        assertTrue(MySeedCamParser.next(doc, 1))
        assertFalse(MySeedCamParser.next(doc, 2))
        assertEquals("https://myseed.cam/films/page/2", MySeedCamParser.pageUrl("/films", 2))
        assertTrue(MySeedCamParser.pageUrl("/category/افلام-اجنبي", 2)!!.contains("page=2"))
        assertTrue(MySeedCamParser.sections.any { it.first == "/seriestv" })
    }

    @Test fun seriesSeasonsHaveCorrectEpisodeOrder() {
        val doc = page(
            """
            <section class="tt-season" id="season--2"><div class="tt-season-head"><h2>الموسم 2</h2></div>
              <a class="tt-episode-card" href="/show-n3/"><div class="tt-episode-copy"><strong>الحلقة 3</strong><small>الحلقة 3</small></div></a>
              <a class="tt-episode-card" href="/show-n1/"><div class="tt-episode-copy"><strong>الحلقة 1</strong><small>الحلقة 1</small></div></a>
            </section>
            <section class="tt-season" id="season--1"><div class="tt-season-head"><h2>الموسم 1</h2></div>
              <a class="tt-episode-card" href="/old-n1/"><div class="tt-episode-copy"><strong>الحلقة 1</strong><small>الحلقة 1</small></div></a>
            </section>
            <article class="tt-card"><a href="/other-n77/">الحلقة 77</a></article>
            """, "https://myseed.cam/series/show"
        )
        val episodes = MySeedCamParser.episodes(doc)
        assertEquals(listOf(1, 2, 2), episodes.map { it.season })
        assertEquals(listOf(1, 1, 3), episodes.map { it.number })
        assertEquals(3, episodes.size)
    }

    @Test fun directWatchAndDownloadButtonsAreRealUrls() {
        val watch = page(
            """
            <div class="tt-watch-servers">
              <button class="tt-watch-server active" data-src="https://mixdrop.ps/e/abc">سيرفر 1</button>
              <button class="tt-watch-server" data-src="https://voe.sx/e/def">سيرفر 2</button>
              <button class="tt-watch-server" data-src="https://mixdrop.ps/e/abc">سيرفر 3</button>
              <button class="tt-watch-server" data-src="javascript:alert(1)">ملغى</button>
            </div>
            """, "https://myseed.cam/watch/goodfellas/servers"
        )
        val sources = MySeedCamParser.watchSources(watch)
        assertEquals(2, sources.size)
        assertEquals(listOf("Mixdrop", "Voe"), sources.map { it.name })
        val download = page(
            """
            <div class="tt-download-list">
              <div><span>تحميل 1</span><a href="https://gofile.io/d/abc">تحميل</a></div>
              <div><span>تحميل 2</span><a href="https://mixdrop.ps/f/xyz">تحميل</a></div>
            </div>
            """, "https://myseed.cam/download/goodfellas"
        )
        assertEquals(2, MySeedCamParser.downloadSources(download).size)
        assertEquals("https://myseed.cam/watch/goodfellas/servers",
            MySeedCamParser.watchUrl("https://myseed.cam/goodfellas/"))
        assertEquals("https://myseed.cam/download/goodfellas",
            MySeedCamParser.downloadUrl("https://myseed.cam/watch/goodfellas/servers"))
    }

    @Test fun unsafeUrlsAndOtherPageTypesAreRejected() {
        assertNull(MySeedCamParser.siteUrl("https://evil.example/watch/goodfellas/servers"))
        assertNull(MySeedCamParser.siteUrl("https://myseed.cam.evil.test/goodfellas/"))
        assertNull(MySeedCamParser.slug("https://myseed.cam/films"))
        assertNull(MySeedCamParser.slug("https://myseed.cam/series/doctor-x"))
        assertNull(MySeedCamParser.watchUrl("https://myseed.cam/category/افلام-اجنبي"))
        assertTrue(MySeedCamParser.watchSources(page(
            """<button class="tt-watch-server" data-src="javascript:alert(1)"></button>""",
            "https://myseed.cam/watch/goodfellas/servers"
        )).isEmpty())
        assertEquals(12, MySeedCamParser.number("الموسم ١٢"))
    }
}
