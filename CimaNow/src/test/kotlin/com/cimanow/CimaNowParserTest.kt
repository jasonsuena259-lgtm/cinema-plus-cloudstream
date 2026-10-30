package com.cimanow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import java.net.Proxy

class CimaNowParserTest {
    private val base = "https://cimanow.cc/home"
    @Test fun catalogCardsAndSections() {
        val html = """
            <html><header><nav><a href='/category/movies/'>الأفلام</a></nav></header>
            <section><h2>أفلام جديدة</h2>
             <article><a href='/فيلم-اختبار/'><img data-src='/poster.jpg' alt='فيلم اختبار'/>
               <li aria-label='title'>فيلم اختبار</li><li aria-label='year'>2026</li></a></article>
            </section></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, base)
        val cards = CimaNowParser.cards(doc)
        assertEquals(1, cards.size)
        assertEquals("فيلم اختبار", cards[0].title)
        assertEquals(2026, cards[0].year)
        assertEquals("https://cimanow.cc/poster.jpg", cards[0].poster)
        assertEquals(1, CimaNowParser.groups(doc).size)
        assertEquals(1, CimaNowParser.sections(doc).size)
    }
    @Test fun episodesStayNumericallyOrderedAndNoOutOfSiteAds() {
        val doc = Jsoup.parse("""
            <ul id='eps'>
              <li><a href='/مسلسل-الاختبار-الحلقة-10/'><em>10</em> الحلقة 10</a></li>
              <li><a href='/مسلسل-الاختبار-الحلقة-2/'><em>2</em> الحلقة 2</a></li>
              <li><a href='https://ads.test/episode'>إعلان<em>3</em></a></li>
            </ul>
        """.trimIndent(), base)
        val episodes = CimaNowParser.episodes(doc)
        assertEquals(listOf(2, 10), episodes.map { it.number })
    }
    @Test fun videoListSkipsTrailersAndDuplicates() {
        val doc = Jsoup.parse("""
            <ul id='download'><li aria-label='quality'>
              <a href='https://cdn.example/media.mp4'>720</a>
              <a href='https://cdn.example/media.mp4'>720</a>
              <a href='https://youtube.com/watch?v=000'>trailer</a>
            </li></ul>
        """.trimIndent(), base)
        assertEquals(listOf("https://cdn.example/media.mp4"), CimaNowParser.sources(doc))
    }
    @Test fun settingsRejectBadUrlsAndValidateProxy() {
        assertEquals("https://cimanow.cc", CimaNowSettings.normalizeDomain("cimanow.cc/home"))
        assertNull(CimaNowSettings.normalizeDomain("http://cimanow.cc"))
        assertNull(CimaNowSettings.normalizeDomain("https://user:pass@cimanow.cc"))
        assertEquals(Proxy.Type.SOCKS, CimaNowSettings.parseProxy("socks5://127.0.0.1:9050")?.type())
        assertEquals(Proxy.Type.HTTP, CimaNowSettings.parseProxy("http://proxy.example:8080")?.type())
        assertNull(CimaNowSettings.parseProxy("socks5://proxy.example"))
        assertNull(CimaNowSettings.parseProxy("http://user:pass@proxy.example:8080"))
    }
}
