package com.akwam

import org.junit.Assert.*
import org.junit.Test

/** HTML fixtures matching akwam.ss, without Android runtime dependencies. */
class AkwamDetailsTest {
    @Test fun actualCatalogueStructureKeepsCardTitlesAndPostersTogether() {
        val html = """
          <section>
            <div class="entry-box">
              <a class="box" href="/movie/11572/love-language">
                <img data-src="/images/love.webp" alt="poster">
              </a>
              <a class="icn play" href="/movie/11572/love-language">مشاهدة</a>
              <h3 class="entry-title"><a class="text-white"
                href="/movie/11572/love-language">Love Language</a></h3>
            </div>
            <div class="entry-box">
              <a class="box" href="/series/5908/the-diplomat">
                <img src="/images/diplomat.webp"></a>
              <h3 class="entry-title"><a class="text-white"
                href="/series/5908/the-diplomat">The Diplomat الموسم الرابع</a></h3>
            </div>
            <div class="entry-box">
              <a class="box" href="/shows/3126/the-last-first-winter-k2">
                <img src="/images/documentary.webp"></a>
              <h3 class="entry-title"><a class="text-white"
                href="/shows/3126/the-last-first-winter-k2">The Last First: Winter K2</a></h3>
            </div>
          </section>
        """.trimIndent()
        val cards=AkwamParser.cards(html)
        assertEquals(3,cards.size)
        assertEquals("Love Language",cards[0].title)
        assertEquals("The Diplomat الموسم الرابع",cards[1].title)
        assertEquals("The Last First: Winter K2",cards[2].title)
        assertFalse(cards[0].series)
        assertTrue(cards[1].series)
        assertFalse(cards[2].series)
        assertTrue(cards.all { it.poster?.startsWith("https://akwam.ss/images/")==true })
        assertFalse(cards.any { it.title=="مشاهدة" })
    }

    @Test fun readEpisodeListFromCurrentSeriesPageWithoutInventingEpisodes() {
        val doc=AkwamParser.document(
            """
              <h1>The Diplomat الموسم الرابع</h1>
              <a href="/episode/104382/the-diplomat/الحلقة-1">الحلقة 1</a>
              <a href="/episode/104389/the-diplomat/الحلقة-8">الحلقة 8</a>
              <a href="/movie/11572/love-language">اقتراح</a>
            """.trimIndent(),AkwamParser.BASE)
        val items=AkwamParser.episodes(doc,4)
        assertEquals(listOf(1,8),items.map{it.number})
        assertTrue(items.all{it.season==4})
        assertTrue(items.all{it.url.contains("/episode/")})
        assertEquals(0,AkwamParser.episodes(
            AkwamParser.document("<h1>غير متوفر</h1>",AkwamParser.BASE)).size)
    }
}
